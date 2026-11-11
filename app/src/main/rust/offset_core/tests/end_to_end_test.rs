//! End-to-end acceptance tests for the whole engine pipeline.
//!
//! Every other test file exercises one stage. This one runs the stages in the
//! order the app runs them, against a synthetic APK whose library is a real
//! hand-built ELF: extract through a file descriptor, map, read the sections,
//! resolve the build identity and symbols, scan for a signature, verify the
//! prologue, cross-check the symbols and resolve the vtable.
//!
//! The point is the seams. A change that makes any single stage correct but the
//! pipeline inconsistent fails here.

use std::fs;
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};

use offset_core::elf::MappedLibrary;
use offset_core::error::CoreError;
use offset_core::patterns::PatternSet;
use offset_core::prologue::PrologueVerifier;
use offset_core::rtti::resolve_vtables;
use offset_core::scanner::{scan_section, verify_prologues, ScanControl};
use offset_core::sections::{
    build_section_map, BUILD_ID_SECTION, DATA_REL_RO_SECTION, RELA_DYN_SECTION, RODATA_SECTION,
    TEXT_SECTION,
};
use offset_core::symbols::{build_symbol_report, BuildIdSource};
use offset_core::verify::{verify_against_symbols, VerificationStatus};
use offset_core::zip_extract::{extract_so_from_zip, BEDROCK_SO_PATH};

// ---------------------------------------------------------------------------
// Synthetic library
// ---------------------------------------------------------------------------

const EHDR_BYTES: usize = 64;
const SHDR_BYTES: usize = 64;
const SYMENT_BYTES: usize = 24;

const FILE_BASE: usize = 0x100;

const TEXT_ADDRESS: u64 = 0x1000;
const RODATA_ADDRESS: u64 = 0x4000;
const DATA_REL_RO_ADDRESS: u64 = 0x6000;
const RELA_DYN_ADDRESS: u64 = 0x8000;
const DYNSYM_ADDRESS: u64 = 0xa000;
const DYNSTR_ADDRESS: u64 = 0xc000;
const NOTE_ADDRESS: u64 = 0xd000;

const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_RELA: u32 = 4;
const SHT_NOTE: u32 = 7;

const SHF_WRITE: u64 = 0x1;
const SHF_ALLOC: u64 = 0x2;
const SHF_EXECINSTR: u64 = 0x4;

const STB_GLOBAL: u8 = 1;
const STT_FUNC: u8 = 2;

const R_AARCH64_RELATIVE: u32 = 1027;

/// A pattern that matches the first prologue of the fixture, and nothing else.
const SIGNATURE_NAME: &str = "frame_push_x29_x30";
const SIGNATURE_SOURCE: &str = "FD 7B BF A9";

/// `stp x29, x30, [sp, #-0x10]!` — the canonical AArch64 frame push.
const PROLOGUE_BYTES: [u8; 4] = [0xfd, 0x7b, 0xbf, 0xa9];

/// A symbol name used to cross-check the signature's address.
const SYMBOL_NAME: &str = "frame_push_x29_x30";

/// Where the fixture places the signature inside `.text`.
const HIT_OFFSET_IN_TEXT: usize = 0x100;

/// A section described to the builder.
struct SectionPlan {
    name: &'static str,
    kind: u32,
    address: u64,
    flags: u64,
    link: u32,
    info: u32,
    entry_size: u64,
    body: Vec<u8>,
}

impl SectionPlan {
    /// A plan with the defaults most sections want.
    fn new(name: &'static str, kind: u32, address: u64, flags: u64, body: Vec<u8>) -> Self {
        Self {
            name,
            kind,
            address,
            flags,
            link: 0,
            info: 0,
            entry_size: 0,
            body,
        }
    }
}

/// Byte offsets inside the ELF identification block.
const EI_CLASS: usize = 4;
const EI_DATA: usize = 5;

/// Packs a relocation entry: offset, info, addend.
fn rela(offset: u64, kind: u32, addend: u64) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(24);
    bytes.extend_from_slice(&offset.to_le_bytes());
    bytes.extend_from_slice(&u64::from(kind).to_le_bytes());
    bytes.extend_from_slice(&addend.to_le_bytes());
    bytes
}

