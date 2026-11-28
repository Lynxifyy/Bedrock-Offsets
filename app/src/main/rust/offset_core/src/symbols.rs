//! GNU build identifier and dynamic symbol table.
//!
//! Both live in the mapping and are read in place: the build identifier is a
//! note descriptor, and the symbol table is a packed array of 24-byte records
//! whose names come from `.dynstr`. Neither is copied into the heap beyond the
//! names the caller asks for.

use goblin::container::{Container, Ctx, Endian};
use goblin::elf::note::{NoteDataIterator, NT_GNU_BUILD_ID};
use goblin::elf::section_header::{SHT_DYNSYM, SHT_NOTE};
use goblin::elf::sym::Sym;
use goblin::elf::Elf;
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::elf::parse_elf_bytes;
use crate::error::{CoreError, CoreResult};
use crate::sections::{
    build_section_map, DYNSTR_SECTION, DYNSYM_SECTION, STRTAB_SECTION, SYMTAB_SECTION, TEXT_SECTION,
};

/// Prefix every exported JNI function carries.
pub const JNI_SYMBOL_PREFIX: &str = "Java_";

/// Prefix of the lifecycle exports, `JNI_OnLoad` and `JNI_OnUnload`.
pub const JNI_ENTRY_PREFIX: &str = "JNI_";

/// Upper bound on the number of exported symbols kept.
///
/// A legitimate Minecraft library exports a few thousand JNI entry points. The
/// cap only exists so a hostile or corrupt `.dynsym` cannot make the engine
/// allocate without limit; when it is hit, the report says so.
pub const MAX_REPORTED_SYMBOLS: usize = 100_000;

/// How a build identifier was obtained.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BuildIdSource {
    /// Read from `.note.gnu.build-id`.
    Note,
    /// Derived from the SHA-256 of `.text` because the note was stripped.
    TextHash,
}

/// Identity of a native library, independent of its file name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct BuildIdentity {
    /// Lower-case hex build identifier.
    pub build_id: String,
    /// Where the identifier came from.
    pub source: BuildIdSource,
    /// Size of `.text` in bytes, the input to the fallback hash.
    pub text_size: u64,
}

/// An exported JNI function and its address.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ExportedSymbol {
    /// Symbol name as it appears in `.dynstr`.
    pub name: String,
    /// Virtual address of the symbol.
    pub address: u64,
    /// Address rendered as `0x`-prefixed hex, so both sides agree on the text.
    pub address_hex: String,
    /// Size recorded for the symbol, when the linker provided one.
    pub size: u64,
    /// `st_info` binding nibble; 1 is global, 2 is weak.
    pub binding: u8,
}

/// Every dynamic symbol of a library, plus the names exported to the JVM.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct SymbolTable {
    /// Number of entries in `.dynsym`, including undefined ones.
    pub total: usize,
    /// Number of defined global or weak symbols.
    pub defined: usize,
    /// Symbols whose name starts with [`JNI_SYMBOL_PREFIX`].
    pub jni: Vec<ExportedSymbol>,
    /// Lifecycle exports such as `JNI_OnLoad` and `JNI_OnUnload`.
    #[serde(default)]
    pub entry_points: Vec<ExportedSymbol>,
    /// Whether [`MAX_REPORTED_SYMBOLS`] truncated the JNI list.
    pub truncated: bool,
}

/// Build identifier and symbol table of one library.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SymbolReport {
    /// Identity of the library.
    pub identity: BuildIdentity,
    /// Symbols exported to the JVM.
    pub symbols: SymbolTable,
}

/// `st_bind` value for a symbol visible outside its own object.
const STB_GLOBAL: u8 = 1;

/// `st_bind` value for a symbol that may be overridden.
const STB_WEAK: u8 = 2;

