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
use std::sync::{Arc, Mutex, OnceLock};

use goblin::container::{Container, Ctx, Endian};
use goblin::elf::dynamic::{DT_NULL, DT_SONAME};
use goblin::elf::header::{Header, EM_386, EM_AARCH64, EM_ARM, ET_DYN, ET_EXEC};
use goblin::elf::program_header::ProgramHeader;
use goblin::elf::section_header::{SectionHeader, SHT_DYNAMIC};
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

/// The process-wide mapping cache: the key it was opened with plus the mapping.
type SharedCache = Mutex<Option<(String, Arc<MappedLibrary>)>>;

/// Returns the shared mapping cache, creating it on first use.
fn shared_cache() -> &'static SharedCache {
    static CACHE: OnceLock<SharedCache> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(None))
}

impl MappedLibrary {
    /// Maps the library at `path`, or returns the mapping already open for it.
    ///
    /// The scan, symbol, vtable and internal passes all run as separate JNI
    /// calls. Mapping the same 378 MB library once per call multiplies its
    /// resident pages by the number of calls, so one shared mapping is kept for
    /// the most recently used file. A new path or hash replaces it.
    ///
    /// # Errors
    ///
    /// Propagates the same failures as [`MappedLibrary::open`].
    pub fn open_shared(path: &Path, sha256: String) -> CoreResult<Arc<Self>> {
        let key = format!("{}|{sha256}", path.display());
        let mut guard = shared_cache()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some((cached_key, library)) = guard.as_ref() {
            if *cached_key == key {
                return Ok(Arc::clone(library));
            }
        }
        let library = Arc::new(Self::open(path, sha256)?);
        *guard = Some((key, Arc::clone(&library)));
        Ok(library)
    }

    /// Drops the shared mapping held for the most recently opened library.
    ///
    /// The cache exists so the four JNI passes do not each map the library.
    /// It must not outlive the file: a mapping kept after the extracted `.so`
    /// is deleted or replaced faults with `SIGBUS` on the next access, and that
    /// signal bypasses the `catch_unwind` guard around every entry point. The
    /// app calls this before it deletes a library or clears the cache.
    ///
    /// # Returns
    ///
    /// `true` when a mapping was dropped, `false` when the cache was empty.
    pub fn clear_shared_cache() -> bool {
        let mut guard = shared_cache()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.take().is_some()
    }