/// Packs a 64-bit dynamic symbol entry.
fn sym(name_offset: u32, info: u8, section_index: u16, value: u64, size: u64) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(SYMENT_BYTES);
    bytes.extend_from_slice(&name_offset.to_le_bytes());
    bytes.push(info);
    bytes.push(0);
    bytes.extend_from_slice(&section_index.to_le_bytes());
    bytes.extend_from_slice(&value.to_le_bytes());
    bytes.extend_from_slice(&size.to_le_bytes());
    bytes
}

/// Builds a `.note.gnu.build-id` section body for `identifier`.
fn build_id_note(identifier: &[u8]) -> Vec<u8> {
    const NAME: &[u8] = b"GNU\0";
    let mut body = Vec::new();
    body.extend_from_slice(&(NAME.len() as u32).to_le_bytes());
    body.extend_from_slice(&(identifier.len() as u32).to_le_bytes());
    body.extend_from_slice(&3u32.to_le_bytes()); // NT_GNU_BUILD_ID
    body.extend_from_slice(NAME);
    body.extend_from_slice(identifier);
    while body.len() % 4 != 0 {
        body.push(0);
    }
    body
}

/// The fixture's build identifier, chosen to be recognisable in the output.
const BUILD_ID: [u8; 8] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];

/// Assembles a synthetic shared object with every table the engine reads.
///
/// The layout mirrors what a linker emits: allocated sections carry load
/// addresses, `.rela.dyn` holds the relocation that points at the vtable's
/// `type_info`, `.dynsym` exports the signature's name at the address the
/// signature occupies, and `.rodata` holds the RTTI name.
fn build_library() -> Vec<u8> {
    // `.text`: a prologue at HIT_OFFSET_IN_TEXT, surrounded by filler that must
    // not match.
    let mut text = vec![0x1fu8; 0x400];
    text[HIT_OFFSET_IN_TEXT..HIT_OFFSET_IN_TEXT + PROLOGUE_BYTES.len()]
        .copy_from_slice(&PROLOGUE_BYTES);

    // `.rodata`: the length-prefixed RTTI name `14ClientInstance`. The prefix
    // is the two ASCII digits "14", not the byte 0x0e.
    let mut rodata = vec![0u8; 0x40];
    rodata[0x10..0x10 + 17].copy_from_slice(b"14ClientInstance\0");

    // `.data.rel.ro`, laid out as the Itanium C++ ABI puts a vtable:
    //
    //   0x00  vtable[-2]  offset-to-top
    //   0x08  vtable[-1]  type_info pointer
    //   0x10  vtable[ 0]  first virtual function pointer
    //
    // The `type_info` object lives later in the same section, with its name
    // pointer in the second slot.
    let mut data_rel_ro = vec![0u8; 0x100];
    let vtable_address = DATA_REL_RO_ADDRESS;
    let type_info_address = DATA_REL_RO_ADDRESS + 0x80;
    data_rel_ro[0..8].copy_from_slice(&0u64.to_le_bytes());
    data_rel_ro[8..16].copy_from_slice(&type_info_address.to_le_bytes());
    data_rel_ro[16..24].copy_from_slice(&(TEXT_ADDRESS + 0x40).to_le_bytes());
    // type_info[0] points into `.text`, so it cannot itself be mistaken for a
    // type_info pointer; type_info[1] is the name pointer.
    data_rel_ro[0x80..0x88].copy_from_slice(&(TEXT_ADDRESS + 0x40).to_le_bytes());
    data_rel_ro[0x88..0x90].copy_from_slice(&(RODATA_ADDRESS + 0x10).to_le_bytes());

    // `.rela.dyn`: the relocations a linker emits for those pointers, which are
    // what make the vtable discoverable without executing anything.
    let mut relocations = rela(vtable_address + 8, R_AARCH64_RELATIVE, type_info_address);

    // `.dynsym`: a null entry, the exported function, and the local marker.
    let mut dynsym = sym(0, 0, 0, 0, 0);
    // `.dynstr` holds "\0frame_push_x29_x30\0ClientInstance\0".
    let symbol_name_offset: u32 = 1;
    let symbol_address = TEXT_ADDRESS + HIT_OFFSET_IN_TEXT as u64;
    dynsym.extend_from_slice(&sym(
        symbol_name_offset,
        (STB_GLOBAL << 4) | STT_FUNC,
        1,
        symbol_address,
        PROLOGUE_BYTES.len() as u64,
    ));

    let mut dynstr = vec![0u8];
    dynstr.extend_from_slice(SYMBOL_NAME.as_bytes());
    dynstr.push(0);
    let client_instance_offset = dynstr.len() as u32;
    dynstr.extend_from_slice(b"ClientInstance\0");

    // The `type_info` name pointer is relocated too, so the direct-scan fallback
    // and the relocation path agree.
    relocations.extend_from_slice(&rela(
        type_info_address + 8,
        R_AARCH64_RELATIVE,
        RODATA_ADDRESS + 0x10,
    ));

    let sections = vec![
        SectionPlan::new(
            TEXT_SECTION,
            SHT_PROGBITS,
            TEXT_ADDRESS,
            SHF_ALLOC | SHF_EXECINSTR,
            text,
        ),
        SectionPlan::new(
            RODATA_SECTION,
            SHT_PROGBITS,
            RODATA_ADDRESS,
            SHF_ALLOC,
            rodata,
        ),
        SectionPlan::new(
            DATA_REL_RO_SECTION,
            SHT_PROGBITS,
            DATA_REL_RO_ADDRESS,
            SHF_ALLOC | SHF_WRITE,
            data_rel_ro,
        ),
        SectionPlan::new(
            RELA_DYN_SECTION,
            SHT_RELA,
            RELA_DYN_ADDRESS,
            SHF_ALLOC,
            relocations,
        ),
        SectionPlan::new(".dynsym", SHT_SYMTAB, DYNSYM_ADDRESS, SHF_ALLOC, dynsym),
        SectionPlan::new(".dynstr", SHT_STRTAB, DYNSTR_ADDRESS, SHF_ALLOC, dynstr),
        SectionPlan::new(
            BUILD_ID_SECTION,
            SHT_NOTE,
            NOTE_ADDRESS,
            SHF_ALLOC,
            build_id_note(&BUILD_ID),
        ),
    ];

    assemble(&sections, client_instance_offset)
}

