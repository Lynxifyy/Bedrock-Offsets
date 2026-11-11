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
//!
//! Peak memory is a first-class concern. A large Bedrock library carries
//! millions of relocations, so the index is built straight from the mapping and
//! keeps only the slots that land inside `.data.rel.ro`; no list of every
//! relocation is ever materialised, and no candidate list is buffered.

use std::collections::BTreeMap;

use goblin::elf::reloc::{R_AARCH64_ABS64, R_AARCH64_RELATIVE};
use goblin::elf::section_header::SHT_RELA;
use serde::Serialize;

use crate::elf::{parse_elf_bytes, parse_section_headers};
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
    /// Virtual address of the vtable base, which holds the offset-to-top value.
    ///
    /// This is the address the ABI points at when a class is used as a primary
    /// base: `vtable[-2]` is the offset-to-top and `vtable[-1]` the `type_info`
    /// pointer, so the first virtual function pointer is
    /// [`VTABLE_PREFIX_SLOTS`] slots further in.
    ///
    /// The address an *object* stores is [`Self::object_vtable_address`], one
    /// slot later; that is the value a reader of an object should use.
    pub vtable_address: u64,
    /// `vtable_address` rendered as `0x`-prefixed hex.
    pub vtable_hex: String,
    /// Offset of the vtable base from the image base, which survives rebasing.
    pub vtable_offset: u64,
    /// Virtual address of the `type_info` object.
    pub type_info_address: u64,
    /// Virtual address of the first virtual function pointer.
    pub first_virtual_address: u64,
}

impl TypeVTable {
    /// The address an object of this class stores in its vptr field.
    ///
    /// That is the vtable base plus one slot: the `type_info` pointer is
    /// skipped, because a vptr must not be mistaken for the `type_info` slot.
    pub fn object_vtable_address(&self) -> u64 {
        self.vtable_address.saturating_add(SLOT_BYTES)
    }
}

/// Every vtable the engine could resolve.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct VtableMap {
    /// How the vtables were found.
    pub source: VtableSource,
    /// Number of vtables accepted.
    ///
    /// Named for the payload field it fills; it counts accepted vtables now, not
    /// a buffered candidate list, because no candidate list exists any more.
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

/// Largest number of vtables reported.
///
/// A hostile or unusual library must not be able to make the result grow
/// without bound. 100,000 is far above any real Bedrock class count.
pub const MAX_VTABLES: usize = 100_000;

// The cap only exists to stop a crafted library growing the result without
// bound, so it must stay far above any real class count.
const _: () = assert!(MAX_VTABLES >= 10_000);

/// Largest `|offset-to-top|` accepted for `vtable[-2]`.
///
/// A primary vtable stores 0 and a secondary (multiple-inheritance) vtable
/// stores a small negative adjustment. Anything larger is ordinary data that
/// happens to precede a `type_info`-like pointer, not a vtable.
const MAX_OFFSET_TO_TOP: u64 = 1 << 20;

/// Slots between progress notes while walking `.data.rel.ro`.
const NOTE_EVERY_SLOTS: usize = 1 << 20;

/// Resolves every RTTI vtable it can find in a library.
///
/// # Errors
///
/// Returns [`crate::error::CoreError::InvalidElf`] when the library cannot be
/// parsed, and [`crate::error::CoreError::MissingSection`] when neither
/// `.data.rel.ro` nor `.rodata` is usable.
pub fn resolve_vtables(image: &[u8]) -> CoreResult<VtableMap> {
    resolve_vtables_with_progress(image, &mut |_| {})
}

