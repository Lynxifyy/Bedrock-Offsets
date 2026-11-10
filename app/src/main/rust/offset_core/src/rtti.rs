//! RTTI type-name and vtable resolution.
//!
//! Itanium C++ ABI RTTI, which the NDK uses, lays a polymorphic class out like
//! this in `.data.rel.ro`:
//!
//! ```text
//! vtable[-2]  offset-to-top   (0 for the primary vtable)
//! vtable[-1]  typeinfo pointer -> std::type_info in .data.rel.ro
//! vtable[ 0]  first virtual function pointer
//! ```
//!
//! The `type_info` object itself begins with a pointer to the `__class_type_info`
//! vtable, followed by the name:
//!
//! ```text
//! typeinfo[0]  vtable pointer
//! typeinfo[1]  name pointer -> "14ClientInstance" in .rodata
//! ```
//!
//! The mangled name carries its own length prefix (`14` before `ClientInstance`),
//! so it can be recognised without a symbol table, and the class name can be
//! recovered from it. Relocations then say where each pointer lands once the
//! library is loaded, which is what makes the vtable address meaningful.
//!
//! Nothing here executes the library: the pointers are read as data.

use std::collections::BTreeMap;

use goblin::elf::reloc::{R_AARCH64_ABS64, R_AARCH64_RELATIVE};
use goblin::elf::section_header::{SHT_PROGBITS, SHT_RELA};
use serde::Serialize;

use crate::elf::parse_elf_bytes;
use crate::error::CoreResult;
use crate::sections::{build_section_map, DATA_REL_RO_SECTION, RELA_DYN_SECTION, RODATA_SECTION};

/// Longest class name accepted from a mangled type name.
///
/// Real Bedrock class names are short. The cap stops a crafted length prefix
/// from making the engine scan megabytes for a name that does not exist.
pub const MAX_TYPE_NAME_BYTES: usize = 256;

/// Number of slots before a vtable's first function pointer.
///
/// Slot `-2` holds the offset-to-top and slot `-1` holds the `type_info`
/// pointer, so the first virtual function pointer is two slots in.
pub const VTABLE_PREFIX_SLOTS: usize = 2;

/// Width of one vtable slot on AArch64.
pub const SLOT_BYTES: u64 = 8;

/// A resolved RTTI type and its vtable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct TypeVTable {
    /// Demangled class name, for example `ClientInstance`.
    pub name: String,
    /// The mangled name as it appears in `.rodata`.
    pub mangled: String,
    /// Virtual address of the vtable's first function pointer.
    pub vtable_address: u64,
    /// `vtable_address` rendered as `0x`-prefixed hex.
    pub vtable_hex: String,
    /// Offset of the vtable from the image base, which survives rebasing.
    pub vtable_offset: u64,
    /// Virtual address of the `type_info` object.
    pub type_info_address: u64,
}

/// Every vtable the engine could resolve.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct VtableMap {
    /// How the vtables were found.
    pub source: VtableSource,
    /// Number of `type_info` name pointers discovered.
    pub type_info_candidates: usize,
    /// Number of relocations examined.
    pub relocations_examined: usize,
    /// Resolved vtables, keyed by class name.
    pub entries: BTreeMap<String, TypeVTable>,
}

impl VtableMap {
    /// Looks a class up by its demangled name.
    pub fn get(&self, name: &str) -> Option<&TypeVTable> {
        self.entries.get(name)
    }

    /// Number of resolved vtables.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether nothing was resolved.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// How a vtable address was derived.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum VtableSource {
    /// The `type_info` pointer was located through `.rela.dyn`.
    Relocations,
    /// Relocations were unusable, so the `type_info` pointer was read directly
    /// from `.data.rel.ro`.
    DirectScan,
}

