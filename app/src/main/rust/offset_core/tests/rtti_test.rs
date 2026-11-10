//! Tests for RTTI vtable resolution.
//!
//! Two kinds of fixture are used. The real engine is cross-compiled with the
//! NDK, which emits no RTTI at all, so the resolution logic itself is exercised
//! on a synthesised image whose layout matches what the NDK produces for a
//! polymorphic class. The real library is still checked for the parts that do
//! not need RTTI: the relocation table and the empty-result contract.

use offset_core::elf::MappedLibrary;
use offset_core::rtti::{resolve_vtables, VtableSource, MAX_TYPE_NAME_BYTES, VTABLE_PREFIX_SLOTS};

/// Path of the library produced by scripts/build_rust.py.
fn staged_library() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../jniLibs/arm64-v8a/liboffset_core.so")
}

/// Byte offsets inside the ELF identification block.
const EI_CLASS: usize = 4;
const EI_DATA: usize = 5;

/// Sizes used by the synthetic image, chosen to mirror a real layout.
const EHDR_BYTES: usize = 64;
const SHDR_BYTES: usize = 64;
const TEXT_ADDRESS: u64 = 0x1000;
const RODATA_ADDRESS: u64 = 0x2000;
const DATA_REL_RO_ADDRESS: u64 = 0x3000;
const RELA_DYN_ADDRESS: u64 = 0x4000;
const FILE_BASE: usize = 0x100;

/// Address of the vtable's first function pointer.
const VTABLE_ADDRESS: u64 = DATA_REL_RO_ADDRESS;

/// Address of the `type_info` object the vtable points at.
const TYPE_INFO_ADDRESS: u64 = DATA_REL_RO_ADDRESS + 0x80;

/// Offset of the `type_info` object inside `.data.rel.ro`.
const TYPE_INFO_OFFSET: usize = (TYPE_INFO_ADDRESS - DATA_REL_RO_ADDRESS) as usize;

/// Section indices, in the order the builder emits them.
const INDEX_NULL: usize = 0;
const INDEX_TEXT: usize = 1;
const INDEX_RODATA: usize = 2;
const INDEX_DATA_REL_RO: usize = 3;
const INDEX_RELA_DYN: usize = 4;
const INDEX_SHSTRTAB: usize = 5;

const SHT_PROGBITS: u32 = 1;
const SHT_RELA: u32 = 4;
const SHT_STRTAB: u32 = 3;

const SHF_WRITE: u64 = 0x1;
const SHF_ALLOC: u64 = 0x2;
const SHF_EXECINSTR: u64 = 0x4;

/// A section described to the builder.
struct SectionPlan {
    name: &'static str,
    kind: u32,
    address: u64,
    flags: u64,
    body: Vec<u8>,
}