/// Reads the build identifier and the exported symbols of a library.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the library cannot be parsed, and
/// [`CoreError::MissingSection`] when it has no usable `.text` section, which
/// is also required for the build-identifier fallback.
pub fn build_symbol_report(image: &[u8]) -> CoreResult<SymbolReport> {
    let elf = parse_elf_bytes(image)?;
    let map = build_section_map(image)?;

    Ok(SymbolReport {
        identity: build_identity(&elf, image)?,
        symbols: read_dynamic_symbols(&elf, image, &map)?,
    })
}

/// Resolves the build identifier, falling back to a hash of `.text`.
///
/// # Errors
///
/// Returns [`CoreError::MissingSection`] when the note is absent and `.text`
/// cannot be hashed.
pub fn build_identity(elf: &Elf<'_>, image: &[u8]) -> CoreResult<BuildIdentity> {
    if let Some(bytes) = build_id_from_note(elf, image)? {
        return Ok(BuildIdentity {
            build_id: to_hex(&bytes),
            source: BuildIdSource::Note,
            text_size: 0,
        });
    }

    let map = build_section_map(image)?;
    let text = map.bytes_of(TEXT_SECTION, image);
    if text.is_empty() {
        return Err(CoreError::MissingSection(format!(
            "{TEXT_SECTION} is empty, so no build identifier can be derived"
        )));
    }

    let digest = Sha256::digest(text);
    Ok(BuildIdentity {
        build_id: to_hex(&digest),
        source: BuildIdSource::TextHash,
        text_size: text.len() as u64,
    })
}

/// Reads the raw build identifier bytes from `.note.gnu.build-id`.
///
/// The section header is used when present; a fully section-stripped library
/// falls back to scanning the program headers, where the note is also mapped.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the note section is malformed. A
/// missing note is not an error: it is reported as `None`.
pub fn build_id_from_note(elf: &Elf<'_>, image: &[u8]) -> CoreResult<Option<Vec<u8>>> {
    let headers = crate::elf::parse_section_headers(image)?;

    for header in &headers {
        if header.sh_type != SHT_NOTE {
            continue;
        }
        let start = header.sh_offset as usize;
        let end = start.saturating_add(header.sh_size as usize);
        let Some(section) = image.get(start..end) else {
            continue;
        };
        if let Some(bytes) = note_build_id(section, context(elf)) {
            return Ok(Some(bytes));
        }
    }

    // Some builds place the note only in a PT_NOTE segment. `lazy_parse` leaves
    // the program header table empty, so it is read from the image.
    let programs = crate::elf::parse_program_headers(image).unwrap_or_default();
    for program in &programs {
        if program.p_type != goblin::elf::program_header::PT_NOTE {
            continue;
        }
        let start = program.p_offset as usize;
        let end = start.saturating_add(program.p_filesz as usize);
        let Some(segment) = image.get(start..end) else {
            continue;
        };
        if let Some(bytes) = note_build_id(segment, context(elf)) {
            return Ok(Some(bytes));
        }
    }

    Ok(None)
}

/// Extracts the first `NT_GNU_BUILD_ID` descriptor from a note blob.
fn note_build_id(blob: &[u8], ctx: Ctx) -> Option<Vec<u8>> {
    let mut iterator = NoteDataIterator {
        data: blob,
        size: blob.len(),
        offset: 0,
        ctx: (4, ctx),
    };
    for note in iterator.by_ref() {
        let Ok(note) = note else { continue };
        if note.n_type == NT_GNU_BUILD_ID && !note.desc.is_empty() {
            return Some(note.desc.to_vec());
        }
    }
    None
}