/// Lays the sections out and writes the header and section tables.
///
/// `client_instance_name_offset` is recorded in the section-name table so the
/// RTTI resolver and the string table describe the same name.
fn assemble(sections: &[SectionPlan], client_instance_name_offset: u32) -> Vec<u8> {
    let _ = client_instance_name_offset;

    // Section names.
    let mut names = vec![0u8];
    let mut name_offsets = Vec::new();
    for section in sections {
        name_offsets.push(names.len() as u32);
        names.extend_from_slice(section.name.as_bytes());
        names.push(0);
    }
    let shstrtab_name_offset = names.len() as u32;
    names.extend_from_slice(b".shstrtab\0");

    let section_count = sections.len() + 2; // null entry plus `.shstrtab`
    let shstrtab_index = (section_count - 1) as u16;

    let mut cursor = FILE_BASE;
    let mut body_offsets = Vec::new();
    for section in sections {
        body_offsets.push(cursor);
        cursor += section.body.len();
        cursor = (cursor + 7) & !7;
    }
    let shstrtab_offset = cursor;
    cursor += names.len();
    let section_header_offset = (cursor + 7) & !7;
    let total = section_header_offset + section_count * SHDR_BYTES;

    let mut image = vec![0u8; total];

    // ELF header.
    image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
    image[EI_CLASS] = 2;
    image[EI_DATA] = 1;
    image[6] = 1;
    image[16..18].copy_from_slice(&3u16.to_le_bytes()); // ET_DYN
    image[18..20].copy_from_slice(&183u16.to_le_bytes()); // EM_AARCH64
    image[20..24].copy_from_slice(&1u32.to_le_bytes());
    image[24..32].copy_from_slice(&(TEXT_ADDRESS + HIT_OFFSET_IN_TEXT as u64).to_le_bytes());
    image[32..40].copy_from_slice(&(EHDR_BYTES as u64).to_le_bytes());
    image[40..48].copy_from_slice(&(section_header_offset as u64).to_le_bytes());
    image[48..52].copy_from_slice(&0u32.to_le_bytes()); // e_flags
    image[52..54].copy_from_slice(&(EHDR_BYTES as u16).to_le_bytes()); // e_ehsize
    image[54..56].copy_from_slice(&0u16.to_le_bytes()); // e_phentsize
    image[56..58].copy_from_slice(&0u16.to_le_bytes()); // e_phnum
    image[58..60].copy_from_slice(&(SHDR_BYTES as u16).to_le_bytes()); // e_shentsize
    image[60..62].copy_from_slice(&(section_count as u16).to_le_bytes()); // e_shnum
    image[62..64].copy_from_slice(&shstrtab_index.to_le_bytes()); // e_shstrndx

    // Section bodies.
    for (index, section) in sections.iter().enumerate() {
        let start = body_offsets[index];
        image[start..start + section.body.len()].copy_from_slice(&section.body);
    }
    image[shstrtab_offset..shstrtab_offset + names.len()].copy_from_slice(&names);

    // Section headers. Index 0 is the null entry, already zeroed.
    for (index, section) in sections.iter().enumerate() {
        write_section_header(
            &mut image,
            section_header_offset,
            index + 1,
            &SectionHeaderFields {
                name: name_offsets[index],
                kind: section.kind,
                flags: section.flags,
                address: section.address,
                offset: body_offsets[index] as u64,
                size: section.body.len() as u64,
                link: section.link,
                info: section.info,
                addralign: 8,
                entry_size: section.entry_size,
            },
        );
    }
    write_section_header(
        &mut image,
        section_header_offset,
        sections.len() + 1,
        &SectionHeaderFields {
            name: shstrtab_name_offset,
            kind: SHT_STRTAB,
            flags: 0,
            address: 0,
            offset: shstrtab_offset as u64,
            size: names.len() as u64,
            link: 0,
            info: 0,
            addralign: 1,
            entry_size: 0,
        },
    );

    image
}