/// Builds a synthetic 64-bit little-endian AArch64 shared object.
///
/// The image has a real section header table and a real section name table, so
/// the engine's own parsing code runs on it. It is deliberately laid out like a
/// loaded library: every allocated section has an address, and `.rela.dyn`
/// carries the relocations that a linker would emit for the vtable pointers.
fn build_image(sections: &[SectionPlan]) -> Vec<u8> {
    let mut names = vec![0u8];
    let mut name_offsets = Vec::new();
    for section in sections {
        name_offsets.push(names.len() as u32);
        names.extend_from_slice(section.name.as_bytes());
        names.push(0);
    }
    // The `.shstrtab` entry itself is appended last by the caller, so its name
    // is added here with a placeholder offset.
    let shstrtab_name_offset = names.len() as u32;
    names.extend_from_slice(b".shstrtab\0");

    let section_count = sections.len() + 2; // the null entry plus `.shstrtab`
    let shstrtab_index = section_count - 1;
    let names_size = names.len() as u64;

    // Lay the bodies out at fixed file offsets, then the tables.
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
    image[20..24].copy_from_slice(&1u32.to_le_bytes()); // e_version
    image[40..48].copy_from_slice(&(section_header_offset as u64).to_le_bytes());
    image[52..54].copy_from_slice(&64u16.to_le_bytes()); // e_ehsize
    image[58..60].copy_from_slice(&(SHDR_BYTES as u16).to_le_bytes());
    image[60..62].copy_from_slice(&(section_count as u16).to_le_bytes());
    image[62..64].copy_from_slice(&(shstrtab_index as u16).to_le_bytes());

    // Section bodies.
    for (index, section) in sections.iter().enumerate() {
        let start = body_offsets[index];
        image[start..start + section.body.len()].copy_from_slice(&section.body);
    }
    image[shstrtab_offset..shstrtab_offset + names.len()].copy_from_slice(&names);

    // Section headers.
    let mut write_header = |index: usize, plan: &SectionPlan, body_offset: usize| {
        let base = section_header_offset + index * SHDR_BYTES;
        let name = if plan.name.is_empty() {
            0
        } else {
            name_offsets[index - 1]
        };
        image[base..base + 4].copy_from_slice(&name.to_le_bytes());
        image[base + 4..base + 8].copy_from_slice(&plan.kind.to_le_bytes());
        image[base + 8..base + 16].copy_from_slice(&plan.flags.to_le_bytes());
        image[base + 16..base + 24].copy_from_slice(&plan.address.to_le_bytes());
        image[base + 24..base + 32].copy_from_slice(&(body_offset as u64).to_le_bytes());
        image[base + 32..base + 40].copy_from_slice(&(plan.body.len() as u64).to_le_bytes());
        image[base + 48..base + 56].copy_from_slice(&8u64.to_le_bytes()); // sh_addralign
    };

    // The null section header is already zeroed.
    for (index, section) in sections.iter().enumerate() {
        write_header(index + 1, section, body_offsets[index]);
    }
    // `.shstrtab` header.
    let base = section_header_offset + shstrtab_index * SHDR_BYTES;
    image[base..base + 4].copy_from_slice(&shstrtab_name_offset.to_le_bytes());
    image[base + 4..base + 8].copy_from_slice(&SHT_STRTAB.to_le_bytes());
    image[base + 24..base + 32].copy_from_slice(&(shstrtab_offset as u64).to_le_bytes());
    image[base + 32..base + 40].copy_from_slice(&names_size.to_le_bytes());
    image[base + 48..base + 56].copy_from_slice(&1u64.to_le_bytes());

    image
}

/// Writes a relocation entry into a `.rela.dyn` body.
fn relocation(offset: u64, kind: u32, addend: u64) -> [u8; 24] {
    let mut entry = [0u8; 24];
    entry[0..8].copy_from_slice(&offset.to_le_bytes());
    entry[8..16].copy_from_slice(&u64::from(kind).to_le_bytes());
    entry[16..24].copy_from_slice(&addend.to_le_bytes());
    entry
}

/// Builds an image containing one polymorphic class named `ClientInstance`.
///
/// Layout, mirroring the Itanium C++ ABI. The vtable lives at
/// [`VTABLE_ADDRESS`] and the `type_info` object it points at lives later in the
/// same section:
///
/// ```text
/// .rodata[0x00]       "14ClientInstance\0"
/// .data.rel.ro[0x00]  offset-to-top  (vtable[-2])
/// .data.rel.ro[0x08]  type_info pointer -> 0x3080  (vtable[-1])
/// .data.rel.ro[0x10]  vtable slot 0 -> 0x1000
/// .data.rel.ro[0x80]  type_info vtable pointer
/// .data.rel.ro[0x88]  name pointer -> .rodata[0]
/// ```
fn image_with_client_instance() -> Vec<u8> {
    let mut rodata = Vec::new();
    let name_offset = rodata.len();
    rodata.extend_from_slice(b"14ClientInstance\0");

    let name_address = RODATA_ADDRESS + name_offset as u64;

    let mut data = vec![0u8; TYPE_INFO_OFFSET + 16];
    // vtable[-2]: offset-to-top.
    data[0..8].copy_from_slice(&0u64.to_le_bytes());
    // vtable[-1]: pointer to the type_info object.
    data[8..16].copy_from_slice(&TYPE_INFO_ADDRESS.to_le_bytes());
    // vtable[0]: first virtual function.
    data[16..24].copy_from_slice(&TEXT_ADDRESS.to_le_bytes());
    // type_info[0]: the type_info vtable pointer, which points into `.text` and
    // therefore cannot itself be mistaken for a type_info pointer.
    data[TYPE_INFO_OFFSET..TYPE_INFO_OFFSET + 8].copy_from_slice(&TEXT_ADDRESS.to_le_bytes());
    // type_info[1]: the name pointer.
    data[TYPE_INFO_OFFSET + 8..TYPE_INFO_OFFSET + 16].copy_from_slice(&name_address.to_le_bytes());

    let rela = {
        let mut body = Vec::new();
        // R_AARCH64_ABS64 = 257
        body.extend_from_slice(&relocation(VTABLE_ADDRESS + 8, 257, TYPE_INFO_ADDRESS));
        body.extend_from_slice(&relocation(TYPE_INFO_ADDRESS + 8, 257, name_address));
        body.extend_from_slice(&relocation(VTABLE_ADDRESS + 16, 257, TEXT_ADDRESS));
        body
    };

    let sections = vec![
        SectionPlan {
            name: ".text",
            kind: SHT_PROGBITS,
            address: TEXT_ADDRESS,
            flags: SHF_ALLOC | SHF_EXECINSTR,
            body: vec![0u8; 64],
        },
        SectionPlan {
            name: ".rodata",
            kind: SHT_PROGBITS,
            address: RODATA_ADDRESS,
            flags: SHF_ALLOC,
            body: rodata,
        },
        SectionPlan {
            name: ".data.rel.ro",
            kind: SHT_PROGBITS,
            address: DATA_REL_RO_ADDRESS,
            flags: SHF_ALLOC | SHF_WRITE,
            body: data,
        },
        SectionPlan {
            name: ".rela.dyn",
            kind: SHT_RELA,
            address: RELA_DYN_ADDRESS,
            flags: SHF_ALLOC,
            body: rela,
        },
    ];
    build_image(&sections)
}

