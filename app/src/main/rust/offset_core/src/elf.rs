//! Zero-copy ELF inspection.
//!
//! The extracted native library is mapped with `mmap` and never read into a
//! heap buffer. A library of 800 MB therefore costs address space, not resident
//! memory, and only the pages that are actually touched are faulted in.
//!
//! Parsing is deliberately shallow: `Elf::lazy_parse` is used instead of
//! `Elf::parse` because the full parse eagerly materialises program headers,
//! section headers, symbol tables and relocation tables. The later phases
//! (sections, symbols, RTTI) request exactly the structure they need, which
//! keeps peak memory flat.

use std::collections::HashMap;
use std::fs::File;
use std::path::{Path, PathBuf};

use goblin::elf::header::{EM_386, EM_AARCH64, EM_ARM, ET_DYN, ET_EXEC};
use goblin::elf::Elf;
use memmap2::Mmap;
use serde::Serialize;

use crate::error::{CoreError, CoreResult};

/// The four ELF magic bytes every valid file starts with.
pub const ELF_MAGIC: [u8; 4] = [0x7f, b'E', b'L', b'F'];

/// Minimum number of bytes needed to identify and parse an ELF header.
pub const ELF_HEADER_SIZE: usize = 64;

/// `EI_CLASS` value for a 32-bit object.
pub const ELFCLASS32: u8 = 1;

/// `EI_CLASS` value for a 64-bit object.
pub const ELFCLASS64: u8 = 2;

/// A mapped shared object plus its identity.
///
/// The mapping is read-only for the lifetime of the value. Dropping it unmaps
/// the file; the underlying descriptor is closed independently.
#[derive(Debug)]
pub struct MappedLibrary {
    map: Mmap,
    path: PathBuf,
    sha256: String,
    size: u64,
}

impl MappedLibrary {
    /// Maps the library at `path` without reading it into memory.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::Io`] when the file cannot be opened or mapped, and
    /// [`CoreError::InvalidElf`] when it is too small to hold a header.
    pub fn open(path: &Path, sha256: String) -> CoreResult<Self> {
        let file = File::open(path)
            .map_err(|error| CoreError::Io(format!("cannot open {}: {error}", path.display())))?;
        let size = file
            .metadata()
            .map_err(|error| CoreError::Io(format!("cannot stat {}: {error}", path.display())))?
            .len();

        if (size as usize) < ELF_HEADER_SIZE {
            return Err(CoreError::InvalidElf(format!(
                "{} is {size} bytes, smaller than an ELF header",
                path.display()
            )));
        }

        // SAFETY: the mapping is read-only and the engine never executes the
        // file, it only inspects bytes. The file is not expected to change
        // while mapped: it is an immutable cache entry written by this engine.
        let map = unsafe { Mmap::map(&file) }
            .map_err(|error| CoreError::Io(format!("cannot mmap {}: {error}", path.display())))?;

        Ok(Self {
            map,
            path: path.to_path_buf(),
            sha256,
            size,
        })
    }

    /// Returns the read-only byte view of the whole file.
    pub fn bytes(&self) -> &[u8] {
        &self.map
    }

    /// Returns the path the library was mapped from.
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Returns the SHA-256 recorded when the library was extracted.
    pub fn sha256(&self) -> &str {
        &self.sha256
    }

    /// Returns the file size in bytes.
    pub fn size(&self) -> u64 {
        self.size
    }

    /// Parses the ELF header and validates the target architecture.
    ///
    /// Only the header is decoded here; sections and symbols are parsed on
    /// demand by the callers that need them.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::InvalidElf`] for a malformed header and
    /// [`CoreError::UnsupportedArchitecture`] for anything that is not a
    /// 64-bit AArch64 object.
    pub fn parse_elf(&self) -> CoreResult<Elf<'_>> {
        parse_elf_bytes(self.bytes())
    }

    /// Builds the metadata reported to Kotlin.
    ///
    /// # Errors
    ///
    /// Propagates any failure from [`Self::parse_elf`].
    pub fn metadata(&self) -> CoreResult<ElfMetadata> {
        describe_library(
            self.bytes(),
            &self.path.display().to_string(),
            &self.sha256,
            self.size,
        )
    }
}

/// Validates and lazily parses an in-memory ELF image.
///
/// Kept separate from [`MappedLibrary`] so the parsing rules can be exercised
/// against byte buffers, without a file or a mapping.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] for a malformed header and
/// [`CoreError::UnsupportedArchitecture`] for anything that is not a 64-bit
/// AArch64 object.
pub fn parse_elf_bytes(bytes: &[u8]) -> CoreResult<Elf<'_>> {
    validate_magic(bytes)?;

    let header = Elf::parse_header(bytes)
        .map_err(|error| CoreError::InvalidElf(format!("cannot parse ELF header: {error}")))?;

    if header.e_ident[4] != ELFCLASS64 {
        return Err(CoreError::UnsupportedArchitecture(format!(
            "only 64-bit arm64-v8a libraries are supported, found a {}-bit ELF",
            if header.e_ident[4] == ELFCLASS32 {
                32
            } else {
                0
            }
        )));
    }

    if header.e_machine != EM_AARCH64 {
        return Err(CoreError::UnsupportedArchitecture(format!(
            "only arm64-v8a is supported, found machine {}",
            describe_machine(header.e_machine)
        )));
    }

    Elf::lazy_parse(header)
        .map_err(|error| CoreError::InvalidElf(format!("cannot decode ELF header: {error}")))
}

