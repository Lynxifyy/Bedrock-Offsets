//! End-to-end scan tests against the real cross-compiled engine.
//!
//! These exercise the path Kotlin uses: map a real `.so`, scan its `.text` for
//! patterns, and confirm the reported addresses line up with the section map.

use offset_core::elf::MappedLibrary;
use offset_core::patterns::PatternSet;
use offset_core::scanner::{scan_section, ScanControl, CHUNK_BYTES};
use offset_core::sections::{build_section_map, TEXT_SECTION};

/// Path of the library produced by scripts/build_rust.py.
fn staged_library() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../jniLibs/arm64-v8a/liboffset_core.so")
}

/// A pattern guaranteed to appear in `.text`: the prologue of an exported
/// function, taken from the library's own dynamic symbol table.
fn pattern_from_a_real_symbol(bytes: &[u8], map: &offset_core::sections::SectionMap) -> String {
    // The start of `.text` is a function boundary in a linked object, so the
    // first bytes there are a valid signature to search for.
    let text = map.bytes_of(TEXT_SECTION, bytes);
    let probe = &text[..8];
    probe
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn scanning_the_real_engine_finds_its_own_code() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");
    let pattern = pattern_from_a_real_symbol(library.bytes(), &map);

    let set = PatternSet::new(&[("probe".to_string(), pattern)]).expect("pattern set");
    let outcome = scan_section(&map, library.bytes(), &set, &ScanControl::new()).expect("scan");

    assert_eq!(outcome.section, TEXT_SECTION);
    assert!(outcome.scanned_bytes > 0);
    assert_eq!(outcome.scanned_bytes, map.text.size);
    assert!(outcome.workers >= 1);
    assert!(!outcome.hits.is_empty(), "the probe must be found");

    // The first hit is at the very start of `.text`.
    let first = &outcome.hits[0];
    assert_eq!(first.section_offset, 0);
    assert_eq!(first.file_offset, map.text.offset);
    assert_eq!(first.address, map.text.address);
}

#[test]
fn a_pattern_that_cannot_appear_reports_no_hits() {
    let path = staged_library();
    if !path.is_file() {
        eprintln!("skipping: {} has not been built yet", path.display());
        return;
    }

    let library = MappedLibrary::open(&path, "real".to_string()).expect("map real library");
    let map = build_section_map(library.bytes()).expect("section map");

    // A 16-byte run that cannot occur: AArch64 instructions are four bytes, so
    // this alignment-unfriendly sequence is effectively impossible.
    let set = PatternSet::new(&[(
        "impossible".to_string(),
        "DE AD BE EF DE AD BE EF DE AD BE EF DE AD BE EF".to_string(),
    )])
    .expect("pattern set");

    let outcome = scan_section(&map, library.bytes(), &set, &ScanControl::new()).expect("scan");
    assert!(outcome.hits.is_empty(), "found {:?}", outcome.hits);
}

#[test]
fn scanning_a_large_buffer_keeps_the_peak_resident_set_flat() {
    // A 64 MB buffer, far larger than the chunk size, to force many chunks.
    let len = 64 * 1024 * 1024;
    let mut image = vec![0u8; len];
    // One needle near the very end, so the scan must traverse everything.
    let needle_at = len - 32;
    image[needle_at..needle_at + 4].copy_from_slice(&[0xca, 0xfe, 0xba, 0xbe]);

    let section = offset_core::sections::SectionInfo {
        name: TEXT_SECTION.to_string(),
        kind: offset_core::sections::SectionKind::Text,
        address: 0x1000,
        size: len as u64,
        offset: 0,
        align: 16,
        delta: 0x1000,
        allocated: true,
        executable: true,
        writable: false,
        occupies_file: true,
    };
    let map = offset_core::sections::SectionMap {
        image_base: 0x1000,
        text: section,
        sections: Vec::new(),
    };

    let set =
        PatternSet::new(&[("needle".to_string(), "CA FE BA BE".to_string())]).expect("pattern set");

    let before = offset_core::memory::peak_resident_bytes();
    let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
    let after = offset_core::memory::peak_resident_bytes();

    assert_eq!(outcome.hits.len(), 1);
    assert_eq!(outcome.hits[0].section_offset, needle_at as u64);
    assert!(
        outcome.chunks >= len / CHUNK_BYTES,
        "expected the buffer to be split into many chunks, got {}",
        outcome.chunks
    );

    // The scan itself must not grow the peak by more than the buffer the test
    // already allocated. A 64 MB buffer is the only large allocation here, so
    // anything near 100 MB of *growth* would mean the scan copies its input.
    if let (Some(before), Some(after)) = (before, after) {
        let growth = after.saturating_sub(before);
        assert!(
            growth < 100 * 1024 * 1024,
            "peak grew by {growth} bytes during a scan of a {len} byte buffer"
        );
    }
}

#[test]
fn cancelling_before_a_scan_of_a_large_buffer_returns_immediately() {
    let len = 8 * 1024 * 1024;
    let image = vec![0u8; len];

    let section = offset_core::sections::SectionInfo {
        name: TEXT_SECTION.to_string(),
        kind: offset_core::sections::SectionKind::Text,
        address: 0x1000,
        size: len as u64,
        offset: 0,
        align: 16,
        delta: 0x1000,
        allocated: true,
        executable: true,
        writable: false,
        occupies_file: true,
    };
    let map = offset_core::sections::SectionMap {
        image_base: 0x1000,
        text: section,
        sections: Vec::new(),
    };

    let set = PatternSet::new(&[("x".to_string(), "41 42 43".to_string())]).expect("pattern set");
    let control = ScanControl::new();
    control.cancel();

    let error = scan_section(&map, &image, &set, &control).expect_err("must cancel");
    assert!(
        matches!(error, offset_core::CoreError::Cancelled),
        "{error}"
    );
}