/// Reads `.dynsym` and filters the entries exported to the JVM.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the symbol table or its string table
/// cannot be read.
pub fn read_dynamic_symbols(
    _elf: &Elf<'_>,
    image: &[u8],
    map: &crate::sections::SectionMap,
) -> CoreResult<SymbolTable> {
    let Some(dynsym) = map.find(DYNSYM_SECTION) else {
        return Ok(SymbolTable {
            total: 0,
            defined: 0,
            jni: Vec::new(),
            entry_points: Vec::new(),
            truncated: false,
        });
    };
    if dynsym.size == 0 {
        return Ok(SymbolTable {
            total: 0,
            defined: 0,
            jni: Vec::new(),
            entry_points: Vec::new(),
            truncated: false,
        });
    }

    let context = section_context(image)?;
    let dynsym_bytes = map.bytes_of(DYNSYM_SECTION, image);
    let entry_size = Sym::size(context.container);
    let count = (dynsym.size as usize) / entry_size;
    let symbols = Sym::parse(dynsym_bytes, 0, count, context)
        .map_err(|error| CoreError::InvalidElf(format!("cannot read {DYNSYM_SECTION}: {error}")))?;

    let strings = map.bytes_of(DYNSTR_SECTION, image);
    let table = goblin::strtab::Strtab::parse(strings, 0, strings.len(), 0x0)
        .map_err(|error| CoreError::InvalidElf(format!("cannot read {DYNSTR_SECTION}: {error}")))?;

    let mut defined = 0usize;
    let mut jni = Vec::new();
    let mut entry_points = Vec::new();
    let mut truncated = false;

    for symbol in &symbols {
        let binding = symbol.st_bind();
        if symbol.st_shndx == 0 || (binding != STB_GLOBAL && binding != STB_WEAK) {
            continue;
        }
        defined += 1;

        let Some(name) = table.get_at(symbol.st_name) else {
            continue;
        };
        let is_java = name.starts_with(JNI_SYMBOL_PREFIX);
        let is_entry = name.starts_with(JNI_ENTRY_PREFIX);
        if !is_java && !is_entry {
            continue;
        }
        if jni.len() + entry_points.len() >= MAX_REPORTED_SYMBOLS {
            truncated = true;
            break;
        }
        let exported = ExportedSymbol {
            name: name.to_string(),
            address: symbol.st_value,
            address_hex: format!("0x{:x}", symbol.st_value),
            size: symbol.st_size,
            binding,
        };
        if is_java {
            jni.push(exported);
        } else {
            entry_points.push(exported);
        }
    }

    jni.sort_by(|left, right| left.name.cmp(&right.name));
    entry_points.sort_by(|left, right| left.name.cmp(&right.name));

    Ok(SymbolTable {
        total: symbols.len(),
        defined,
        jni,
        entry_points,
        truncated,
    })
}

/// Upper bound on the number of named symbols kept for the internal table.
///
/// A Bedrock library exports tens of thousands of symbols. The cap only stops a
/// corrupt `.dynsym` from allocating without limit.
pub const MAX_NAMED_SYMBOLS: usize = 500_000;

/// A defined dynamic symbol, with its Itanium-demangled name when possible.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NamedSymbol {
    /// Demangled name, or the raw name when it is not an Itanium symbol.
    pub name: String,
    /// Name exactly as it appears in `.dynstr`.
    pub mangled: String,
    /// Virtual address of the symbol.
    pub address: u64,
    /// Size recorded for the symbol, or zero when the linker omitted it.
    pub size: u64,
    /// `st_info` binding nibble.
    pub binding: u8,
}

/// A defined dynamic symbol that names data rather than code.
///
/// `_binary_classes_dex_size`, `__emutls_t.*` and similar entries live in the
/// symbol table but have no executable body. Exporting them as functions makes
/// the internal table claim code that does not exist, so they are separated.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DataSymbol {
    /// Demangled name, or the raw name when it is not an Itanium symbol.
    pub name: String,
    /// Name exactly as it appears in `.dynstr`.
    pub mangled: String,
    /// Virtual address of the symbol.
    pub address: u64,
    /// Size recorded for the symbol, or zero.
    pub size: u64,
    /// `data` for an object, `tls` for thread-local storage.
    pub kind: String,
    /// Why the symbol was classified as data.
    pub reason: String,
}

