//! Tests for the section map, including the real cross-compiled engine.

use offset_core::elf::MappedLibrary;
use offset_core::sections::{
    build_section_map, SectionKind, DATA_REL_RO_SECTION, RODATA_SECTION, TEXT_SECTION,
};

/// Path of the library produced by scripts/build_rust.py.
fn staged_library() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../jniLibs/arm64-v8a/liboffset_core.so")
}

#[test]
fn maps_every_section_of_the_real_engine() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");

    assert_eq!(map.text.name, TEXT_SECTION);
    assert_eq!(map.text.kind, SectionKind::Text);
    assert!(map.text.executable, ".text must be executable");
    assert!(map.text.address > 0, ".text must have a load address");
    assert!(map.text.size > 0, ".text must not be empty");
    assert!(map.text.occupies_file, ".text must occupy file space");

    // The load delta is what turns a scan hit into an address, so it has to be
    // consistent with the addresses the header reports.
    assert_eq!(map.text.address, map.text.offset + map.text.delta);
    // The image base is the lowest allocated address, which is at or below the
    // start of `.text` because earlier sections such as `.interp` come first.
    assert!(map.image_base <= map.text.address);

    assert!(
        map.find(RODATA_SECTION).is_some(),
        "expected {RODATA_SECTION} in the real engine"
    );
    assert!(
        map.find(".dynsym").is_some(),
        "expected .dynsym in the real engine"
    );

    assert!(map.sections.len() > 10, "suspiciously few sections");
}

#[test]
fn section_bytes_are_a_slice_of_the_mapping() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");

    let text = map.bytes_of(TEXT_SECTION, library.bytes());
    assert_eq!(text.len() as u64, map.text.size);
    assert!(!text.is_empty());

    // An absent section yields an empty slice rather than a panic.
    assert!(map.bytes_of(".does.not.exist", library.bytes()).is_empty());
}

#[test]
fn address_round_trip_holds_inside_the_real_text_section() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");

    let probe = map.text.offset + map.text.size / 2;
    let address = map.text.address_of(probe).expect("probe has an address");
    assert_eq!(map.text.offset_of(address), Some(probe));
}

#[test]
fn data_rel_ro_is_classified_when_present() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");

    // A Rust cdylib may or may not emit `.data.rel.ro`; when it does, it must be
    // classified as relocatable read-only data rather than "other".
    if let Some(section) = map.find(DATA_REL_RO_SECTION) {
        assert_eq!(section.kind, SectionKind::DataRelRo);
        assert!(section.allocated);
    }
}