/// Resolves vtables, reporting each phase to `note` as it starts.
///
/// The callback lets the caller persist a breadcrumb. A process killed mid
/// resolve cannot log, but the last phase written survives it and can be
/// reported on the next launch, which an in-process log cannot do.
///
/// # Errors
///
/// Same as [`resolve_vtables`].
pub fn resolve_vtables_with_progress(
    image: &[u8],
    note: &mut dyn FnMut(&str),
) -> CoreResult<VtableMap> {
    // Parsing validates the header and the machine type before any pointer is
    // followed.
    note("rtti: parsing ELF header");
    parse_elf_bytes(image)?;
    note("rtti: building section map");
    let map = build_section_map(image)?;

    // Only relocations that land inside `.data.rel.ro` can describe a vtable
    // slot, so only those are kept, sixteen bytes each. Building the index
    // straight from the mapping means no list of every relocation ever exists,
    // which is what keeps the transient peak off the resident set.
    let windows: Vec<(u64, u64)> = map
        .sections
        .iter()
        .filter(|section| section.name == DATA_REL_RO_SECTION)
        .map(|section| {
            (
                section.address,
                section.address.saturating_add(section.size),
            )
        })
        .collect();
    note("rtti: indexing relocations");
    let (relocations, examined) = RelocationIndex::from_image(image, &windows)?;
    note(&format!(
        "rtti: {examined} relocation records read, {} kept",
        relocations.entries.len()
    ));

    let source = if examined == 0 {
        VtableSource::DirectScan
    } else {
        VtableSource::Relocations
    };

    let (entries, accepted) = collect_vtables(&map, image, &relocations, note);
    note(&format!("rtti: done, {accepted} vtables accepted"));

    Ok(VtableMap {
        source,
        type_info_candidates: accepted,
        relocations_examined: examined,
        entries,
    })
}

/// Walks `.data.rel.ro` and resolves every slot that looks like `vtable[-1]`.
///
/// A slot is accepted only when all of the following hold:
///
/// * it points at a `type_info` object inside `.data.rel.ro`;
/// * the slot before it (`vtable[-2]`) is a plausible offset-to-top;
/// * the slot after it (`vtable[0]`) points into executable code;
/// * the object's name pointer lands on a valid mangled name.
///
/// The cheap tests run first, and accepted vtables are inserted as they are
/// found, so nothing is buffered and the resolver's own footprint is the result
/// plus the index.
///
/// # Returns
///
/// The vtables keyed by class name, and how many slots were accepted.
fn collect_vtables(
    map: &crate::sections::SectionMap,
    image: &[u8],
    relocations: &RelocationIndex,
    note: &mut dyn FnMut(&str),
) -> (BTreeMap<String, TypeVTable>, usize) {
    let mut entries: BTreeMap<String, TypeVTable> = BTreeMap::new();
    let mut accepted = 0usize;
    let slot = SLOT_BYTES as usize;

    for section in map
        .sections
        .iter()
        .filter(|section| section.name == DATA_REL_RO_SECTION)
    {
        let bytes = map.bytes_of(&section.name, image);
        let slots = bytes.len() / slot;
        // Slot 0 has no predecessor and the last slot has no successor.
        for index in 1..slots.saturating_sub(1) {
            if index % NOTE_EVERY_SLOTS == 0 {
                note(&format!("rtti: scanning slot {index} of {slots}"));
            }
            let offset = index * slot;
            let type_info_address = pointer_at(relocations, bytes, section.address, offset);
            if type_info_address == 0 || !in_data_rel_ro(map, type_info_address) {
                continue;
            }

            // `vtable[-2]` is the offset-to-top: 0 for a primary vtable, a small
            // negative adjustment for a secondary one.
            let offset_to_top = (read_u64(bytes, offset - slot) as i64).unsigned_abs();
            if offset_to_top > MAX_OFFSET_TO_TOP {
                continue;
            }

            let first_virtual = pointer_at(relocations, bytes, section.address, offset + slot);
            if !in_executable(map, first_virtual) {
                continue;
            }

            let Some(name_address) =
                type_info_name_address(map, image, type_info_address, relocations)
            else {
                continue;
            };
            let Some((class_name, mangled)) = read_type_name(map, image, name_address) else {
                continue;
            };

            accepted += 1;
            // The type_info pointer sits in `vtable[-1]`, so the vtable base is
            // one slot before the slot that holds it.
            let slot_address = section.address.saturating_add(offset as u64);
            let Some(vtable_address) = slot_address.checked_sub(SLOT_BYTES) else {
                continue;
            };
            if entries.len() >= MAX_VTABLES && !entries.contains_key(&class_name) {
                continue;
            }
            entries.entry(class_name.clone()).or_insert(TypeVTable {
                name: class_name,
                mangled,
                vtable_address,
                vtable_hex: format!("0x{vtable_address:x}"),
                vtable_offset: vtable_address.saturating_sub(map.image_base),
                type_info_address,
                first_virtual_address: first_virtual,
            });
        }
    }

    (entries, accepted)
}