/// The dynamic symbols split into code and data.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize)]
pub struct NamedSymbols {
    /// Symbols with an executable body.
    pub functions: Vec<NamedSymbol>,
    /// Symbols that name data or thread-local storage.
    pub data: Vec<DataSymbol>,
}

/// `st_info` type: no type information.
const STT_NOTYPE: u8 = 0;
/// `st_info` type: data object.
const STT_OBJECT: u8 = 1;
/// `st_info` type: function.
const STT_FUNC: u8 = 2;
/// `st_info` type: section symbol, which names no code or data.
const STT_SECTION: u8 = 3;
/// `st_info` type: source file name, which names no code or data.
const STT_FILE: u8 = 4;
/// `st_info` type: thread-local storage.
const STT_TLS: u8 = 6;
/// Section index meaning "absolute, not relocated".
const SHN_ABS: usize = 0xfff1;
/// Section index meaning "common block".
const SHN_COMMON: usize = 0xfff2;

/// Whether an address lies inside an executable section.
fn in_executable_section(map: &crate::sections::SectionMap, address: u64) -> bool {
    map.sections.iter().any(|section| {
        section.executable
            && address >= section.address
            && address < section.address.saturating_add(section.size)
    })
}

/// Why a symbol names data rather than code, or `None` when it is a function.
fn data_reason_for(
    symbol_type: u8,
    section_index: usize,
    executable: bool,
) -> Option<&'static str> {
    if section_index == SHN_ABS {
        Some("absolute_symbol")
    } else if section_index == SHN_COMMON {
        Some("common_symbol")
    } else if symbol_type == STT_TLS {
        Some("tls_symbol")
    } else if symbol_type == STT_OBJECT {
        Some("object_symbol")
    } else if !executable {
        Some("outside_executable_section")
    } else if symbol_type != STT_FUNC && symbol_type != STT_NOTYPE {
        Some("non_function_type")
    } else {
        None
    }
}

/// Reads every defined symbol that carries a name, split into functions and
/// data.
///
/// Unlike [`read_dynamic_symbols`], which keeps only `Java_` entry points, this
/// is the naming source for the internal function table. A symbol is treated as
/// code only when its `st_info` type is `STT_FUNC`, or `STT_NOTYPE` with an
/// address inside an executable section. Everything else is returned as data so
/// the function table, its stats and its suspect count stay honest.
///
/// `.symtab` is preferred when the library is not stripped: it is a superset of
/// `.dynsym` and carries the local and hidden C++ symbols that never reach the
/// dynamic table, which is where most internal names live. A stripped release
/// library falls back to `.dynsym`, so exports still name what they can.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the symbol or string table cannot be
/// read.
pub fn read_named_symbols(image: &[u8]) -> CoreResult<NamedSymbols> {
    let map = build_section_map(image)?;
    let mut named = NamedSymbols::default();
    let used_static = read_named_from(
        &map,
        image,
        SYMTAB_SECTION,
        STRTAB_SECTION,
        true,
        &mut named,
    )?;
    if !used_static || (named.functions.is_empty() && named.data.is_empty()) {
        read_named_from(
            &map,
            image,
            DYNSYM_SECTION,
            DYNSTR_SECTION,
            false,
            &mut named,
        )?;
    }
    Ok(named)
}