/// Resolves every RTTI vtable it can find in a library.
///
/// # Errors
///
/// Returns [`crate::error::CoreError::InvalidElf`] when the library cannot be
/// parsed, and [`crate::error::CoreError::MissingSection`] when neither
/// `.data.rel.ro` nor `.rodata` is usable.
pub fn resolve_vtables(image: &[u8]) -> CoreResult<VtableMap> {
    // Parsing validates the header and the machine type before any pointer is
    // followed.
    parse_elf_bytes(image)?;
    let map = build_section_map(image)?;

    let relocations = read_relocations(image)?;
    let reloc_pointer = relocation_targets(&relocations);

    let candidates = find_type_info_pointers(&map, image, &reloc_pointer);

    let mut entries = BTreeMap::new();
    for candidate in &candidates {
        let Some((class_name, mangled)) = read_type_name(&map, image, candidate.name_address)
        else {
            continue;
        };
        // The type_info pointer occupies `vtable[-1]`, so the vtable starts one
        // slot before the slot that holds it.
        let Some(vtable_address) = candidate.slot_address.checked_sub(SLOT_BYTES) else {
            continue;
        };
        entries.entry(class_name.clone()).or_insert(TypeVTable {
            name: class_name,
            mangled,
            vtable_address,
            vtable_hex: format!("0x{vtable_address:x}"),
            vtable_offset: vtable_address.saturating_sub(map.image_base),
            type_info_address: candidate.type_info_address,
        });
    }

    let source = if relocations.is_empty() {
        VtableSource::DirectScan
    } else {
        VtableSource::Relocations
    };

    Ok(VtableMap {
        source,
        type_info_candidates: candidates.len(),
        relocations_examined: relocations.len(),
        entries,
    })
}

/// A `type_info` pointer found in the read-only data.
#[derive(Debug, Clone, PartialEq, Eq)]
struct TypeInfoCandidate {
    /// Virtual address of the slot holding the `type_info` pointer.
    ///
    /// This is `vtable[-1]`, so the vtable itself begins one slot earlier.
    slot_address: u64,
    /// Virtual address of the `type_info` object.
    type_info_address: u64,
    /// Virtual address of the name string the `type_info` points at.
    name_address: u64,
}

/// Finds every pointer in `.data.rel.ro` that could be a `type_info` pointer.
///
/// A `type_info` pointer is an eight-byte little-endian value that relocation
/// information places inside a mapped section and that is followed, at the next
/// slot, by another pointer into `.rodata`. That pairing is the signature of a
/// vtable prefix: `typeinfo` pointer followed by the start of the function
/// pointer table.
fn find_type_info_pointers(
    map: &crate::sections::SectionMap,
    image: &[u8],
    relocation_targets: &BTreeMap<u64, u64>,
) -> Vec<TypeInfoCandidate> {
    let mut candidates = Vec::new();
    let sections: Vec<_> = map
        .sections
        .iter()
        .filter(|section| section.name == DATA_REL_RO_SECTION)
        .collect();
    if sections.is_empty() {
        return candidates;
    }

    for section in sections {
        let bytes = map.bytes_of(&section.name, image);
        let slot = SLOT_BYTES as usize;
        if bytes.len() < slot {
            continue;
        }
        for offset in (0..=bytes.len() - slot).step_by(slot) {
            let raw = read_u64(bytes, offset);
            if raw == 0 {
                continue;
            }
            let slot_address = section.address.saturating_add(offset as u64);
            // The relocation table wins when it knows the slot.
            let type_info_address = relocation_targets
                .get(&slot_address)
                .copied()
                .unwrap_or(raw);
            if type_info_address == 0 {
                continue;
            }

            let Some(name_address) = type_info_name_address(map, image, type_info_address) else {
                continue;
            };
            candidates.push(TypeInfoCandidate {
                slot_address,
                type_info_address,
                name_address,
            });
        }
    }

    candidates.sort_by_key(|candidate| candidate.type_info_address);
    candidates.dedup();
    candidates
}

/// Reads the name pointer out of a `type_info` object.
///
/// A `type_info` starts with its own vtable pointer, so the name pointer is the
/// second slot. The object is located by its virtual address.
fn type_info_name_address(
    map: &crate::sections::SectionMap,
    image: &[u8],
    type_info_address: u64,
) -> Option<u64> {
    let section = map.sections.iter().find(|section| {
        if !section.occupies_file {
            return false;
        }
        let end = section.address.saturating_add(section.size);
        let needed = type_info_address.saturating_add(16);
        type_info_address >= section.address && needed <= end
    })?;
    let bytes = map.bytes_of(&section.name, image);
    let offset = (type_info_address - section.address) as usize;
    if offset.saturating_add(16) > bytes.len() {
        return None;
    }
    let name = read_u64(bytes, offset + 8);
    if name == 0 {
        return None;
    }
    Some(name)
}