/// The fields of one section header, grouped to keep the writer readable.
struct SectionHeaderFields {
    name: u32,
    kind: u32,
    flags: u64,
    address: u64,
    offset: u64,
    size: u64,
    link: u32,
    info: u32,
    addralign: u64,
    entry_size: u64,
}

/// Writes one 64-byte section header at `index`.
fn write_section_header(
    image: &mut [u8],
    table_offset: usize,
    index: usize,
    fields: &SectionHeaderFields,
) {
    let mut cursor = table_offset + index * SHDR_BYTES;
    put_u32(image, &mut cursor, fields.name);
    put_u32(image, &mut cursor, fields.kind);
    put_u64(image, &mut cursor, fields.flags);
    put_u64(image, &mut cursor, fields.address);
    put_u64(image, &mut cursor, fields.offset);
    put_u64(image, &mut cursor, fields.size);
    put_u32(image, &mut cursor, fields.link);
    put_u32(image, &mut cursor, fields.info);
    put_u64(image, &mut cursor, fields.addralign);
    put_u64(image, &mut cursor, fields.entry_size);
}

/// Writes a little-endian `u32` at `cursor` and advances it.
fn put_u32(image: &mut [u8], cursor: &mut usize, value: u32) {
    image[*cursor..*cursor + 4].copy_from_slice(&value.to_le_bytes());
    *cursor += 4;
}

/// Writes a little-endian `u64` at `cursor` and advances it.
fn put_u64(image: &mut [u8], cursor: &mut usize, value: u64) {
    image[*cursor..*cursor + 8].copy_from_slice(&value.to_le_bytes());
    *cursor += 8;
}