/// Reads one symbol table into `named`.
///
/// # Returns
///
/// `true` when the table exists and was read; `false` when it is absent, empty
/// or has no string table, which is the signal to try the next source.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the symbol or string table cannot be
/// parsed.
fn read_named_from(
    map: &crate::sections::SectionMap,
    image: &[u8],
    sym_section: &str,
    str_section: &str,
    allow_local: bool,
    named: &mut NamedSymbols,
) -> CoreResult<bool> {
    let Some(sym) = map.find(sym_section) else {
        return Ok(false);
    };
    if sym.size == 0 {
        return Ok(false);
    }

    let context = section_context(image)?;
    let sym_bytes = map.bytes_of(sym_section, image);
    let entry_size = Sym::size(context.container);
    let count = (sym.size as usize) / entry_size;
    if count == 0 {
        return Ok(false);
    }
    let symbols = Sym::parse(sym_bytes, 0, count, context)
        .map_err(|error| CoreError::InvalidElf(format!("cannot read {sym_section}: {error}")))?;

    let strings = map.bytes_of(str_section, image);
    if strings.is_empty() {
        return Ok(false);
    }
    let table = goblin::strtab::Strtab::parse(strings, 0, strings.len(), 0x0)
        .map_err(|error| CoreError::InvalidElf(format!("cannot read {str_section}: {error}")))?;

    for symbol in &symbols {
        if named.functions.len() + named.data.len() >= MAX_NAMED_SYMBOLS {
            break;
        }
        let binding = symbol.st_bind();
        if symbol.st_shndx == 0 || (!allow_local && binding != STB_GLOBAL && binding != STB_WEAK) {
            continue;
        }
        let symbol_type = symbol.st_type();
        // A local `.symtab` also carries its source files and section symbols;
        // neither names code or data, so they must not reach the table.
        if symbol_type == STT_FILE || symbol_type == STT_SECTION {
            continue;
        }
        let Some(name) = table.get_at(symbol.st_name) else {
            continue;
        };
        if name.is_empty() {
            continue;
        }
        let executable = in_executable_section(map, symbol.st_value);
        if let Some(reason) = data_reason_for(symbol_type, symbol.st_shndx, executable) {
            named.data.push(DataSymbol {
                name: demangle(name).unwrap_or_else(|| name.to_string()),
                mangled: name.to_string(),
                address: symbol.st_value,
                size: symbol.st_size,
                kind: if symbol_type == STT_TLS {
                    "tls"
                } else {
                    "data"
                }
                .to_string(),
                reason: reason.to_string(),
            });
            continue;
        }

        named.functions.push(NamedSymbol {
            name: demangle(name).unwrap_or_else(|| name.to_string()),
            mangled: name.to_string(),
            address: symbol.st_value,
            size: symbol.st_size,
            binding,
        });
    }
    Ok(true)
}

/// Returns the address of the defined symbol named `name`, if it is exported.
///
/// Used to identify `__cxa_pure_virtual` without hard-coding an address.
pub fn find_symbol_address(image: &[u8], name: &str) -> Option<u64> {
    let map = build_section_map(image).ok()?;
    let dynsym = map.find(DYNSYM_SECTION)?;
    if dynsym.size == 0 {
        return None;
    }
    let context = section_context(image).ok()?;
    let dynsym_bytes = map.bytes_of(DYNSYM_SECTION, image);
    let count = (dynsym.size as usize) / Sym::size(context.container);
    let symbols = Sym::parse(dynsym_bytes, 0, count, context).ok()?;
    let strings = map.bytes_of(DYNSTR_SECTION, image);
    let table = goblin::strtab::Strtab::parse(strings, 0, strings.len(), 0x0).ok()?;
    for symbol in &symbols {
        if symbol.st_shndx == 0 {
            continue;
        }
        if table.get_at(symbol.st_name) == Some(name) {
            return Some(symbol.st_value);
        }
    }
    None
}

/// Demangles an Itanium C++ symbol, returning `None` when it is not one.
///
/// A JNI export (`Java_...`) or a plain C symbol is not mangled, so it is left
/// untouched by returning `None`; the caller keeps the original spelling.
pub fn demangle(name: &str) -> Option<String> {
    if !name.starts_with("_Z") {
        return None;
    }
    cpp_demangle::Symbol::new(name).ok()?.demangle().ok()
}

/// Builds the container context of an ELF image.
fn context(elf: &Elf<'_>) -> Ctx {
    let container = if elf.is_64 {
        Container::Big
    } else {
        Container::Little
    };
    let endian = if elf.little_endian {
        Endian::Little
    } else {
        Endian::Big
    };
    Ctx::new(container, endian)
}

