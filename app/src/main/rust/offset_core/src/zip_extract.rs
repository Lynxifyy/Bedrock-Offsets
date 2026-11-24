//! Streaming extraction of native libraries from an APK archive.
//!
//! The archive is read through a duplicated file descriptor and the target
//! entry is copied out with a fixed-size buffer, so peak heap use is constant
//! even for multi-gigabyte APKs.

use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use sha2::{Digest, Sha256};
use zip::ZipArchive;

use crate::error::{CoreError, CoreResult};
use crate::fd::duplicate_fd;
use crate::symbols::to_hex;

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

    // Collect the ABI list before `by_name` borrows the archive, so the
    // missing-entry path can explain what the APK actually contains.
    let available_abis = collect_lib_abis(&mut archive);
    let mut entry = match archive.by_name(entry_name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => {
            return Err(CoreError::MissingSoFile(missing_entry_message(
                &available_abis,
                entry_name,
            )));
        }
        Err(other) => return Err(CoreError::InvalidZip(other.to_string())),
    };

    if entry.is_dir() {
        drop(entry);
        return Err(CoreError::MissingSoFile(missing_entry_message(
            &available_abis,
            entry_name,
        )));
    }

    std::fs::create_dir_all(dest_dir)?;
    let file_name = Path::new(entry_name)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("libminecraftpe.so");

    // The content hash is only known after streaming, so the entry lands in a
    // temporary file first and is renamed into its hash-named directory. The
    // target is therefore never truncated in place: a mapping of a previous
    // extraction keeps its inode alive even when a newer extraction replaces
    // the directory entry, and a concurrent scan can never observe a
    // half-written library.
    let part = dest_dir.join(part_file_name());
    let streamed = stream_entry_to_file(&mut entry, &part);

    let (sha256, size) = match streamed {
        Ok(values) => values,
        Err(error) => {
            let _ = std::fs::remove_file(&part);
            return Err(error);
        }
    };

    let hashed_dir = dest_dir.join(&sha256);
    let destination = hashed_dir.join(file_name);
    if let Ok(existing) = std::fs::metadata(&destination) {
        // A previous extraction of the same bytes is already in place. Reusing
        // it avoids touching a file that may currently be mapped.
        if existing.len() == size {
            let _ = std::fs::remove_file(&part);
            return Ok(ExtractedSo {
                path: destination.to_string_lossy().into_owned(),
                sha256,
                size,
            });
        }
    }

    std::fs::create_dir_all(&hashed_dir)?;
    std::fs::rename(&part, &destination).map_err(|error| {
        let _ = std::fs::remove_file(&part);
        CoreError::Io(format!(
            "cannot move extracted library into {}: {:?} (os error {})",
            hashed_dir.display(),
            error.kind(),
            error.raw_os_error().unwrap_or(-1)
        ))
    })?;

    Ok(ExtractedSo {
        path: destination.to_string_lossy().into_owned(),
        sha256,
        size,
    })
}

/// Builds a unique name for the in-progress extraction file.
///
/// A fixed `.part` name would make two extractions of different APKs collide;
/// the process id and a nanosecond timestamp keep them apart.
fn part_file_name() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    format!("libminecraftpe.{}.{nanos}.part", std::process::id())
}

/// Lists the ABIs that carry `libminecraftpe.so` in the archive.
fn collect_lib_abis(archive: &mut ZipArchive<File>) -> Vec<String> {
    let mut abis: Vec<String> = archive
        .file_names()
        .filter_map(|name| name.ok())
        .filter(|name| name.starts_with("lib/") && name.ends_with("/libminecraftpe.so"))
        .filter_map(|name| name.split('/').nth(1).map(str::to_string))
        .collect();
    abis.sort();
    abis.dedup();
    abis
}