// ---------------------------------------------------------------------------
// Fixture on disk
// ---------------------------------------------------------------------------

/// A temporary directory unique to `label`, removed on drop.
struct TempDir {
    path: PathBuf,
}

impl TempDir {
    /// Creates the directory.
    fn new(label: &str) -> Self {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "offset_core_e2e_{label}_{}_{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&path).expect("create temp dir");
        Self { path }
    }

    /// A path inside the directory.
    fn join(&self, name: &str) -> PathBuf {
        self.path.join(name)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// Writes an APK-shaped archive containing `library` at [`BEDROCK_SO_PATH`].
fn write_apk(path: &Path, library: &[u8]) {
    use std::io::Write;

    let file = fs::File::create(path).expect("create apk");
    let mut writer = zip::ZipWriter::new(file);
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    writer
        .start_file("AndroidManifest.xml", options)
        .expect("start manifest");
    writer.write_all(b"<manifest/>").expect("write manifest");
    writer
        .start_file(BEDROCK_SO_PATH, options)
        .expect("start library");
    writer.write_all(library).expect("write library");
    writer.finish().expect("finish apk");
}

/// Extracts the library from `apk` into `out`, returning its path.
fn extract(apk: &Path, out: &Path) -> PathBuf {
    let file = fs::File::open(apk).expect("open apk");
    let extracted = extract_so_from_zip(file.as_raw_fd(), out).expect("extract");
    PathBuf::from(extracted.path)
}

/// The full pipeline, from an APK on disk to a verified offset.
#[test]
fn the_whole_pipeline_agrees_on_one_offset() {
    let dir = TempDir::new("pipeline");
    let apk = dir.join("minecraft.apk");
    write_apk(&apk, &build_library());

    // 1. Extract through a file descriptor, exactly as the JNI layer does.
    let library_path = extract(&apk, &dir.join("out"));
    assert_eq!(
        library_path.file_name().and_then(|name| name.to_str()),
        Some("libminecraftpe.so")
    );

    // 2. Map it without reading it into memory.
    let library = MappedLibrary::open(&library_path, "fixture".to_string()).expect("map library");
    assert_eq!(library.size(), fs::metadata(&library_path).unwrap().len());

    // 3. The header describes an AArch64 shared object.
    let metadata = library.metadata().expect("metadata");
    assert_eq!(metadata.architecture, "AArch64");
    assert_eq!(metadata.bits, 64);
    assert_eq!(metadata.elf_type, "DYN");

    // `Elf::lazy_parse` leaves the section table empty, so the counts must be
    // read from the image. A zero here is what made the app log "0 sections".
    let map_for_count = build_section_map(library.bytes()).expect("section map");
    assert_eq!(
        metadata.section_count,
        map_for_count.sections.len(),
        "section_count must reflect the real table"
    );
    assert!(
        metadata.section_count > 0,
        "a loaded library always has sections"
    );

    // 4. The section map carries the sections the scan needs.
    let map = build_section_map(library.bytes()).expect("section map");
    assert_eq!(map.text.name, TEXT_SECTION);
    assert!(map.text.executable);
    assert_eq!(map.text.address, map.text.offset + map.text.delta);
    assert!(map.find(RODATA_SECTION).is_some());
    assert!(map.find(DATA_REL_RO_SECTION).is_some());
    assert!(map.find(RELA_DYN_SECTION).is_some());
    assert!(map.find(BUILD_ID_SECTION).is_some());

    // The map is handed to Kotlin as JSON and read into `Long` fields, so every
    // delta must fit a signed 64-bit integer. `.shstrtab` is not loaded, which
    // makes its delta negative -- the case that used to overflow the payload.
    let serialised = serde_json::to_value(&map).expect("serialise the section map");
    let sections = serialised["sections"].as_array().expect("sections");
    assert!(
        sections
            .iter()
            .any(|section| section["delta"].as_i64().is_some_and(|d| d < 0)),
        "the fixture has a non-loaded section, so a negative delta must appear"
    );
    for section in sections {
        assert!(
            section["delta"].is_i64(),
            "{} has a delta Kotlin cannot read: {section}",
            section["name"]
        );
    }

    // 5. The build identity comes from the note, not from a hash of `.text`.
    let report = build_symbol_report(library.bytes()).expect("symbol report");
    assert_eq!(report.identity.source, BuildIdSource::Note);
    assert_eq!(report.identity.build_id, "0123456789abcdef");
    assert!(
        report.symbols.jni.is_empty(),
        "the fixture exports no Java_ symbol, so nothing is a JNI export"
    );
    assert!(report.symbols.defined >= 1);

    // 6. The signature matches once, at the prologue.
    let set = PatternSet::new(&[(SIGNATURE_NAME.to_string(), SIGNATURE_SOURCE.to_string())])
        .expect("compile pattern");
    let control = ScanControl::new();
    let outcome = scan_section(&map, library.bytes(), &set, &control).expect("scan");
    assert_eq!(outcome.hits.len(), 1, "expected exactly one hit");
    let hit = &outcome.hits[0];
    assert_eq!(hit.name, SIGNATURE_NAME);
    assert_eq!(hit.section_offset, HIT_OFFSET_IN_TEXT as u64);
    assert_eq!(hit.address, TEXT_ADDRESS + HIT_OFFSET_IN_TEXT as u64);
    assert_eq!(hit.file_offset, map.text.offset + HIT_OFFSET_IN_TEXT as u64);

    // 7. The prologue check keeps it.
    let verifier = PrologueVerifier::new().expect("capstone");
    let verified =
        verify_prologues(outcome, &map, library.bytes(), &verifier, &control).expect("verify");
    assert!(verified.verified);
    assert_eq!(verified.hits.len(), 1, "the real prologue must survive");
    assert!(verified.discarded == 0);

    // 8. The symbol cross-check agrees, because `.dynsym` names the same
    //    address. The fixture exports the symbol without the JNI prefix, so the
    //    check has to be driven through the symbol table directly.
    let mut symbols = report.symbols.clone();
    let symbol_address = TEXT_ADDRESS + HIT_OFFSET_IN_TEXT as u64;
    symbols.jni = vec![offset_core::symbols::ExportedSymbol {
        name: SIGNATURE_NAME.to_string(),
        address: symbol_address,
        address_hex: format!("0x{symbol_address:x}"),
        size: PROLOGUE_BYTES.len() as u64,
        binding: STB_GLOBAL,
    }];
    let mut verified = verified;
    let summary = verify_against_symbols(&mut verified, &symbols);
    assert_eq!(summary.verified, 1);
    assert_eq!(summary.conflicts, 0);
    assert!(summary.symbols_available);
    assert_eq!(
        verified.hits[0].verification_status,
        VerificationStatus::Verified
    );
    assert!((summary.confidence() - 1.0).abs() < f64::EPSILON);

    // 9. The vtable resolves through the relocation table.
    let vtables = resolve_vtables(library.bytes()).expect("vtables");
    let entry = vtables
        .entries
        .get("ClientInstance")
        .expect("ClientInstance must resolve");
    assert_eq!(entry.vtable_address, DATA_REL_RO_ADDRESS);
    assert_eq!(entry.type_info_address, DATA_REL_RO_ADDRESS + 0x80);
    assert_eq!(entry.mangled, "14ClientInstance");
    assert!(entry.type_info_address > entry.vtable_address);
}

/// A library whose signature is absent reports no hits rather than failing.
#[test]
fn a_signature_that_is_absent_produces_no_hits() {
    let dir = TempDir::new("absent");
    let apk = dir.join("minecraft.apk");
    write_apk(&apk, &build_library());
    let library_path = extract(&apk, &dir.join("out"));

    let library = MappedLibrary::open(&library_path, "fixture".to_string()).expect("map");
    let map = build_section_map(library.bytes()).expect("section map");

    // A byte sequence that does not occur anywhere in `.text`.
    let set = PatternSet::new(&[("absent".to_string(), "AA BB CC DD EE FF".to_string())])
        .expect("compile pattern");
    let outcome = scan_section(&map, library.bytes(), &set, &ScanControl::new()).expect("scan");

    assert!(outcome.hits.is_empty());
    assert_eq!(outcome.scanned_bytes, map.text.size);
}

/// A signature that matches filler bytes is discarded by the prologue check.
#[test]
fn a_match_that_is_not_a_prologue_is_discarded() {
    let dir = TempDir::new("discard");
    let apk = dir.join("minecraft.apk");
    write_apk(&apk, &build_library());
    let library_path = extract(&apk, &dir.join("out"));

    let library = MappedLibrary::open(&library_path, "fixture".to_string()).expect("map");
    let map = build_section_map(library.bytes()).expect("section map");

    // `0x1F` fills the fixture's `.text`, so this matches many times but never
    // decodes to a prologue.
    let set = PatternSet::new(&[("filler".to_string(), "1F 1F 1F 1F".to_string())])
        .expect("compile pattern");
    let outcome = scan_section(&map, library.bytes(), &set, &ScanControl::new()).expect("scan");
    assert!(!outcome.hits.is_empty(), "the filler must match");

    let verifier = PrologueVerifier::new().expect("capstone");
    let verified = verify_prologues(
        outcome,
        &map,
        library.bytes(),
        &verifier,
        &ScanControl::new(),
    )
    .expect("verify");

    assert!(verified.hits.is_empty(), "no filler match is a prologue");
    assert!(verified.discarded > 0, "the discards must be counted");
}

/// A corrupted archive fails closed with a typed error, not a panic.
#[test]
fn a_corrupted_apk_fails_closed() {
    let dir = TempDir::new("corrupt");
    let apk = dir.join("broken.apk");
    fs::write(&apk, b"this is not a zip archive at all").expect("write");

    let file = fs::File::open(&apk).expect("open");
    let error = extract_so_from_zip(file.as_raw_fd(), &dir.join("out")).expect_err("must fail");

    assert!(
        matches!(error, CoreError::InvalidZip(_)),
        "expected InvalidZip, got {error:?}"
    );
}

/// An archive without the library fails with `MissingSoFile`.
#[test]
fn an_apk_without_the_library_fails_closed() {
    use std::io::Write;

    let dir = TempDir::new("missing");
    let apk = dir.join("java-edition.apk");
    let file = fs::File::create(&apk).expect("create");
    let mut writer = zip::ZipWriter::new(file);
    writer
        .start_file("assets/data.bin", zip::write::SimpleFileOptions::default())
        .expect("start");
    writer.write_all(b"nothing useful").expect("write");
    writer.finish().expect("finish");

    let file = fs::File::open(&apk).expect("open");
    let error = extract_so_from_zip(file.as_raw_fd(), &dir.join("out")).expect_err("must fail");

    assert!(
        matches!(error, CoreError::MissingSoFile(_)),
        "expected MissingSoFile, got {error:?}"
    );
}

/// A 32-bit library is rejected before any table is read.
#[test]
fn a_32_bit_library_is_rejected() {
    let dir = TempDir::new("bits32");
    let mut image = build_library();
    image[EI_CLASS] = 1; // ELFCLASS32

    let path = dir.join("lib32.so");
    fs::write(&path, &image).expect("write");
    let library = MappedLibrary::open(&path, "fixture".to_string()).expect("map");

    let error = library.parse_elf().expect_err("must reject");
    assert!(
        matches!(error, CoreError::UnsupportedArchitecture(_)),
        "expected UnsupportedArchitecture, got {error:?}"
    );
}

/// A truncated library is rejected as an invalid ELF.
#[test]
fn a_truncated_library_is_rejected() {
    let dir = TempDir::new("truncated");
    let image = build_library();
    let path = dir.join("truncated.so");
    fs::write(&path, &image[..image.len() / 2]).expect("write");

    let library = MappedLibrary::open(&path, "fixture".to_string()).expect("map");
    // The header still parses, because it is intact. The section table it
    // points at is gone, so the map, which every later stage needs, fails.
    let error = build_section_map(library.bytes()).expect_err("must reject");
    assert!(
        matches!(error, CoreError::InvalidElf(_)),
        "expected InvalidElf, got {error:?}"
    );
}

/// A file smaller than an ELF header is refused before it is mapped.
#[test]
fn a_file_smaller_than_a_header_is_refused() {
    let dir = TempDir::new("tiny");
    let path = dir.join("tiny.so");
    fs::write(&path, b"\x7fELF").expect("write");

    let error = MappedLibrary::open(&path, "fixture".to_string()).expect_err("must refuse");
    assert!(
        matches!(error, CoreError::InvalidElf(_)),
        "expected InvalidElf, got {error:?}"
    );
}

/// Cancelling before a scan returns `Cancelled` rather than a partial result.
#[test]
fn cancelling_a_scan_returns_cancelled() {
    let dir = TempDir::new("cancel");
    let apk = dir.join("minecraft.apk");
    write_apk(&apk, &build_library());
    let library_path = extract(&apk, &dir.join("out"));

    let library = MappedLibrary::open(&library_path, "fixture".to_string()).expect("map");
    let map = build_section_map(library.bytes()).expect("section map");
    let set = PatternSet::new(&[(SIGNATURE_NAME.to_string(), SIGNATURE_SOURCE.to_string())])
        .expect("compile pattern");

    let control = ScanControl::new();
    control.cancel();
    let error = scan_section(&map, library.bytes(), &set, &control).expect_err("must cancel");

    assert!(
        matches!(error, CoreError::Cancelled),
        "expected Cancelled, got {error:?}"
    );
}

/// The scan reports progress that starts at 0 and ends at 100.
#[test]
fn the_scan_reports_progress_from_zero_to_one_hundred() {
    use std::sync::Mutex;

    struct Recorder {
        reports: Mutex<Vec<(u32, String)>>,
    }

    impl offset_core::scanner::ProgressSink for Recorder {
        fn report(&self, percent: u32, message: &str) {
            self.reports
                .lock()
                .expect("lock")
                .push((percent, message.to_string()));
        }
    }

    let dir = TempDir::new("progress");
    let apk = dir.join("minecraft.apk");
    write_apk(&apk, &build_library());
    let library_path = extract(&apk, &dir.join("out"));

    let library = MappedLibrary::open(&library_path, "fixture".to_string()).expect("map");
    let map = build_section_map(library.bytes()).expect("section map");
    let set = PatternSet::new(&[(SIGNATURE_NAME.to_string(), SIGNATURE_SOURCE.to_string())])
        .expect("compile pattern");

    let recorder = Recorder {
        reports: Mutex::new(Vec::new()),
    };

    // Matching stops short of 100 on purpose: the prologue check is the rest of
    // the budget, and it is what carries the total to 100.
    let outcome = offset_core::scanner::scan_section_with_progress(
        &map,
        library.bytes(),
        &set,
        &ScanControl::new(),
        Some(&recorder),
    )
    .expect("scan");
    {
        let reports = recorder.reports.lock().expect("lock");
        assert!(!reports.is_empty(), "progress must be reported");
        assert_eq!(reports.first().map(|entry| entry.0), Some(0));
        assert!(
            reports
                .last()
                .map(|entry| entry.0)
                .is_some_and(|percent| percent < 100),
            "matching must leave room for verification: {reports:?}"
        );
        assert!(
            reports.windows(2).all(|pair| pair[0].0 <= pair[1].0),
            "progress must never go backwards: {reports:?}"
        );
    }

    offset_core::scanner::verify_prologues_with_progress(
        outcome,
        &map,
        library.bytes(),
        &PrologueVerifier::new().expect("capstone"),
        &ScanControl::new(),
        Some(&recorder),
    )
    .expect("verify");

    let reports = recorder.reports.lock().expect("lock");
    assert_eq!(reports.last().map(|entry| entry.0), Some(100));
    assert!(
        reports.windows(2).all(|pair| pair[0].0 <= pair[1].0),
        "progress must never go backwards: {reports:?}"
    );
}