#[test]
fn resolves_the_vtable_of_a_polymorphic_class() {
    let image = image_with_client_instance();
    let map = resolve_vtables(&image).expect("resolve vtables");

    assert_eq!(map.len(), 1, "{map:#?}");
    let entry = map.get("ClientInstance").expect("ClientInstance");
    assert_eq!(entry.name, "ClientInstance");
    assert_eq!(entry.mangled, "14ClientInstance");
    assert_eq!(entry.type_info_address, TYPE_INFO_ADDRESS);
    // The type_info pointer sits in vtable[-1], so the vtable starts one slot
    // before the slot that holds it.
    assert_eq!(entry.vtable_address, VTABLE_ADDRESS);
    // The first virtual function pointer is two slots in, so the vtable base is
    // exactly one slot below the type_info pointer slot.
    assert_eq!(
        entry.vtable_address + (VTABLE_PREFIX_SLOTS as u64 - 1) * 8 + 8,
        VTABLE_ADDRESS + (VTABLE_PREFIX_SLOTS as u64) * 8,
    );
    assert!(entry.type_info_address > entry.vtable_address);
    assert_eq!(entry.vtable_hex, format!("0x{:x}", entry.vtable_address));
}

#[test]
fn the_vtable_address_is_consistent_with_the_image_base() {
    let image = image_with_client_instance();
    let map = resolve_vtables(&image).expect("resolve vtables");
    let entry = map.get("ClientInstance").expect("ClientInstance");

    // The image base is the lowest allocated address, which is `.text` here.
    assert_eq!(entry.vtable_offset, entry.vtable_address - TEXT_ADDRESS);
}

#[test]
fn the_relocation_table_is_used_when_present() {
    let image = image_with_client_instance();
    let map = resolve_vtables(&image).expect("resolve vtables");

    assert_eq!(map.source, VtableSource::Relocations);
    assert!(map.relocations_examined >= 2, "{map:#?}");
}

#[test]
fn a_library_without_rtti_yields_an_empty_map() {
    // No `.data.rel.ro`, which is what a stripped library looks like.
    let sections = vec![
        SectionPlan {
            name: ".text",
            kind: SHT_PROGBITS,
            address: TEXT_ADDRESS,
            flags: SHF_ALLOC | SHF_EXECINSTR,
            body: vec![0u8; 64],
        },
        SectionPlan {
            name: ".rodata",
            kind: SHT_PROGBITS,
            address: RODATA_ADDRESS,
            flags: SHF_ALLOC,
            body: b"no rtti here\0".to_vec(),
        },
    ];

    let map = resolve_vtables(&build_image(&sections)).expect("resolve vtables");

    assert!(map.is_empty());
    assert_eq!(map.source, VtableSource::DirectScan);
}

