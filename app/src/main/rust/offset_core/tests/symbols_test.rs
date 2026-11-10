//! Tests for the build identifier and the dynamic symbol table.

use offset_core::elf::MappedLibrary;
use offset_core::symbols::{build_symbol_report, BuildIdSource, JNI_SYMBOL_PREFIX};

/// Path of the library produced by scripts/build_rust.py.
fn staged_library() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../jniLibs/arm64-v8a/liboffset_core.so")
}

#[test]
fn real_engine_reports_a_consistent_build_identity() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let report = build_symbol_report(library.bytes()).expect("symbol report");

    // The engine is linked by the Android NDK with a build-id note.
    assert_eq!(report.identity.source, BuildIdSource::Note);
    assert!(!report.identity.build_id.is_empty());
    assert!(
        report.identity.build_id.len() >= 16,
        "build id looks truncated: {}",
        report.identity.build_id
    );
    assert!(
        report
            .identity
            .build_id
            .chars()
            .all(|character| character.is_ascii_hexdigit() && !character.is_ascii_uppercase()),
        "build id must be lower-case hex: {}",
        report.identity.build_id
    );
}

#[test]
fn real_engine_exports_the_jni_entry_points() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let report = build_symbol_report(library.bytes()).expect("symbol report");

    assert!(report.symbols.total > 0, "expected a dynamic symbol table");
    assert!(report.symbols.defined > 0, "expected defined symbols");
    assert!(!report.symbols.truncated);

    let names: Vec<&str> = report
        .symbols
        .jni
        .iter()
        .map(|symbol| symbol.name.as_str())
        .collect();
    assert!(
        names
            .contains(&"Java_com_chimerateam_offsetextractor_data_native_NativeLib_rustTestNative"),
        "expected rustTestNative in {names:?}"
    );
    assert!(names.iter().all(|name| name.starts_with(JNI_SYMBOL_PREFIX)));
    assert!(
        names.windows(2).all(|pair| pair[0] <= pair[1]),
        "symbols must be sorted for stable output"
    );
}

#[test]
fn jni_symbols_carry_a_plausible_address() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let report = build_symbol_report(library.bytes()).expect("symbol report");

    for symbol in &report.symbols.jni {
        assert!(symbol.address > 0, "{} has no address", symbol.name);
        assert_eq!(symbol.address_hex, format!("0x{:x}", symbol.address));
    }
}

#[test]
fn a_stripped_library_falls_back_to_a_text_hash() {
    // A minimal 64-bit AArch64 ELF header with a section table that contains
    // only a `.text` section: no note, no symbol table.
    let image = synthetic_elf_without_notes();

    let report = build_symbol_report(&image).expect("fallback report");

    assert_eq!(report.identity.source, BuildIdSource::TextHash);
    assert_eq!(
        report.identity.build_id.len(),
        64,
        "SHA-256 is 64 hex chars"
    );
    assert_eq!(report.symbols.total, 0);
    assert!(report.symbols.jni.is_empty());
}

/// Builds a small but structurally valid AArch64 ELF with only `.text`.
fn synthetic_elf_without_notes() -> Vec<u8> {
    // Layout: header (64) | .text (16) | .shstrtab (16) | section headers (3*64)
    const HEADER: usize = 64;
    const TEXT: usize = 16;
    const SHSTRTAB: usize = 16;
    const SHOFF: usize = HEADER + TEXT + SHSTRTAB;
    const SHNUM: u16 = 3;

    let mut image = vec![0u8; SHOFF + SHNUM as usize * 64];

    // e_ident
    image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
    image[4] = 2; // ELFCLASS64
    image[5] = 1; // little endian
    image[6] = 1; // version

    // e_type = ET_DYN, e_machine = EM_AARCH64
    image[16..18].copy_from_slice(&3u16.to_le_bytes());
    image[18..20].copy_from_slice(&183u16.to_le_bytes());
    // e_entry
    image[24..32].copy_from_slice(&0x1000u64.to_le_bytes());
    // e_shoff
    image[40..48].copy_from_slice(&(SHOFF as u64).to_le_bytes());
    // e_ehsize, e_shentsize, e_shnum, e_shstrndx
    image[52..54].copy_from_slice(&64u16.to_le_bytes());
    image[58..60].copy_from_slice(&64u16.to_le_bytes());
    image[60..62].copy_from_slice(&SHNUM.to_le_bytes());
    image[62..64].copy_from_slice(&2u16.to_le_bytes());

    // .text body: 16 bytes of recognisable code.
    image[HEADER..HEADER + 4].copy_from_slice(&[0xfd, 0x7b, 0xbf, 0xa9]);

    // .shstrtab contents: "\0.text\0.shstrtab\0"
    let names: &[u8] = b"\0.text\0.shstrtab\0";
    image[HEADER + TEXT..HEADER + TEXT + names.len()].copy_from_slice(names);

    // Section header 0: the null header.
    // Section header 1: .text
    let text_header = SHOFF + 64;
    write_u32(&mut image, text_header, 1); // sh_name -> ".text"
    write_u32(&mut image, text_header + 4, 1); // SHT_PROGBITS
    write_u64(&mut image, text_header + 8, 0x6); // SHF_ALLOC | SHF_EXECINSTR
    write_u64(&mut image, text_header + 16, 0x1000); // sh_addr
    write_u64(&mut image, text_header + 24, HEADER as u64); // sh_offset
    write_u64(&mut image, text_header + 32, TEXT as u64); // sh_size
    write_u64(&mut image, text_header + 48, 16); // sh_addralign

    // Section header 2: .shstrtab
    let strtab_header = SHOFF + 128;
    write_u32(&mut image, strtab_header, 7); // sh_name -> ".shstrtab"
    write_u32(&mut image, strtab_header + 4, 3); // SHT_STRTAB
    write_u64(&mut image, strtab_header + 24, (HEADER + TEXT) as u64);
    write_u64(&mut image, strtab_header + 32, names.len() as u64);

    image
}

/// Writes a little-endian `u32` at `offset`.
fn write_u32(image: &mut [u8], offset: usize, value: u32) {
    image[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

/// Writes a little-endian `u64` at `offset`.
fn write_u64(image: &mut [u8], offset: usize, value: u64) {
    image[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