/// Explains why the expected native library is absent, naming the ABIs present.
///
/// "does not contain libminecraftpe.so" gives the user nothing to act on. The
/// archive is asked which `lib/<abi>/libminecraftpe.so` entries it *does* carry,
/// so the message can say "this APK only has armeabi-v7a" and what to do about
/// a split APK.
fn missing_entry_message(abis: &[String], entry_name: &str) -> String {
    if abis.is_empty() {
        return format!(
            "{entry_name} is absent and the archive contains no lib/<abi>/libminecraftpe.so at \
             all. This is not a Bedrock APK with native code, or its native libraries were \
             removed."
        );
    }

    let list = abis.join(", ");
    let mut message = format!(
        "{entry_name} is absent. This APK only has {list}; AArch64 (lib/arm64-v8a) is required."
    );
    if abis.iter().any(|abi| abi != "arm64-v8a") {
        message.push_str(" If this is a split APK, select the base + arm64 config APK.");
    }
    message
}

/// Reads the `versionName` from the APK's binary `AndroidManifest.xml`.
///
/// The manifest is a small binary XML document, not text. Only the string pool
/// and the `manifest` start element are decoded, so the cost is bounded by the
/// manifest size rather than the APK size.
///
/// # Errors
///
/// Returns [`CoreError::InvalidZip`] when the archive cannot be read. A missing
/// or malformed manifest is reported as `Ok(None)`; it is a best-effort source
/// for a file name, never a reason to fail an extraction.
pub fn read_apk_version_name(fd: libc::c_int) -> CoreResult<Option<String>> {
    let file = duplicate_fd(fd)?;
    let mut archive = ZipArchive::new(file)?;
    let mut entry = match archive.by_name("AndroidManifest.xml") {
        Ok(entry) => entry,
        Err(_) => return Ok(None),
    };
    let mut bytes = Vec::new();
    entry.read_to_end(&mut bytes)?;
    Ok(parse_axml_version(&bytes))
}

/// Extracts `versionName` from a binary Android XML manifest.
fn parse_axml_version(bytes: &[u8]) -> Option<String> {
    const RES_XML_TYPE: u16 = 0x0003;
    const STRING_POOL_TYPE: u16 = 0x0001;
    const START_ELEMENT_TYPE: u16 = 0x0102;

    if bytes.len() < 8 {
        return None;
    }
    if u16::from_le_bytes([bytes[0], bytes[1]]) != RES_XML_TYPE {
        return None;
    }
    let mut strings: Vec<String> = Vec::new();
    let mut position = 8usize;
    while position + 8 <= bytes.len() {
        let kind = u16::from_le_bytes([bytes[position], bytes[position + 1]]);
        let header_size = u16::from_le_bytes([bytes[position + 2], bytes[position + 3]]) as usize;
        let size = u32::from_le_bytes([
            bytes[position + 4],
            bytes[position + 5],
            bytes[position + 6],
            bytes[position + 7],
        ]) as usize;
        if size < 8 || position + size > bytes.len() {
            break;
        }
        let chunk = &bytes[position..position + size];
        if kind == STRING_POOL_TYPE {
            strings = parse_string_pool(chunk);
        } else if kind == START_ELEMENT_TYPE {
            if let Some(version) = version_from_start_element(chunk, header_size, &strings) {
                return Some(version);
            }
        }
        position += size;
    }
    None
}

