//! Host tests for the JNI-adjacent helpers that do not need a JVM.

use std::fs;
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::path::PathBuf;

use offset_core::android::catch_panics;
use offset_core::jni_bridge::extraction_payload_json;
use offset_core::zip_extract::BEDROCK_SO_PATH;
use zip::write::SimpleFileOptions;

fn temp_dir(label: &str) -> PathBuf {
    let mut dir = std::env::temp_dir();
    dir.push(format!(
        "offset_core_jni_{label}_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

fn write_zip(path: &std::path::Path, entries: &[(&str, &[u8])]) {
    let file = fs::File::create(path).expect("create zip");
    let mut writer = zip::ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in entries {
        writer.start_file(*name, options).expect("start file");
        writer.write_all(bytes).expect("write entry");
    }
    writer.finish().expect("finish zip");
}

#[test]
fn payload_json_contains_path_hash_and_size() {
    let dir = temp_dir("payload");
    let archive_path = dir.join("app.apk");
    write_zip(&archive_path, &[(BEDROCK_SO_PATH, b"native-payload")]);

    let archive = fs::File::open(&archive_path).expect("open archive");
    let json = extraction_payload_json(archive.as_raw_fd(), &dir.join("out"))
        .expect("payload must be produced");

    assert!(json.contains("\"path\""), "json was {json}");
    assert!(json.contains("\"sha256\""), "json was {json}");
    assert!(json.contains("\"size\":14"), "json was {json}");
}

#[test]
fn payload_json_propagates_missing_entry_as_error() {
    let dir = temp_dir("payloadmissing");
    let archive_path = dir.join("app.apk");
    write_zip(&archive_path, &[("classes.dex", b"x")]);

    let archive = fs::File::open(&archive_path).expect("open archive");
    let error = extraction_payload_json(archive.as_raw_fd(), &dir.join("out"))
        .expect_err("missing entry must fail");
    assert!(error.to_string().contains("missing .so entry"), "{error}");
}

#[test]
fn catch_panics_returns_a_message_instead_of_unwinding() {
    let result = catch_panics("testing", || panic!("deliberate"));
    let message = result.expect_err("panic must be caught");
    assert!(message.contains("deliberate"), "{message}");
}

#[test]
fn catch_panics_passes_successful_values_through() {
    let result = catch_panics("testing", || 21 * 2);
    assert_eq!(result.expect("no panic"), 42);
}