/// Reads and demangles the type name a `type_info` points at.
///
/// The mangled form is `<length><name>`, for example `14ClientInstance`. A name
/// that does not parse is skipped rather than guessed at.
///
/// # Returns
///
/// `(demangled name, mangled name)` when the string is a valid type name.
fn read_type_name(
    map: &crate::sections::SectionMap,
    image: &[u8],
    name_address: u64,
) -> Option<(String, String)> {
    let section = map.sections.iter().find(|section| {
        section.occupies_file
            && section.allocated
            && name_address >= section.address
            && name_address < section.address.saturating_add(section.size)
    })?;
    let bytes = map.bytes_of(&section.name, image);
    let start = (name_address - section.address) as usize;
    if start >= bytes.len() {
        return None;
    }

    // The name must live in read-only data; a pointer into `.text` is a
    // coincidence, not a type name.
    if section.name != RODATA_SECTION && section.name != DATA_REL_RO_SECTION {
        return None;
    }

    let (length, digits) = read_length_prefix(&bytes[start..])?;
    if length == 0 || length > MAX_TYPE_NAME_BYTES {
        return None;
    }
    let name_start = start + digits;
    let name_end = name_start.checked_add(length)?;
    let raw = bytes.get(name_start..name_end)?;
    if !raw.iter().all(|byte| is_name_byte(*byte)) {
        return None;
    }
    let name = String::from_utf8(raw.to_vec()).ok()?;
    let mangled = format!("{length}{name}");
    Some((name, mangled))
}

/// Reads the decimal length prefix of a mangled type name.
///
/// # Returns
///
/// `(length, digits consumed)`.
fn read_length_prefix(bytes: &[u8]) -> Option<(usize, usize)> {
    let mut digits = 0usize;
    let mut value = 0usize;
    for byte in bytes {
        if !byte.is_ascii_digit() {
            break;
        }
        // A leading zero would make the length ambiguous.
        if digits == 0 && *byte == b'0' {
            return None;
        }
        value = value.checked_mul(10)?.checked_add((byte - b'0') as usize)?;
        digits += 1;
        if digits > 4 {
            return None;
        }
    }
    if digits == 0 {
        return None;
    }
    Some((value, digits))
}

/// Whether a byte may appear inside a C++ type name.
fn is_name_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_' || byte == b':' || byte == b'.' || byte == b'$'
}

/// One `.rela.dyn` entry, reduced to what vtable resolution needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Relocation {
    /// Virtual address the relocation writes to.
    offset: u64,
    /// Addend the linker applies.
    addend: u64,
    /// Relocation type.
    kind: u32,
}

/// Reads `.rela.dyn` into a flat list.
///
/// # Errors
///
/// Returns [`CoreError::InvalidElf`] when the section is malformed. A library
/// with no `.rela.dyn` at all yields an empty list, which is the signal for the
/// caller to fall back to a direct scan.
fn read_relocations(image: &[u8]) -> CoreResult<Vec<Relocation>> {
    let headers = crate::elf::parse_section_headers(image)?;
    let mut relocations = Vec::new();

    for header in &headers {
        if header.sh_type != SHT_RELA {
            continue;
        }
        let start = header.sh_offset as usize;
        let end = start.saturating_add(header.sh_size as usize);
        let Some(body) = image.get(start..end) else {
            continue;
        };

        const ENTRY_BYTES: usize = 24;
        let count = body.len() / ENTRY_BYTES;
        for index in 0..count {
            let base = index * ENTRY_BYTES;
            let offset = read_u64(body, base);
            let info = read_u64(body, base + 8);
            let addend = read_u64(body, base + 16);
            relocations.push(Relocation {
                offset,
                addend,
                kind: (info & 0xffff_ffff) as u32,
            });
        }
    }

    Ok(relocations)
}