/// Parses a `ResStringPool` chunk into owned strings.
fn parse_string_pool(chunk: &[u8]) -> Vec<String> {
    if chunk.len() < 28 {
        return Vec::new();
    }
    let string_count = u32::from_le_bytes([chunk[8], chunk[9], chunk[10], chunk[11]]) as usize;
    let flags = u32::from_le_bytes([chunk[16], chunk[17], chunk[18], chunk[19]]);
    let strings_start = u32::from_le_bytes([chunk[20], chunk[21], chunk[22], chunk[23]]) as usize;
    let header_size = u16::from_le_bytes([chunk[2], chunk[3]]) as usize;
    let is_utf8 = flags & 0x0100 != 0;
    let mut strings = Vec::with_capacity(string_count);
    for index in 0..string_count {
        let offset_at = header_size + index * 4;
        let Some(offset_bytes) = chunk.get(offset_at..offset_at + 4) else {
            break;
        };
        let offset = u32::from_le_bytes([
            offset_bytes[0],
            offset_bytes[1],
            offset_bytes[2],
            offset_bytes[3],
        ]) as usize;
        let mut cursor = match strings_start.checked_add(offset) {
            Some(cursor) if cursor < chunk.len() => cursor,
            _ => {
                strings.push(String::new());
                continue;
            }
        };
        if is_utf8 {
            let Some(first) = chunk.get(cursor).copied() else {
                strings.push(String::new());
                continue;
            };
            cursor += 1;
            let mut length = first as usize;
            if first & 0x80 != 0 {
                let Some(second) = chunk.get(cursor).copied() else {
                    strings.push(String::new());
                    continue;
                };
                cursor += 1;
                length = ((first & 0x7F) as usize) << 8 | second as usize;
            }
            let end = cursor.saturating_add(length).min(chunk.len());
            strings.push(
                String::from_utf8_lossy(chunk.get(cursor..end).unwrap_or_default()).to_string(),
            );
        } else {
            let Some(low) = chunk.get(cursor).copied() else {
                strings.push(String::new());
                continue;
            };
            cursor += 1;
            let Some(high) = chunk.get(cursor).copied() else {
                strings.push(String::new());
                continue;
            };
            cursor += 1;
            let mut length = u16::from_le_bytes([low, high]) as usize;
            if length & 0x8000 != 0 {
                let Some(extra) = chunk.get(cursor..cursor + 2) else {
                    strings.push(String::new());
                    continue;
                };
                cursor += 2;
                length =
                    ((length & 0x7FFF) << 16) | u16::from_le_bytes([extra[0], extra[1]]) as usize;
            }
            let end = cursor.saturating_add(length * 2).min(chunk.len());
            let mut units = Vec::with_capacity(length);
            let mut at = cursor;
            while at + 1 < end {
                units.push(u16::from_le_bytes([chunk[at], chunk[at + 1]]));
                at += 2;
            }
            strings.push(String::from_utf16_lossy(&units));
        }
    }
    strings
}

/// Reads `versionName` from a `ResXMLTree_attrExt` start-element chunk.
fn version_from_start_element(
    chunk: &[u8],
    header_size: usize,
    strings: &[String],
) -> Option<String> {
    if chunk.len() < 30 {
        return None;
    }
    // `ResXMLTree_node` puts the element name at byte 20; `attributeStart` and
    // `attributeCount` follow inside `ResXMLTree_attrExt`.
    let name_index = u32::from_le_bytes([chunk[20], chunk[21], chunk[22], chunk[23]]) as usize;
    if strings.get(name_index).map(String::as_str) != Some("manifest") {
        return None;
    }
    let attribute_start = u16::from_le_bytes([chunk[24], chunk[25]]) as usize;
    let attribute_count = u16::from_le_bytes([chunk[28], chunk[29]]) as usize;
    let first = header_size.saturating_add(attribute_start);
    for index in 0..attribute_count {
        let at = first + index * 20;
        let Some(attribute) = chunk.get(at..at + 20) else {
            break;
        };
        let attribute_name =
            u32::from_le_bytes([attribute[4], attribute[5], attribute[6], attribute[7]]) as usize;
        if strings.get(attribute_name).map(String::as_str) != Some("versionName") {
            continue;
        }
        let data_type = attribute[15];
        let data = u32::from_le_bytes([attribute[16], attribute[17], attribute[18], attribute[19]])
            as usize;
        if data_type == 0x03 {
            if let Some(value) = strings.get(data) {
                return Some(value.clone());
            }
        }
    }
    None
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