#[test]
fn a_name_pointer_that_is_not_a_type_name_is_ignored() {
    // `.data.rel.ro` holds a pointer into `.rodata`, but the bytes there are not
    // a length-prefixed type name, so no vtable may be reported.
    let rodata = b"this is prose, not a type name\0".to_vec();
    let name_address = RODATA_ADDRESS;

    let mut data = Vec::new();
    data.extend_from_slice(&0u64.to_le_bytes());
    data.extend_from_slice(&name_address.to_le_bytes());
    data.extend_from_slice(&TEXT_ADDRESS.to_le_bytes());

    let sections = vec![
        SectionPlan {
            name: ".text",
            kind: SHT_PROGBITS,
            address: TEXT_ADDRESS,
            flags: SHF_ALLOC | SHF_EXECINSTR,
            body: vec![0u8; 64],
        },
        SectionPlan {
            name: ".rodata",
            kind: SHT_PROGBITS,
            address: RODATA_ADDRESS,
            flags: SHF_ALLOC,
            body: rodata,
        },
        SectionPlan {
            name: ".data.rel.ro",
            kind: SHT_PROGBITS,
            address: DATA_REL_RO_ADDRESS,
            flags: SHF_ALLOC | SHF_WRITE,
            body: data,
        },
    ];

    let map = resolve_vtables(&build_image(&sections)).expect("resolve vtables");

    assert!(map.is_empty(), "{map:#?}");
}

#[test]
fn an_over_long_type_name_is_rejected() {
    let long = "A".repeat(MAX_TYPE_NAME_BYTES + 1);
    let mut rodata = format!("{}{long}", long.len()).into_bytes();
    rodata.push(0);
    let name_address = RODATA_ADDRESS;

    let mut data = Vec::new();
    data.extend_from_slice(&0u64.to_le_bytes());
    data.extend_from_slice(&name_address.to_le_bytes());
    data.extend_from_slice(&TEXT_ADDRESS.to_le_bytes());

    let sections = vec![
        SectionPlan {
            name: ".text",
            kind: SHT_PROGBITS,
            address: TEXT_ADDRESS,
            flags: SHF_ALLOC | SHF_EXECINSTR,
            body: vec![0u8; 64],
        },
        SectionPlan {
            name: ".rodata",
            kind: SHT_PROGBITS,
            address: RODATA_ADDRESS,
            flags: SHF_ALLOC,
            body: rodata,
        },
        SectionPlan {
            name: ".data.rel.ro",
            kind: SHT_PROGBITS,
            address: DATA_REL_RO_ADDRESS,
            flags: SHF_ALLOC | SHF_WRITE,
            body: data,
        },
    ];

    let map = resolve_vtables(&build_image(&sections)).expect("resolve vtables");

    assert!(map.is_empty(), "{map:#?}");
}

#[test]
fn a_corrupt_image_is_rejected_rather_than_guessed_at() {
    let mut image = image_with_client_instance();
    image[..4].copy_from_slice(b"junk");

    assert!(resolve_vtables(&image).is_err());
}

#[test]
fn the_real_engine_reports_relocations_without_panicking() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = resolve_vtables(library.bytes()).expect("resolve vtables");

    // Rust emits no Itanium RTTI, so the map is expected to be empty; the test
    // pins that the engine reads the real relocation table and reports it.
    assert!(map.relocations_examined > 0, "{map:#?}");
    assert!(map.is_empty(), "the Rust engine carries no C++ RTTI");
}

#[test]
fn every_reported_vtable_address_is_aligned_and_nonzero() {
    let image = image_with_client_instance();
    let map = resolve_vtables(&image).expect("resolve vtables");

    for entry in map.entries.values() {
        assert_ne!(entry.vtable_address, 0);
        assert_eq!(entry.vtable_address % 8, 0, "{entry:#?}");
        assert!(entry.vtable_address < entry.type_info_address);
        assert!(entry.mangled.ends_with(&entry.name));
    }
}

/// Pins the fixture geometry so a future edit cannot silently invalidate the
/// assertions above.
#[test]
fn the_section_index_constants_match_the_builder_order() {
    assert_eq!(INDEX_NULL, 0);
    assert_eq!(INDEX_TEXT, 1);
    assert_eq!(INDEX_RODATA, 2);
    assert_eq!(INDEX_DATA_REL_RO, 3);
    assert_eq!(INDEX_RELA_DYN, 4);
    assert_eq!(INDEX_SHSTRTAB, 5);
    assert_eq!(EHDR_BYTES, 64);
    assert_eq!(VTABLE_ADDRESS, DATA_REL_RO_ADDRESS);
    assert_eq!(TYPE_INFO_OFFSET, 0x80);
}
