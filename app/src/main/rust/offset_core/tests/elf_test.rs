//! Tests for zero-copy ELF mapping and header validation.
//!
//! The fixtures are synthesised here rather than checked in: only the first 64
//! bytes are ever inspected by this phase, so a hand-built header exercises the
//! same code path as a real library while keeping the repository small.

use std::fs;

use offset_core::elf::{
    describe_library, parse_elf_bytes, validate_magic, MappedLibrary, ELF_MAGIC,
};
use offset_core::error::CoreError;

/// Byte offsets inside the ELF identification block.
const EI_CLASS: usize = 4;
const EI_DATA: usize = 5;

/// Builds a minimal 64-bit little-endian AArch64 ELF header.
fn aarch64_header(entry: u64) -> Vec<u8> {
    let mut bytes = vec![0u8; 64];
    bytes[..4].copy_from_slice(&ELF_MAGIC);
    bytes[EI_CLASS] = 2; // ELFCLASS64
    bytes[EI_DATA] = 1; // little endian
    bytes[6] = 1; // EV_CURRENT
                  // e_type = ET_DYN
    bytes[16..18].copy_from_slice(&3u16.to_le_bytes());
    bytes[18..20].copy_from_slice(&183u16.to_le_bytes()); // e_machine = EM_AARCH64
    bytes[24..32].copy_from_slice(&entry.to_le_bytes()); // e_entry
    bytes
}

fn temp_file(label: &str, contents: &[u8]) -> std::path::PathBuf {
    let mut path = std::env::temp_dir();
    path.push(format!(
        "offset_core_elf_{label}_{}_{}.so",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::write(&path, contents).expect("write fixture");
    path
}

#[test]
fn maps_a_real_file_without_reading_it_into_a_buffer() {
    let path = temp_file("map", &aarch64_header(0x1234));
    let library = MappedLibrary::open(&path, "deadbeef".to_string()).expect("map file");

    assert_eq!(library.bytes().len(), 64);
    assert_eq!(library.sha256(), "deadbeef");
    assert_eq!(library.size(), 64);
    assert_eq!(library.path(), path.as_path());
}

#[test]
fn reports_aarch64_metadata_for_a_valid_header() {
    let path = temp_file("meta", &aarch64_header(0x4000));
    let library = MappedLibrary::open(&path, "abc".to_string()).expect("map file");
    let metadata = library.metadata().expect("metadata");

    assert_eq!(metadata.architecture, "AArch64");
    assert_eq!(metadata.bits, 64);
    assert_eq!(metadata.endianness, "little");
    assert_eq!(metadata.elf_type, "DYN");
    assert_eq!(metadata.entry_point, "0x4000");
    assert_eq!(metadata.entry_point_value, 0x4000);
    assert_eq!(metadata.sha256, "abc");
}

#[test]
fn rejects_a_32_bit_elf_with_a_specific_error() {
    let mut bytes = aarch64_header(0);
    bytes[EI_CLASS] = 1; // ELFCLASS32
    let error = parse_elf_bytes(&bytes).expect_err("32-bit must be rejected");

    assert!(
        matches!(error, CoreError::UnsupportedArchitecture(_)),
        "unexpected error: {error}"
    );
    assert!(error.to_string().contains("32-bit"), "{error}");
}

#[test]
fn rejects_a_non_aarch64_elf() {
    let mut bytes = aarch64_header(0);
    bytes[18..20].copy_from_slice(&3u16.to_le_bytes()); // EM_386
    let error = parse_elf_bytes(&bytes).expect_err("x86 must be rejected");

    assert!(matches!(error, CoreError::UnsupportedArchitecture(_)));
    assert!(error.to_string().contains("x86"), "{error}");
}

#[test]
fn rejects_a_corrupted_binary_before_parsing() {
    let mut bytes = aarch64_header(0);
    bytes[..4].copy_from_slice(b"PK\x03\x04");
    let error = parse_elf_bytes(&bytes).expect_err("corrupted file must be rejected");

    assert!(matches!(error, CoreError::InvalidElf(_)));
    assert!(error.to_string().contains("expected ELF magic"), "{error}");
}

#[test]
fn rejects_a_file_smaller_than_an_elf_header() {
    let path = temp_file("tiny", b"\x7fELF");
    let error = MappedLibrary::open(&path, String::new()).expect_err("too small to map");

    assert!(matches!(error, CoreError::InvalidElf(_)));
    assert!(
        error.to_string().contains("smaller than an ELF header"),
        "{error}"
    );
}

#[test]
fn rejects_a_missing_file_without_panicking() {
    let error = MappedLibrary::open(
        std::path::Path::new("/nonexistent/does-not-exist.so"),
        String::new(),
    )
    .expect_err("missing file must fail");

    assert!(
        matches!(error, CoreError::Io(_)),
        "unexpected error: {error}"
    );
}

#[test]
fn magic_validation_accepts_a_real_prefix() {
    assert!(validate_magic(&aarch64_header(0)).is_ok());
}

#[test]
fn maps_the_cross_compiled_engine_when_it_is_present() {
    // The staged library is produced by scripts/build_rust.py and is not
    // committed, so this test skips cleanly on a fresh checkout while still
    // verifying real-device mapping whenever the artefact exists.
    let candidate = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../jniLibs/arm64-v8a/liboffset_core.so");
    if !candidate.is_file() {
        eprintln!("skipping: {} has not been built yet", candidate.display());
        return;
    }

    let library = MappedLibrary::open(&candidate, "real".to_string()).expect("map real library");
    let metadata = library.metadata().expect("metadata for real library");

    assert_eq!(metadata.architecture, "AArch64");
    assert_eq!(metadata.bits, 64);
    assert_eq!(metadata.elf_type, "DYN");
    assert!(metadata.size > 100_000, "unexpected size {}", metadata.size);
    assert!(
        metadata.entry_point.starts_with("0x"),
        "{}",
        metadata.entry_point
    );
}

#[test]
fn metadata_helper_matches_the_mapped_result() {
    let bytes = aarch64_header(0x2000);
    let metadata = describe_library(&bytes, "/cache/libminecraftpe.so", "hash", 64)
        .expect("metadata from bytes");

    assert_eq!(metadata.path, "/cache/libminecraftpe.so");
    assert_eq!(metadata.entry_point, "0x2000");
    assert_eq!(metadata.format, "ELF");
    assert_eq!(metadata.size, 64);
}