/// Builds the metadata payload from an ELF image.
///
/// # Errors
///
/// Propagates any failure from [`parse_elf_bytes`].
pub fn describe_library(
    bytes: &[u8],
    path: &str,
    sha256: &str,
    size: u64,
) -> CoreResult<ElfMetadata> {
    let elf = parse_elf_bytes(bytes)?;
    Ok(ElfMetadata {
        path: path.to_string(),
        sha256: sha256.to_string(),
        size,
        format: "ELF".to_string(),
        architecture: "AArch64".to_string(),
        bits: if elf.is_64 { 64 } else { 32 },
        endianness: if elf.little_endian { "little" } else { "big" }.to_string(),
        elf_type: describe_type(elf.header.e_type).to_string(),
        entry_point: format!("0x{:x}", elf.entry),
        entry_point_value: elf.entry,
        section_count: elf.section_headers.len(),
        program_header_count: elf.program_headers.len(),
        soname: elf.soname.map(str::to_string),
    })
}

/// JSON description of a mapped native library.
#[derive(Debug, Serialize)]
pub struct ElfMetadata {
    /// Absolute path of the mapped file.
    pub path: String,
    /// SHA-256 of the mapped file.
    pub sha256: String,
    /// Size of the mapped file in bytes.
    pub size: u64,
    /// Container format, always `ELF` for a valid library.
    pub format: String,
    /// Target architecture as reported by the ELF header.
    pub architecture: String,
    /// Word size in bits.
    pub bits: u32,
    /// Byte order of the file.
    pub endianness: String,
    /// ELF file type, for example `DYN`.
    pub elf_type: String,
    /// Entry point formatted as a hexadecimal string.
    pub entry_point: String,
    /// Entry point as a number, used by tests and kept out of the JSON.
    #[serde(skip)]
    pub entry_point_value: u64,
    /// Number of section headers.
    pub section_count: usize,
    /// Number of program headers.
    pub program_header_count: usize,
    /// `DT_SONAME` of the library, when present.
    pub soname: Option<String>,
}

/// Checks the ELF magic number.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the bytes do not start with `\x7fELF`.
pub fn validate_magic(bytes: &[u8]) -> CoreResult<()> {
    if bytes.len() < ELF_MAGIC.len() {
        return Err(CoreError::InvalidElf(format!(
            "file is {} bytes, too small to contain the ELF magic number",
            bytes.len()
        )));
    }
    if bytes[..ELF_MAGIC.len()] != ELF_MAGIC {
        return Err(CoreError::InvalidElf(format!(
            "expected ELF magic {ELF_MAGIC:02x?}, found {:02x?}",
            &bytes[..ELF_MAGIC.len()]
        )));
    }
    Ok(())
}

/// Renders an ELF machine number for a human-readable error.
pub fn describe_machine(machine: u16) -> String {
    match machine {
        EM_ARM => format!("{machine} (32-bit ARM)"),
        EM_386 => format!("{machine} (x86)"),
        EM_AARCH64 => format!("{machine} (AArch64)"),
        other => format!("{other}"),
    }
}

/// Renders an ELF file type for the metadata payload.
pub fn describe_type(file_type: u16) -> &'static str {
    match file_type {
        ET_DYN => "DYN",
        ET_EXEC => "EXEC",
        _ => "OTHER",
    }
}

/// Convenience map type used by later phases for name-to-address tables.
pub type AddressTable = HashMap<String, u64>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn magic_check_rejects_a_short_file() {
        let error = validate_magic(b"\x7fEL").expect_err("short input must fail");
        assert!(error.to_string().contains("too small"), "{error}");
    }

    #[test]
    fn magic_check_rejects_a_non_elf_file() {
        let mut bytes = vec![0u8; 128];
        bytes[..4].copy_from_slice(b"PK\x03\x04");
        let error = validate_magic(&bytes).expect_err("non-ELF must fail");
        assert!(error.to_string().contains("expected ELF magic"), "{error}");
    }

    #[test]
    fn magic_check_accepts_a_real_header_prefix() {
        let mut bytes = vec![0u8; ELF_HEADER_SIZE];
        bytes[..4].copy_from_slice(&ELF_MAGIC);
        assert!(validate_magic(&bytes).is_ok());
    }

    #[test]
    fn machine_description_names_the_known_architectures() {
        assert!(describe_machine(EM_ARM).contains("32-bit ARM"));
        assert!(describe_machine(EM_AARCH64).contains("AArch64"));
        assert_eq!(describe_machine(0x1234), "4660");
    }
}
