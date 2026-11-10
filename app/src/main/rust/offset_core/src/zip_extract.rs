//! Streaming extraction of native libraries from an APK archive.
//!
//! The archive is read through a duplicated file descriptor and the target
//! entry is copied out with a fixed-size buffer, so peak heap use is constant
//! even for multi-gigabyte APKs.

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use zip::ZipArchive;

use crate::error::{CoreError, CoreResult};
use crate::fd::duplicate_fd;

/// Size of the streaming buffer. The engine never allocates more than this
/// while extracting, regardless of the archive or entry size.
pub const STREAM_BUFFER_BYTES: usize = 64 * 1024;

/// Canonical location of the Minecraft Bedrock native library inside an APK.
pub const BEDROCK_SO_PATH: &str = "lib/arm64-v8a/libminecraftpe.so";

/// Result of a successful extraction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedSo {
    /// Absolute path of the extracted file in the cache directory.
    pub path: String,
    /// Lower-case hex SHA-256 of the extracted bytes.
    pub sha256: String,
    /// Size of the extracted file in bytes.
    pub size: u64,
}

/// Extracts [`BEDROCK_SO_PATH`] from the archive reachable through `fd`.
///
/// `dest_dir` receives the extracted file; it is created when absent.
///
/// # Errors
///
/// - [`CoreError::InvalidDescriptor`] when `fd` cannot be duplicated.
/// - [`CoreError::InvalidZip`] when the archive cannot be parsed.
/// - [`CoreError::MissingSoFile`] when the entry is absent.
/// - [`CoreError::Io`] on any filesystem failure.
pub fn extract_so_from_zip(fd: libc::c_int, dest_dir: &Path) -> CoreResult<ExtractedSo> {
    extract_named_so_from_zip(fd, BEDROCK_SO_PATH, dest_dir)
}

/// Extracts the entry named `entry_name` from the archive reachable through `fd`.
///
/// Split out from [`extract_so_from_zip`] so tests can target synthetic
/// archives with arbitrary entry names.
///
/// # Errors
///
/// See [`extract_so_from_zip`].
pub fn extract_named_so_from_zip(
    fd: libc::c_int,
    entry_name: &str,
    dest_dir: &Path,
) -> CoreResult<ExtractedSo> {
    let file = duplicate_fd(fd)?;
    let mut archive = ZipArchive::new(file)?;

    let mut entry = match archive.by_name(entry_name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => {
            return Err(CoreError::MissingSoFile(entry_name.to_string()));
        }
        Err(other) => return Err(CoreError::InvalidZip(other.to_string())),
    };

    if entry.is_dir() {
        return Err(CoreError::MissingSoFile(entry_name.to_string()));
    }

    std::fs::create_dir_all(dest_dir)?;
    let file_name = Path::new(entry_name)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("libminecraftpe.so");
    let destination = dest_dir.join(file_name);

    let (sha256, size) = stream_entry_to_file(&mut entry, &destination)?;

    Ok(ExtractedSo {
        path: destination.to_string_lossy().into_owned(),
        sha256,
        size,
    })
}

/// Streams `entry` into `destination`, hashing as it copies.
///
/// Only [`STREAM_BUFFER_BYTES`] are ever held in memory.
fn stream_entry_to_file<R: Read>(entry: &mut R, destination: &Path) -> CoreResult<(String, u64)> {
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; STREAM_BUFFER_BYTES];
    let mut written: u64 = 0;

    let mut output = File::create(destination)?;
    loop {
        let read = entry.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
        output.write_all(&buffer[..read])?;
        written += read as u64;
    }
    output.flush()?;

    let digest = hasher.finalize();
    Ok((to_hex(&digest), written))
}

/// Renders bytes as lower-case hexadecimal.
fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

/// Returns a stable cache file name for a given entry, used by callers that
/// want to predict where the extraction will land.
pub fn cache_file_name_for(entry_name: &str) -> PathBuf {
    Path::new(entry_name)
        .file_name()
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("libminecraftpe.so"))
}