/// Reads the pointer stored at `offset` of a section, preferring the relocation.
///
/// With RELA the in-place value is usually zero and the real target lives only
/// in the relocation table, so the table is consulted first.
fn pointer_at(
    relocations: &RelocationIndex,
    bytes: &[u8],
    section_address: u64,
    offset: usize,
) -> u64 {
    let address = section_address.saturating_add(offset as u64);
    relocations
        .target_of(address)
        .unwrap_or_else(|| read_u64(bytes, offset))
}

/// Whether `address` plus a 16-byte `type_info` lies inside `.data.rel.ro`.
fn in_data_rel_ro(map: &crate::sections::SectionMap, address: u64) -> bool {
    map.sections.iter().any(|section| {
        section.name == DATA_REL_RO_SECTION
            && section.occupies_file
            && address >= section.address
            && address.saturating_add(16) <= section.address.saturating_add(section.size)
    })
}

/// Whether `address` lies inside an executable, loaded section.
fn in_executable(map: &crate::sections::SectionMap, address: u64) -> bool {
    address != 0
        && map.sections.iter().any(|section| {
            section.allocated
                && section.executable
                && address >= section.address
                && address < section.address.saturating_add(section.size)
        })
}

/// Reads the name pointer out of a `type_info` object.
///
/// A `type_info` starts with its own vtable pointer, so the name pointer is the
/// second slot. The object must live in allocated, non-executable data: a
/// `type_info` never sits in `.text`, and a relocated function pointer always
/// does. Accepting a `.text` target here is what made a real library report one
/// candidate per virtual function pointer.
///
/// The name slot is relocated too, so its in-place value may be zero on a RELA
/// build; the relocation table is consulted first for that reason.
fn type_info_name_address(
    map: &crate::sections::SectionMap,
    image: &[u8],
    type_info_address: u64,
    relocations: &RelocationIndex,
) -> Option<u64> {
    let section = map.sections.iter().find(|section| {
        if !section.occupies_file || !section.allocated || section.executable {
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
    let name_slot = type_info_address.saturating_add(8);
    let name = relocations
        .target_of(name_slot)
        .unwrap_or_else(|| read_u64(bytes, offset + 8));
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
///
/// Only used by the unit tests, which build an index from a hand-written list.
#[cfg(test)]
struct Relocation {
    /// Virtual address the relocation writes to.
    offset: u64,
    /// Addend the linker applies.
    addend: u64,
    /// Relocation type.
    kind: u32,
}

/// Size of one `Elf64_Rela` record.
const RELA_ENTRY_BYTES: usize = 24;

/// A sorted slot-address-to-target index over the relocation sections.
///
/// Sixteen bytes per kept entry and one binary search per lookup. It is built
/// straight from the mapping, so no list of every relocation ever exists, which
/// is what keeps a large library's resolver footprint bounded.
struct RelocationIndex {
    entries: Vec<(u64, u64)>,
}

impl RelocationIndex {
    /// Reads every `SHT_RELA` section into an index of the slots in `windows`.
    ///
    /// `R_AARCH64_RELATIVE` means "store the address the linker computed for
    /// this slot", which is the addend; `R_AARCH64_ABS64` stores an absolute
    /// address carried by the addend. Every other kind is ignored. Records whose
    /// slot is outside `windows` are counted but not kept, so the index holds
    /// only slots that can describe a vtable.
    ///
    /// # Returns
    ///
    /// The index and the number of records examined; zero examined means the
    /// library has no relocation table and the caller direct-scans.
    ///
    /// # Errors
    ///
    /// Returns [`crate::error::CoreError::InvalidElf`] when the section header
    /// table is malformed.
    fn from_image(image: &[u8], windows: &[(u64, u64)]) -> CoreResult<(Self, usize)> {
        let headers = parse_section_headers(image)?;
        let mut entries: Vec<(u64, u64)> = Vec::new();
        let mut examined = 0usize;

        for header in &headers {
            if header.sh_type != SHT_RELA {
                continue;
            }
            let start = header.sh_offset as usize;
            let end = start.saturating_add(header.sh_size as usize);
            let Some(body) = image.get(start..end) else {
                continue;
            };

            for record in body.as_chunks::<RELA_ENTRY_BYTES>().0 {
                examined += 1;
                let kind = (read_u64(record, 8) & 0xffff_ffff) as u32;
                if kind != R_AARCH64_RELATIVE && kind != R_AARCH64_ABS64 {
                    continue;
                }
                let slot = read_u64(record, 0);
                if windows
                    .iter()
                    .any(|(low, high)| slot >= *low && slot < *high)
                {
                    entries.push((slot, read_u64(record, 16)));
                }
            }
        }

        entries.sort_unstable();
        entries.dedup_by_key(|entry| entry.0);
        Ok((Self { entries }, examined))
    }

    /// Builds the index from a flat relocation list (unit tests only).
    #[cfg(test)]
    fn build(relocations: &[Relocation]) -> Self {
        let mut entries: Vec<(u64, u64)> = relocations
            .iter()
            .filter(|relocation| {
                relocation.kind == R_AARCH64_RELATIVE || relocation.kind == R_AARCH64_ABS64
            })
            .map(|relocation| (relocation.offset, relocation.addend))
            .collect();
        entries.sort_unstable();
        entries.dedup();
        Self { entries }
    }

    /// Returns the address the slot at `slot_address` resolves to, if any.
    fn target_of(&self, slot_address: u64) -> Option<u64> {
        let index = self
            .entries
            .binary_search_by_key(&slot_address, |(offset, _)| *offset)
            .ok()?;
        Some(self.entries[index].1)
    }

    /// Number of relocations the index kept (unit tests only).
    #[cfg(test)]
    fn len(&self) -> usize {
        self.entries.len()
    }
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

        let index = RelocationIndex::build(&relocations);
        assert_eq!(index.target_of(0x1000), Some(0x2000));
        assert_eq!(index.target_of(0x1008), Some(0x3000));
        // An unsupported kind is dropped, and so is an unknown slot.
        assert_eq!(index.target_of(0x1010), None);
        assert_eq!(index.target_of(0x9999), None);
        assert_eq!(index.len(), 2);
    }

    #[test]
    fn reading_off_the_end_yields_zero() {
        assert_eq!(read_u64(&[1, 2, 3], 0), 0);
        assert_eq!(read_u64(&[], 0), 0);
    }

    #[test]
    fn relocation_sections_are_recognised() {}

    /// Builds a minimal AArch64 image with the sections the resolver needs.
    ///
    /// `rela` becomes the whole `.rela.dyn` body, so a test can control exactly
    /// which records the index sees.
    fn image_with_relocations(rela: &[u8]) -> Vec<u8> {
        const TEXT: u64 = 0x1000;
        const RODATA: u64 = 0x2000;
        const DATA: u64 = 0x3000;
        const RELA: u64 = 0x4000;

        struct Plan {
            name: &'static str,
            kind: u32,
            flags: u64,
            address: u64,
            body: Vec<u8>,
        }

        let sections = vec![
            Plan {
                name: ".text",
                kind: 1,
                flags: 0x6,
                address: TEXT,
                body: vec![0x90u8; 0x40],
            },
            Plan {
                name: ".rodata",
                kind: 1,
                flags: 0x2,
                address: RODATA,
                body: b"14ClientInstance\0".to_vec(),
            },
            Plan {
                name: ".data.rel.ro",
                kind: 1,
                flags: 0x3,
                address: DATA,
                body: vec![0u8; 0x20],
            },
            Plan {
                name: ".rela.dyn",
                kind: 4,
                flags: 0x2,
                address: RELA,
                body: rela.to_vec(),
            },
        ];

        let mut names = vec![0u8];
        let mut name_offsets = Vec::new();
        for section in &sections {
            name_offsets.push(names.len() as u32);
            names.extend_from_slice(section.name.as_bytes());
            names.push(0);
        }
        let shstrtab_name = names.len() as u32;
        names.extend_from_slice(b".shstrtab\0");

        let mut cursor = 0x100usize;
        let mut body_offsets = Vec::new();
        for section in &sections {
            body_offsets.push(cursor);
            cursor += section.body.len();
            cursor = (cursor + 7) & !7;
        }
        let shstrtab_offset = cursor;
        cursor += names.len();
        let shdr_offset = (cursor + 7) & !7;
        let count = sections.len() + 2;
        let total = shdr_offset + count * 64;

        let mut image = vec![0u8; total];
        image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        image[4] = 2;
        image[5] = 1;
        image[6] = 1;
        image[16..18].copy_from_slice(&3u16.to_le_bytes());
        image[18..20].copy_from_slice(&183u16.to_le_bytes());
        image[20..24].copy_from_slice(&1u32.to_le_bytes());
        image[40..48].copy_from_slice(&(shdr_offset as u64).to_le_bytes());
        image[52..54].copy_from_slice(&64u16.to_le_bytes());
        image[58..60].copy_from_slice(&64u16.to_le_bytes());
        image[60..62].copy_from_slice(&(count as u16).to_le_bytes());
        image[62..64].copy_from_slice(&((count - 1) as u16).to_le_bytes());

        for (index, section) in sections.iter().enumerate() {
            let start = body_offsets[index];
            image[start..start + section.body.len()].copy_from_slice(&section.body);
        }
        image[shstrtab_offset..shstrtab_offset + names.len()].copy_from_slice(&names);

        for (index, section) in sections.iter().enumerate() {
            let base = shdr_offset + (index + 1) * 64;
            image[base..base + 4].copy_from_slice(&name_offsets[index].to_le_bytes());
            image[base + 4..base + 8].copy_from_slice(&section.kind.to_le_bytes());
            image[base + 8..base + 16].copy_from_slice(&section.flags.to_le_bytes());
            image[base + 16..base + 24].copy_from_slice(&section.address.to_le_bytes());
            image[base + 24..base + 32]
                .copy_from_slice(&(body_offsets[index] as u64).to_le_bytes());
            image[base + 32..base + 40].copy_from_slice(&(section.body.len() as u64).to_le_bytes());
            image[base + 48..base + 56].copy_from_slice(&8u64.to_le_bytes());
        }
        let base = shdr_offset + (count - 1) * 64;
        image[base..base + 4].copy_from_slice(&shstrtab_name.to_le_bytes());
        image[base + 4..base + 8].copy_from_slice(&3u32.to_le_bytes());
        image[base + 24..base + 32].copy_from_slice(&(shstrtab_offset as u64).to_le_bytes());
        image[base + 32..base + 40].copy_from_slice(&(names.len() as u64).to_le_bytes());
        image[base + 48..base + 56].copy_from_slice(&1u64.to_le_bytes());
        image
    }

    /// Appends one `R_AARCH64_RELATIVE` record for `slot`.
    fn relative_record(body: &mut Vec<u8>, slot: u64, addend: u64) {
        body.extend_from_slice(&slot.to_le_bytes());
        body.extend_from_slice(&u64::from(R_AARCH64_RELATIVE).to_le_bytes());
        body.extend_from_slice(&addend.to_le_bytes());
    }

    #[test]
    fn the_index_keeps_only_slots_inside_data_rel_ro() {
        // A real library's relocations are almost all outside `.data.rel.ro`.
        // Retaining them is what made the resolver's transient peak hundreds of
        // megabytes, so the index must drop them as it reads, not later.
        let mut body = Vec::new();
        for index in 0..50_000u64 {
            // 0x100000 is far outside every section in the fixture.
            relative_record(&mut body, 0x100000 + index * 8, 0x2000);
        }
        relative_record(&mut body, 0x3008, 0x3000);
        relative_record(&mut body, 0x3010, 0x1000);

        let image = image_with_relocations(&body);
        let windows = vec![(0x3000u64, 0x3020u64)];
        let (index, examined) = RelocationIndex::from_image(&image, &windows).expect("index");

        assert_eq!(examined, 50_002, "every record must be counted");
        assert_eq!(index.len(), 2, "only the in-window slots may be kept");
    }

    #[test]
    fn unsupported_relocation_kinds_are_not_kept() {
        let mut body = Vec::new();
        // R_AARCH64_ABS64 is kept; a made-up kind is not.
        relative_record(&mut body, 0x3008, 0x3000);
        body.extend_from_slice(&0x3010u64.to_le_bytes());
        body.extend_from_slice(&u64::from(999u32).to_le_bytes());
        body.extend_from_slice(&0x1000u64.to_le_bytes());

        let image = image_with_relocations(&body);
        let windows = vec![(0x3000u64, 0x3020u64)];
        let (index, examined) = RelocationIndex::from_image(&image, &windows).expect("index");

        assert_eq!(examined, 2);
        assert_eq!(index.len(), 1);
    }
}
