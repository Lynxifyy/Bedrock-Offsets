//! Integration tests for the streaming ZIP extractor.
//!
//! A synthetic APK-shaped archive is built on disk, opened as a file
//! descriptor, and extracted through the same integer-fd entry point the JNI
//! layer will use.

use std::fs;
use std::io::{Read, Write};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};

use offset_core::error::CoreError;
use offset_core::zip_extract::{extract_named_so_from_zip, extract_so_from_zip, BEDROCK_SO_PATH};
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;

/// Builds a temporary directory unique to `label`.
fn temp_dir(label: &str) -> PathBuf {
    let mut dir = std::env::temp_dir();
    dir.push(format!(
        "offset_core_test_{label}_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

/// Writes a zip archive containing `entries` at `path`.
fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
    let file = fs::File::create(path).expect("create zip");
    let mut writer = zip::ZipWriter::new(file);
    let options =
        SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in entries {
        writer.start_file(*name, options).expect("start file");
        writer.write_all(bytes).expect("write entry");
    }
    writer.finish().expect("finish zip");
}

#[test]
fn extracts_the_named_entry_and_reports_matching_hash() {
    let dir = temp_dir("hash");
    let archive_path = dir.join("app.apk");
    let payload: Vec<u8> = (0..200_000u32).map(|value| (value % 251) as u8).collect();
    write_zip(
        &archive_path,
        &[
            ("AndroidManifest.xml", b"<manifest/>"),
            (BEDROCK_SO_PATH, &payload),
        ],
    );

    let archive = fs::File::open(&archive_path).expect("open archive");
    let out_dir = dir.join("out");
    let result = extract_so_from_zip(archive.as_raw_fd(), &out_dir).expect("extract");

    let expected_hash = {
        let mut hasher = Sha256::new();
        hasher.update(&payload);
        let digest = hasher.finalize();
        digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    };

    assert_eq!(result.sha256, expected_hash);
    assert_eq!(result.size, payload.len() as u64);
    assert_eq!(fs::read(&result.path).expect("read extracted"), payload);
}

#[test]
fn reports_missing_so_file_with_a_specific_error() {
    let dir = temp_dir("missing");
    let archive_path = dir.join("java_edition.apk");
    write_zip(&archive_path, &[("classes.dex", b"not-a-native-lib")]);

    let archive = fs::File::open(&archive_path).expect("open archive");
    let error = extract_so_from_zip(archive.as_raw_fd(), &dir.join("out"))
        .expect_err("must fail without the native library");

    assert!(matches!(error, CoreError::MissingSoFile(_)), "got {error:?}");
}

#[test]
fn rejects_a_negative_descriptor_without_panicking() {
    let dir = temp_dir("negfd");
    let error = extract_so_from_zip(-1, &dir).expect_err("negative fd must fail");
    assert!(
        matches!(error, CoreError::InvalidDescriptor(_)),
        "got {error:?}"
    );
}

#[test]
fn rejects_a_non_zip_payload() {
    let dir = temp_dir("garbage");
    let archive_path = dir.join("corrupt.apk");
    fs::write(&archive_path, b"this is definitely not a zip archive").expect("write garbage");

    let archive = fs::File::open(&archive_path).expect("open archive");
    let error =
        extract_so_from_zip(archive.as_raw_fd(), &dir.join("out")).expect_err("garbage must fail");
    assert!(matches!(error, CoreError::InvalidZip(_)), "got {error:?}");
}

#[test]
fn extraction_leaves_the_callers_descriptor_usable() {
    let dir = temp_dir("fdlife");
    let archive_path = dir.join("app.apk");
    write_zip(&archive_path, &[(BEDROCK_SO_PATH, b"native-bytes")]);

    let mut archive = fs::File::open(&archive_path).expect("open archive");
    extract_named_so_from_zip(archive.as_raw_fd(), BEDROCK_SO_PATH, &dir.join("out"))
        .expect("extract");

    // The caller's descriptor must still be open: reading through it succeeds.
    let mut buffer = [0u8; 4];
    archive
        .read_exact(&mut buffer)
        .expect("descriptor still open");
}

#[test]
fn streamed_buffer_stays_at_the_documented_size() {
    // Guards the memory contract: the constant must not grow silently.
    assert_eq!(offset_core::zip_extract::STREAM_BUFFER_BYTES, 64 * 1024);
}