/// Builds the container context straight from the header of an image.
fn section_context(image: &[u8]) -> CoreResult<Ctx> {
    let header = Elf::parse_header(image)
        .map_err(|error| CoreError::InvalidElf(format!("cannot parse ELF header: {error}")))?;
    let container = if header.e_ident[4] == 2 {
        Container::Big
    } else {
        Container::Little
    };
    let endian = if header.e_ident[5] == 2 {
        Endian::Big
    } else {
        Endian::Little
    };
    Ok(Ctx::new(container, endian))
}

/// Renders bytes as lower-case hexadecimal.
pub fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

/// Reports whether a section header is a dynamic symbol table.
pub fn is_dynamic_symbols(section_type: u32) -> bool {
    section_type == SHT_DYNSYM
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_rendering_is_lower_case_and_padded() {
        assert_eq!(to_hex(&[0x00, 0x0f, 0xa5, 0xff]), "000fa5ff");
        assert_eq!(to_hex(&[]), "");
    }

    #[test]
    fn dynamic_symbol_sections_are_recognised() {
        assert!(is_dynamic_symbols(SHT_DYNSYM));
        assert!(!is_dynamic_symbols(SHT_NOTE));
    }

    #[test]
    fn a_blob_without_notes_yields_no_build_id() {
        assert_eq!(note_build_id(&[], Ctx::default()), None);
        assert_eq!(note_build_id(&[0u8; 32], Ctx::default()), None);
    }

    #[test]
    fn build_id_note_is_extracted_from_a_synthetic_blob() {
        // n_namesz=4 ("GNU\0"), n_descsz=4, n_type=NT_GNU_BUILD_ID, then the
        // name and the descriptor, each padded to a four-byte boundary.
        let mut blob = Vec::new();
        blob.extend_from_slice(&4u32.to_le_bytes());
        blob.extend_from_slice(&4u32.to_le_bytes());
        blob.extend_from_slice(&NT_GNU_BUILD_ID.to_le_bytes());
        blob.extend_from_slice(b"GNU\0");
        blob.extend_from_slice(&[0xde, 0xad, 0xbe, 0xef]);

        let found = note_build_id(&blob, Ctx::default()).expect("note must be found");
        assert_eq!(found, vec![0xde, 0xad, 0xbe, 0xef]);
    }

    #[test]
    fn data_symbols_are_separated_from_functions() {
        // A function in .text stays a function.
        assert_eq!(data_reason_for(STT_FUNC, 2, true), None);
        // An object, a TLS entry and an absolute symbol are data even in .text.
        assert_eq!(data_reason_for(STT_OBJECT, 2, true), Some("object_symbol"));
        assert_eq!(data_reason_for(STT_TLS, 2, true), Some("tls_symbol"));
        assert_eq!(
            data_reason_for(STT_FUNC, SHN_ABS, true),
            Some("absolute_symbol")
        );
        // An untyped symbol outside .text is data, which is what
        // `_binary_classes_dex_size` is.
        assert_eq!(
            data_reason_for(STT_NOTYPE, 3, false),
            Some("outside_executable_section")
        );
        // An untyped symbol inside .text is still treated as code.
        assert_eq!(data_reason_for(STT_NOTYPE, 2, true), None);
    }

    #[test]
    fn a_non_build_id_note_is_ignored() {
        let mut blob = Vec::new();
        blob.extend_from_slice(&4u32.to_le_bytes());
        blob.extend_from_slice(&4u32.to_le_bytes());
        blob.extend_from_slice(&1u32.to_le_bytes()); // NT_GNU_ABI_TAG
        blob.extend_from_slice(b"GNU\0");
        blob.extend_from_slice(&[1, 2, 3, 4]);

        assert_eq!(note_build_id(&blob, Ctx::default()), None);
    }
}