    /// Maps the library at `path` without reading it into memory.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::Io`] when the file cannot be opened or mapped, and
    /// [`CoreError::InvalidElf`] when it is too small to hold a header.
    pub fn open(path: &Path, sha256: String) -> CoreResult<Self> {
        let file = File::open(path).map_err(|error| io_error("cannot open", path, &error))?;
        let size = file
            .metadata()
            .map_err(|error| io_error("cannot stat", path, &error))?
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
        let map =
            unsafe { Mmap::map(&file) }.map_err(|error| io_error("cannot mmap", path, &error))?;

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

    /// Advises the kernel that the mapped file's pages may be reclaimed.
    ///
    /// Scanning touches every page of `.text`, so the resident set climbs to the
    /// size of that section even though nothing is copied. Between phases the
    /// pages are no longer needed: the file is on disk, so dropping them costs
    /// only a re-read if a later phase touches them again. This is what keeps a
    /// 342 MB library from holding hundreds of megabytes resident for the whole
    /// scan.
    ///
    /// The advice is best-effort; a kernel that refuses it leaves the mapping
    /// intact, so a failure is ignored rather than propagated.
    pub fn advise_pages_droppable(&self) {
        // SAFETY: `self.map` is a live, page-aligned mapping for its whole
        // length, and `MADV_DONTNEED` on a read-only mapping only tells the
        // kernel it may discard clean pages. It never changes the file and never
        // invalidates the mapping.
        unsafe {
            let address = self.map.as_ptr() as *mut libc::c_void;
            libc::madvise(address, self.map.len(), libc::MADV_DONTNEED);
        }
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

impl crate::scanner::PageReleaser for MappedLibrary {
    /// Offers the byte range `start..end` of the file for reclamation.
    ///
    /// The range is expanded to page boundaries, because `madvise` requires a
    /// page-aligned address and length. Expanding is safe: the extra bytes are
    /// clean file-backed pages that will simply be re-read if they are touched
    /// again.
    fn release(&self, start: usize, end: usize) {
        let page = page_size();
        if page == 0 || end <= start {
            return;
        }
        let length = self.map.len();
        if start >= length {
            return;
        }
        let aligned_start = start - (start % page);
        let aligned_end = end.min(length).div_ceil(page) * page;
        let span = aligned_end.saturating_sub(aligned_start);
        if span == 0 {
            return;
        }
        // SAFETY: the range lies inside the live mapping, is page-aligned, and
        // `MADV_DONTNEED` on a read-only mapping only allows the kernel to drop
        // clean pages. The file is unchanged and the mapping stays valid.
        unsafe {
            let address = self.map.as_ptr().add(aligned_start) as *mut libc::c_void;
            libc::madvise(address, span, libc::MADV_DONTNEED);
        }
    }
}

/// The system page size, or 0 when it cannot be determined.
fn page_size() -> usize {
    // SAFETY: `sysconf` is always safe to call and returns a `long`.
    let value = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if value <= 0 {
        0
    } else {
        value as usize
    }
}

/// Renders a filesystem failure with the OS error number.
///
/// `std::io::Error`'s `Display` output is often just the strerror text, which
/// does not say *which* errno produced it. A user report of "cannot open
/// /data/.../libminecraftpe.so" is not actionable without `NotFound (os error
/// 2)` versus `TooManyOpenFiles (os error 24)`, so both the kind and the raw
/// errno are always included.
fn io_error(action: &str, path: &Path, error: &std::io::Error) -> CoreError {
    CoreError::Io(format!(
        "{action} {}: {:?} (os error {})",
        path.display(),
        error.kind(),
        error.raw_os_error().unwrap_or(-1)
    ))
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

/// Parses the section header table of an ELF image.
///
/// `Elf::lazy_parse` deliberately leaves the header table empty, so it is read
/// here when a caller actually needs it. Only the headers are read: no section
/// body is copied, which is what keeps an 800 MB library cheap.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the header table lies outside the
/// file or cannot be decoded.
pub fn parse_section_headers(image: &[u8]) -> CoreResult<Vec<SectionHeader>> {
    let header = Elf::parse_header(image)
        .map_err(|error| CoreError::InvalidElf(format!("cannot parse ELF header: {error}")))?;

    SectionHeader::parse(
        image,
        header.e_shoff as usize,
        header.e_shnum as usize,
        header_ctx(&header),
    )
    .map_err(|error| CoreError::InvalidElf(format!("cannot read section headers: {error}")))
}

/// Parses the program header table of an ELF image.
///
/// `Elf::lazy_parse` leaves `program_headers` empty as well, so anything that
/// needs a `PT_NOTE` segment -- the build-id fallback, for example -- has to
/// read the table through this function.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the table lies outside the file or
/// cannot be decoded.
pub fn parse_program_headers(image: &[u8]) -> CoreResult<Vec<ProgramHeader>> {
    let header = Elf::parse_header(image)
        .map_err(|error| CoreError::InvalidElf(format!("cannot parse ELF header: {error}")))?;

    ProgramHeader::parse(
        image,
        header.e_phoff as usize,
        header.e_phnum as usize,
        header_ctx(&header),
    )
    .map_err(|error| CoreError::InvalidElf(format!("cannot read program headers: {error}")))
}

/// Builds the parse context (word size and byte order) from an ELF header.
fn header_ctx(header: &Header) -> Ctx {
    let container = if header.e_ident[4] == ELFCLASS64 {
        Container::Big
    } else {
        Container::Little
    };
    let endian = if header.e_ident[5] == 2 {
        Endian::Big
    } else {
        Endian::Little
    };
    Ctx::new(container, endian)
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
    // `lazy_parse` leaves the tables and the dynamic table empty, so the counts
    // and the soname are read from the image instead. A failure here is not
    // fatal: the metadata is still useful without them.
    let section_count = parse_section_headers(bytes).map_or(0, |headers| headers.len());
    let program_header_count = parse_program_headers(bytes).map_or(0, |headers| headers.len());
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
        section_count,
        program_header_count,
        soname: parse_soname(bytes),
    })
}

/// Reads `DT_SONAME` from the dynamic table, if the library declares one.
///
/// Only the `.dynamic` and its linked string table are touched, and neither is
/// copied: the string is borrowed from the mapping. Returns `None` for a library
/// without a dynamic table, which is normal for a relocatable object.
fn parse_soname(image: &[u8]) -> Option<String> {
    let headers = parse_section_headers(image).ok()?;
    // `sh_link` of `.dynamic` names the string table that holds the soname.
    let dynamic = headers
        .iter()
        .find(|header| header.sh_type == SHT_DYNAMIC)?;
    let dynstr = headers.get(dynamic.sh_link as usize)?;

    let entries = image.get(
        dynamic.sh_offset as usize
            ..(dynamic.sh_offset as usize).saturating_add(dynamic.sh_size as usize),
    )?;
    let dynstr_bytes = image.get(
        dynstr.sh_offset as usize
            ..(dynstr.sh_offset as usize).saturating_add(dynstr.sh_size as usize),
    )?;

    let mut soname_offset = None;
    for entry in entries.as_chunks::<16>().0 {
        let tag = u64::from_le_bytes(entry[0..8].try_into().ok()?);
        let value = u64::from_le_bytes(entry[8..16].try_into().ok()?);
        if tag == DT_SONAME {
            soname_offset = Some(value as usize);
            break;
        }
        if tag == DT_NULL {
            break;
        }
    }

    let offset = soname_offset?;
    let tail = dynstr_bytes.get(offset..)?;
    let end = tail.iter().position(|byte| *byte == 0)?;
    String::from_utf8(tail[..end].to_vec()).ok()
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

    #[test]
    fn clearing_the_shared_cache_releases_the_mapping() {
        let path = std::env::temp_dir().join(format!(
            "offset-core-elf-cache-{}-{}.so",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ));
        let mut bytes = vec![0u8; 128];
        bytes[..4].copy_from_slice(&ELF_MAGIC);
        std::fs::write(&path, &bytes).expect("write fixture");

        let first = MappedLibrary::open_shared(&path, "sha".to_string()).expect("open");
        let second = MappedLibrary::open_shared(&path, "sha".to_string()).expect("reuse");
        assert!(Arc::ptr_eq(&first, &second), "the cache must be reused");
        drop(first);
        drop(second);

        assert!(MappedLibrary::clear_shared_cache());
        assert!(
            !MappedLibrary::clear_shared_cache(),
            "a second clear has nothing to release"
        );
        // The file can now be replaced without a live mapping faulting.
        std::fs::write(&path, &bytes).expect("replace fixture");
        MappedLibrary::open_shared(&path, "sha".to_string()).expect("reopen");
        MappedLibrary::clear_shared_cache();
        let _ = std::fs::remove_file(&path);
    }
}