/// Maps relocation target addresses to the address they resolve to.
///
/// Two relocation kinds matter here. `R_AARCH64_RELATIVE` means "store the
/// address the linker computed for this slot", which is the addend; the loader
/// adds the image base when it maps the library. `R_AARCH64_ABS64` stores an
/// absolute address, which the addend already carries. Either way the addend is
/// the virtual address the slot will hold, so both kinds are recorded the same
/// way. Every other kind is ignored rather than guessed at.
fn relocation_targets(relocations: &[Relocation]) -> BTreeMap<u64, u64> {
    relocations
        .iter()
        .filter(|relocation| {
            relocation.kind == R_AARCH64_RELATIVE || relocation.kind == R_AARCH64_ABS64
        })
        .map(|relocation| (relocation.offset, relocation.addend))
        .collect()
}

/// Reads a little-endian `u64` at `offset`, or zero when it runs off the end.
fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    match bytes.get(offset..offset + 8) {
        Some(slice) => u64::from_le_bytes([
            slice[0], slice[1], slice[2], slice[3], slice[4], slice[5], slice[6], slice[7],
        ]),
        None => 0,
    }
}

/// Whether a section header holds relocations, used by callers that want to
/// check for `.rela.dyn` before parsing.
pub fn is_relocation_section(section_type: u32) -> bool {
    section_type == SHT_RELA
}

/// Whether a section header holds program data, used by the fallback scan.
pub fn is_program_data(section_type: u32) -> bool {
    section_type == SHT_PROGBITS
}

/// Name of the section holding relocations, re-exported for tests.
pub const RELOCATION_SECTION: &str = RELA_DYN_SECTION;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_length_prefix_is_parsed() {
        assert_eq!(read_length_prefix(b"14ClientInstance"), Some((14, 2)));
        assert_eq!(read_length_prefix(b"3Foo"), Some((3, 1)));
    }

    #[test]
    fn a_missing_length_prefix_is_rejected() {
        assert_eq!(read_length_prefix(b"ClientInstance"), None);
        assert_eq!(read_length_prefix(b""), None);
        assert_eq!(read_length_prefix(b"01Foo"), None);
    }

    #[test]
    fn an_absurd_length_prefix_is_rejected() {
        assert_eq!(read_length_prefix(b"99999Foo"), None);
        assert_eq!(read_length_prefix(b"99999999999999999999"), None);
    }

    #[test]
    fn name_bytes_are_restricted() {
        assert!(is_name_byte(b'A'));
        assert!(is_name_byte(b'z'));
        assert!(is_name_byte(b'0'));
        assert!(is_name_byte(b'_'));
        assert!(is_name_byte(b':'));
        assert!(!is_name_byte(b' '));
        assert!(!is_name_byte(b'\n'));
        assert!(!is_name_byte(0xff));
    }

    #[test]
    fn relative_relocations_resolve_to_their_addend() {
        let relocations = vec![
            Relocation {
                offset: 0x1000,
                addend: 0x2000,
                kind: R_AARCH64_RELATIVE,
            },
            Relocation {
                offset: 0x1008,
                addend: 0x3000,
                kind: R_AARCH64_ABS64,
            },
            Relocation {
                offset: 0x1010,
                addend: 0x4000,
                kind: 999,
            },
        ];

        let targets = relocation_targets(&relocations);
        assert_eq!(targets.get(&0x1000), Some(&0x2000));
        assert_eq!(targets.get(&0x1008), Some(&0x3000));
        assert_eq!(targets.get(&0x1010), None);
    }

    #[test]
    fn reading_off_the_end_yields_zero() {
        assert_eq!(read_u64(&[1, 2, 3], 0), 0);
        assert_eq!(read_u64(&[], 0), 0);
    }

    #[test]
    fn relocation_sections_are_recognised() {
        assert!(is_relocation_section(SHT_RELA));
        assert!(!is_relocation_section(SHT_PROGBITS));
        assert!(is_program_data(SHT_PROGBITS));
    }
}
