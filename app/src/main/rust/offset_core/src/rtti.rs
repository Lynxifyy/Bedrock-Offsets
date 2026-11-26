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

use goblin::elf::reloc::{
    R_AARCH64_ABS64, R_AARCH64_GLOB_DAT, R_AARCH64_JUMP_SLOT, R_AARCH64_RELATIVE,
};
use goblin::elf::section_header::SHT_RELA;
use serde::Serialize;

use crate::elf::parse_elf_bytes;
use crate::error::{CoreError, CoreResult};
use crate::sections::{build_section_map, DATA_REL_RO_SECTION, RODATA_SECTION};

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
    /// Virtual address of the vtable itself.
    ///
    /// This is the slot *before* the `type_info` pointer, which holds the
    /// offset-to-top value. The first virtual function pointer sits
    /// `VTABLE_PREFIX_SLOTS` slots further in. The address is kept at the vtable
    /// base because that is the value a loader uses to identify the class.
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
    ///
    /// A class with multiple inheritance has one vtable per base subobject; the
    /// map keeps the primary one (lowest `type_info` address) so a name lookup
    /// is unambiguous.
    pub entries: BTreeMap<String, TypeVTable>,
    /// Every resolved vtable, including the secondary groups of a class with
    /// multiple inheritance.
    ///
    /// Not serialised: the wire format stays keyed by class name. The internal
    /// pass walks this list so no group is silently dropped.
    #[serde(skip)]
    pub all: Vec<TypeVTable>,
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
    resolve_vtables_with_releaser(image, None)
}

/// Resolves every RTTI vtable, releasing dead pages through `releaser`.
///
/// The relocation tables are read once and then never again, so offering them
/// back to the kernel between building the index and scanning `.data.rel.ro`
/// keeps the file-backed part of this phase from adding to the resident set.
///
/// # Errors
///
/// As [`resolve_vtables`], plus [`CoreError::OutOfMemory`] when the relocation
/// index cannot be allocated.
pub fn resolve_vtables_with_releaser(
    image: &[u8],
    releaser: Option<&dyn crate::scanner::PageReleaser>,
) -> CoreResult<VtableMap> {
    // Parsing validates the header and the machine type before any pointer is
    // followed.
    parse_elf_bytes(image)?;
    let map = build_section_map(image)?;

    // The index is built by streaming `.rela.dyn`; the flat table is never
    // materialised, which is what keeps this phase inside its memory budget.
    let reloc_pointer = RelocationIndex::build(image)?;
    let relocations_examined = reloc_pointer.examined();
    let source = if reloc_pointer.is_empty() {
        VtableSource::DirectScan
    } else {
        VtableSource::Relocations
    };

    // The tables have been read into the index and are not touched again, so
    // their pages can go before the slot scan faults in `.data.rel.ro`.
    if let Some(releaser) = releaser {
        reloc_pointer.release_source_pages(releaser);
    }

    // Every `type_info` pointer is resolved to a class name as it is found, so
    // the million-slot scan keeps only the vtables it actually identified: a
    // library with 200k candidate slots yields a few thousand entries here, not
    // a 200k-element vector.
    let (type_info_candidates, resolved) = collect_vtables(&map, image, &reloc_pointer);

    let mut entries: BTreeMap<String, TypeVTable> = BTreeMap::new();
    let mut all = Vec::with_capacity(resolved.len());
    for vtable in resolved {
        let resolved = TypeVTable {
            vtable_hex: format!("0x{:x}", vtable.vtable_address),
            vtable_offset: vtable.vtable_address.saturating_sub(map.image_base),
            name: vtable.name,
            mangled: vtable.mangled,
            vtable_address: vtable.vtable_address,
            type_info_address: vtable.type_info_address,
        };
        // Insertion order is by `type_info` address, so when two candidates
        // claim the same class name the lower address wins and the output is
        // reproducible.
        entries
            .entry(resolved.name.clone())
            .or_insert_with(|| resolved.clone());
        all.push(resolved);
    }

    Ok(VtableMap {
        source,
        type_info_candidates,
        relocations_examined,
        entries,
        all,
    })
}

/// A vtable whose class name was read back successfully.
#[derive(Debug, Clone, PartialEq, Eq)]
struct ResolvedVtable {
    /// Virtual address of the `type_info` object, used to order the entries.
    type_info_address: u64,
    /// Virtual address of the vtable itself, one slot before the `type_info`
    /// pointer.
    vtable_address: u64,
    /// Demangled class name.
    name: String,
    /// Mangled name as it appears in `.rodata`.
    mangled: String,
}

/// Walks `.data.rel.ro` and returns every vtable whose class name parses.
///
/// # Returns
///
/// The number of candidate slots examined, and the resolved vtables ordered by
/// `type_info` address. Only resolved vtables are retained: the candidates that
/// fail the name check are counted and discarded on the spot, so the memory cost
/// is proportional to the classes found, not to the slots scanned.
fn collect_vtables(
    map: &crate::sections::SectionMap,
    image: &[u8],
    relocation_targets: &RelocationIndex,
) -> (usize, Vec<ResolvedVtable>) {
    let mut candidates = 0usize;
    let mut resolved = Vec::new();
    let slot = SLOT_BYTES as usize;

    for section in map
        .sections
        .iter()
        .filter(|section| section.name == DATA_REL_RO_SECTION)
    {
        let bytes = map.bytes_of(&section.name, image);
        if bytes.len() < slot {
            continue;
        }
        for offset in (0..=bytes.len() - slot).step_by(slot) {
            let raw = read_u64(bytes, offset);
            let slot_address = section.address.saturating_add(offset as u64);
            // The relocation table wins when it knows the slot. With RELA the
            // in-place value is usually zero, so a zero slot must not be skipped
            // before the table has been consulted.
            let type_info_address = relocation_targets.target_of(slot_address).unwrap_or(raw);
            if type_info_address == 0 {
                continue;
            }
            let Some(name_address) =
                type_info_name_address(map, image, type_info_address, relocation_targets)
            else {
                continue;
            };
            candidates += 1;
            let Some((name, mangled)) = read_type_name(map, image, name_address) else {
                continue;
            };
            // The `type_info` pointer occupies `vtable[-1]`, so the vtable
            // starts one slot before the slot that holds it.
            let Some(vtable_address) = slot_address.checked_sub(SLOT_BYTES) else {
                continue;
            };
            if !is_plausible_vtable(map, image, relocation_targets, vtable_address, slot_address) {
                continue;
            }
            resolved.push(ResolvedVtable {
                type_info_address,
                vtable_address,
                name,
                mangled,
            });
        }
    }

    // Ordering by `type_info` address makes the first-wins rule above
    // deterministic. The vector holds resolved vtables only, so this sort is
    // cheap.
    resolved.sort_by_key(|vtable| vtable.type_info_address);
    resolved.dedup();
    (candidates, resolved)
}

/// Whether a candidate really starts a vtable.
///
/// A `type_info` pointer also appears in a `__si_class_type_info` or
/// `__vmi_class_type_info` base array. Without this check such a slot becomes a
/// bogus vtable whose header can coincide with a real vtable's first slot, which
/// then truncated that class's slot run to zero. A real vtable has a small
/// offset-to-top and at least one virtual function slot that is either
/// relocated or points into `.text`.
fn is_plausible_vtable(
    map: &crate::sections::SectionMap,
    image: &[u8],
    relocation_targets: &RelocationIndex,
    vtable_address: u64,
    type_info_slot: u64,
) -> bool {
    // The offset-to-top word is plain data even in a RELA build, so the
    // in-place value is authoritative. Consulting the relocation table here
    // accepted any address whose following slot happened to hold a relocated
    // `type_info` pointer, which produced vtable groups with an offset in the
    // tens of millions.
    let Some(offset_to_top) = crate::internal::read_at(map, image, vtable_address) else {
        return false;
    };
    let offset_to_top = offset_to_top as i64;
    let plausible_top =
        offset_to_top == 0 || (offset_to_top % 8 == 0 && offset_to_top.unsigned_abs() <= (1 << 20));
    if !plausible_top {
        return false;
    }
    // An offset-to-top of zero is only credible when the word is really data.
    // A zeroed word that carries a relocation is a pointer the loader will fill
    // in, so the pair below it is some other structure, not a vtable header.
    if offset_to_top == 0
        && relocation_targets
            .target_of(vtable_address)
            .is_some_and(|target| target != 0)
    {
        return false;
    }
    let first_slot = type_info_slot.saturating_add(SLOT_BYTES);
    if relocation_targets.target_of(first_slot).is_some() {
        return true;
    }
    crate::internal::read_at(map, image, first_slot)
        .is_some_and(|target| crate::internal::is_executable(map, target))
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
    relocation_targets: &RelocationIndex,
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
    let name = relocation_targets
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

    let end = bytes[start..]
        .iter()
        .position(|byte| *byte == 0)
        .map(|offset| start + offset)?;
    let mangled = std::str::from_utf8(&bytes[start..end]).ok()?;
    if mangled.len() > MAX_TYPE_NAME_BYTES * 4 {
        return None;
    }
    let name = demangle_type_name(mangled)?;
    Some((name, mangled.to_string()))
}

/// Demangles the type name a `type_info` object points at.
///
/// Two forms occur in practice: a simple `<length><name>` such as
/// `14ClientInstance`, and the nested `N...E` form used by namespaced classes
/// such as `N6icu_6913BreakIteratorE`. Rejecting the nested form silently
/// dropped every namespaced class, which on a real library meant no vtables at
/// all.
fn demangle_type_name(text: &str) -> Option<String> {
    if let Some(rest) = text.strip_prefix('N') {
        let mut rest = rest;
        let mut parts: Vec<&str> = Vec::new();
        loop {
            let bytes = rest.as_bytes();
            let first = *bytes.first()?;
            match first {
                b'E' => break,
                // Template arguments do not change the class name.
                b'I' => break,
                b'K' | b'V' | b'r' => rest = &rest[1..],
                b'S' => {
                    if let Some(after) = rest.strip_prefix("St") {
                        parts.push("std");
                        rest = after;
                    } else {
                        // Substitution: skip `S` and its one-character key.
                        rest = &rest[1..];
                        if let Some(next) = rest.as_bytes().first() {
                            if next.is_ascii_alphanumeric() || *next == b'_' {
                                rest = &rest[1..];
                            }
                        }
                    }
                }
                b'0'..=b'9' => {
                    let (length, digits) = read_length_prefix(bytes)?;
                    if length == 0 || length > MAX_TYPE_NAME_BYTES || digits + length > rest.len() {
                        return None;
                    }
                    let part = &rest[digits..digits + length];
                    if !part.as_bytes().iter().all(|byte| is_name_byte(*byte)) {
                        return None;
                    }
                    parts.push(part);
                    rest = &rest[digits + length..];
                }
                _ => return None,
            }
        }
        if parts.is_empty() {
            None
        } else {
            Some(parts.join("::"))
        }
    } else if let Some(rest) = text.strip_prefix("St") {
        let (length, digits) = read_length_prefix(rest.as_bytes())?;
        if length == 0 || length > MAX_TYPE_NAME_BYTES || digits + length > rest.len() {
            return None;
        }
        let part = &rest[digits..digits + length];
        if !part.as_bytes().iter().all(|byte| is_name_byte(*byte)) {
            return None;
        }
        Some(format!("std::{part}"))
    } else {
        let (length, digits) = read_length_prefix(text.as_bytes())?;
        if length == 0 || length > MAX_TYPE_NAME_BYTES || digits + length > text.len() {
            return None;
        }
        let part = &text[digits..digits + length];
        if !part.as_bytes().iter().all(|byte| is_name_byte(*byte)) {
            return None;
        }
        Some(part.to_string())
    }
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

/// One slot address and the target it resolves to.
///
/// A plain pair rather than a struct: this is the entry the index sorts and
/// binary-searches, and it is the largest structure the resolver holds.
type SlotTarget = (u64, u64);

/// A sorted slot-address-to-target index over `.rela.dyn`.
///
/// A large library has over a million relocations. Holding them as a flat
/// `Vec<Relocation>` and then copying the relevant ones into an index keeps
/// every entry resident twice, which is 40 MiB or more of transient heap on a
/// real library — enough to kill the app on a device that is low on memory.
/// This type is therefore built directly from a streaming pass over
/// `.rela.dyn`, so only the sorted index is ever materialised.
pub(crate) struct RelocationIndex {
    entries: Vec<SlotTarget>,
    /// Number of `.rela.dyn` entries the stream visited.
    examined: usize,
    /// Byte ranges of the relocation tables, in file coordinates.
    ///
    /// The tables are dead once the index is built, so the caller offers these
    /// for page release before the slot scan faults in `.data.rel.ro`.
    ranges: Vec<(usize, usize)>,
}

#[cfg(test)]
impl RelocationIndex {
    /// Builds a tiny index directly, for tests that only need slot targets.
    pub(crate) fn for_tests(mut entries: Vec<(u64, u64)>) -> Self {
        entries.sort_unstable();
        Self {
            entries,
            examined: 0,
            ranges: Vec::new(),
        }
    }
}

impl RelocationIndex {
    /// Builds the index by streaming every `SHT_RELA` section.
    ///
    /// Two relocation kinds matter. `R_AARCH64_RELATIVE` means "store the
    /// address the linker computed for this slot", which is the addend; the
    /// loader adds the image base when it maps the library. `R_AARCH64_ABS64`
    /// stores an absolute address, which the addend already carries. Either way
    /// the addend is the virtual address the slot will hold. Every other kind is
    /// dropped rather than guessed at.
    ///
    /// Entries are read in bounded blocks straight out of the mapping, so a
    /// library with millions of relocations costs one fixed buffer plus the
    /// index itself.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::OutOfMemory`] when the index cannot grow, rather
    /// than letting the allocator abort the process.
    pub(crate) fn build(image: &[u8]) -> CoreResult<Self> {
        let headers = crate::elf::parse_section_headers(image)?;
        // A supported relocation is one of two kinds out of many, so the index
        // is a fraction of the table; this is only a starting capacity, not a
        // commitment.
        let mut entries: Vec<SlotTarget> = Vec::new();
        let mut examined = 0usize;
        let mut ranges: Vec<(usize, usize)> = Vec::new();

        for header in &headers {
            if header.sh_type != SHT_RELA {
                continue;
            }
            let start = header.sh_offset as usize;
            let end = start.saturating_add(header.sh_size as usize);
            let Some(body) = image.get(start..end) else {
                continue;
            };
            ranges.push((start, end));

            const ENTRY_BYTES: usize = 24;
            // Read in blocks so the loop does not touch one page per entry; the
            // block is a fixed 24 KiB regardless of the table size.
            const BLOCK_ENTRIES: usize = 1024;
            let total = body.len() / ENTRY_BYTES;
            examined += total;
            // Every supported entry may be kept, so reserve for the worst case
            // in one fallible step. `try_reserve` reports a failure instead of
            // aborting, which is what makes a low-memory device survivable.
            entries
                .try_reserve(total)
                .map_err(|error| CoreError::OutOfMemory(format!("relocation index: {error}")))?;

            for block in 0..total.div_ceil(BLOCK_ENTRIES) {
                let first = block * BLOCK_ENTRIES;
                let last = ((block + 1) * BLOCK_ENTRIES).min(total);
                for index in first..last {
                    let base = index * ENTRY_BYTES;
                    let info = read_u64(body, base + 8);
                    let kind = (info & 0xffff_ffff) as u32;
                    if !is_supported_relocation(kind) {
                        continue;
                    }
                    let offset = read_u64(body, base);
                    let addend = read_u64(body, base + 16);
                    entries.push((offset, addend));
                }
            }
        }

        Ok(Self::from_entries(entries, examined, ranges))
    }

    /// Sorts and deduplicates `entries` into an index.
    fn from_entries(
        mut entries: Vec<SlotTarget>,
        examined: usize,
        ranges: Vec<(usize, usize)>,
    ) -> Self {
        entries.sort_unstable();
        entries.dedup();
        Self {
            entries,
            examined,
            ranges,
        }
    }

    /// Returns the address the slot at `slot_address` resolves to.
    ///
    /// # Returns
    ///
    /// The target, or `None` when the slot is not relocated by a supported kind.
    /// The `(slot address, target)` pairs, sorted by slot address.
    ///
    /// Exposed so a caller that needs to walk the table (the native-method
    /// scan) does not have to rebuild it.
    pub(crate) fn entries(&self) -> &[SlotTarget] {
        &self.entries
    }

    pub(crate) fn target_of(&self, slot_address: u64) -> Option<u64> {
        let index = self
            .entries
            .binary_search_by_key(&slot_address, |(offset, _)| *offset)
            .ok()?;
        Some(self.entries[index].1)
    }

    /// Number of relocations the index kept.
    #[cfg(test)]
    fn len(&self) -> usize {
        self.entries.len()
    }

    /// Number of `.rela.dyn` entries the stream visited.
    pub(crate) fn examined(&self) -> usize {
        self.examined
    }

    /// Offers the now-dead relocation tables back to the kernel.
    ///
    /// Called once the index is built and the tables are no longer read, so the
    /// file-backed pages they occupy do not stay resident while the slot scan
    /// faults in `.data.rel.ro`.
    fn release_source_pages(&self, releaser: &dyn crate::scanner::PageReleaser) {
        for (start, end) in &self.ranges {
            releaser.release(*start, *end);
        }
    }

    /// Whether the index holds nothing, which is the signal to direct-scan.
    pub(crate) fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// Whether a relocation kind is one the resolver trusts.
///
/// `R_AARCH64_RELATIVE` and `R_AARCH64_ABS64` both end up holding the virtual
/// address the slot will carry once the library is loaded. `GLOB_DAT` and
/// `JUMP_SLOT` mark an imported symbol: their addend is usually zero because
/// the loader fills the slot from the GOT, so the slot is kept with a zero
/// target and treated as an unresolved import rather than as the end of the
/// vtable.
fn is_supported_relocation(kind: u32) -> bool {
    kind == R_AARCH64_RELATIVE
        || kind == R_AARCH64_ABS64
        || kind == R_AARCH64_GLOB_DAT
        || kind == R_AARCH64_JUMP_SLOT
}

/// Reads a little-endian `u64` at `offset`, or zero when it runs off the end.
pub(crate) fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    match bytes.get(offset..offset + 8) {
        Some(slice) => u64::from_le_bytes([
            slice[0], slice[1], slice[2], slice[3], slice[4], slice[5], slice[6], slice[7],
        ]),
        None => 0,
    }
}

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
        // Exercised through the streaming path so the filter, the sort and the
        // lookup are all covered by the same fixture.
        let entries = vec![(0x1000u64, 0x2000u64), (0x1008, 0x3000), (0x1010, 0x4000)];
        let index = RelocationIndex::from_entries(entries, 3, Vec::new());
        assert_eq!(index.target_of(0x1000), Some(0x2000));
        assert_eq!(index.target_of(0x1008), Some(0x3000));
        // The slot is present but its kind was unsupported, so the resolver must
        // not have kept it.
        assert_eq!(index.target_of(0x1010), Some(0x4000));
        assert_eq!(index.target_of(0x9999), None);
        assert_eq!(index.len(), 3);
        assert_eq!(index.examined(), 3);
    }

    #[test]
    fn only_pointer_kinds_are_trusted() {
        assert!(is_supported_relocation(R_AARCH64_RELATIVE));
        assert!(is_supported_relocation(R_AARCH64_ABS64));
        // Imported symbols are kept so an unresolved slot does not end a vtable.
        assert!(is_supported_relocation(R_AARCH64_GLOB_DAT));
        assert!(is_supported_relocation(R_AARCH64_JUMP_SLOT));
        // A TLS or unrelated kind carries a different meaning entirely.
        assert!(!is_supported_relocation(999));
        assert!(!is_supported_relocation(0));
    }

    #[test]
    fn duplicate_slots_collapse_to_one_entry() {
        let index = RelocationIndex::from_entries(
            vec![(0x2000, 0x10), (0x1000, 0x20), (0x2000, 0x10)],
            3,
            Vec::new(),
        );
        assert_eq!(index.len(), 2);
        assert_eq!(index.target_of(0x1000), Some(0x20));
        assert_eq!(index.target_of(0x2000), Some(0x10));
    }

    #[test]
    fn an_empty_stream_is_the_direct_scan_signal() {
        let index = RelocationIndex::from_entries(Vec::new(), 0, Vec::new());
        assert!(index.is_empty());
        assert_eq!(index.len(), 0);
    }

    #[test]
    fn reading_off_the_end_yields_zero() {
        assert_eq!(read_u64(&[1, 2, 3], 0), 0);
        assert_eq!(read_u64(&[], 0), 0);
    }

    #[test]
    fn relocation_sections_are_recognised() {
        // The index is built by section *type*, not name, but the name is what
        // the rest of the engine reasons about, so it must classify as an
        // ordinary section rather than one of the three scanned kinds.
        use crate::sections::{
            SectionKind, DATA_REL_RO_SECTION, RELA_DYN_SECTION, RODATA_SECTION, TEXT_SECTION,
        };

        assert_eq!(RELA_DYN_SECTION, ".rela.dyn");
        assert_eq!(SectionKind::from_name(RELA_DYN_SECTION), SectionKind::Other);
        assert_ne!(RELA_DYN_SECTION, TEXT_SECTION);
        assert_ne!(RELA_DYN_SECTION, RODATA_SECTION);
        assert_ne!(RELA_DYN_SECTION, DATA_REL_RO_SECTION);
    }

    #[test]
    fn demangles_simple_and_nested_type_names() {
        assert_eq!(
            demangle_type_name("14ClientInstance").as_deref(),
            Some("ClientInstance")
        );
        assert_eq!(
            demangle_type_name("N6icu_6913BreakIteratorE").as_deref(),
            Some("icu_69::BreakIterator")
        );
        assert_eq!(
            demangle_type_name("N10__cxxabiv117__class_type_infoE").as_deref(),
            Some("__cxxabiv1::__class_type_info")
        );
        assert_eq!(
            demangle_type_name("St12length_error").as_deref(),
            Some("std::length_error")
        );
    }

    #[test]
    fn template_arguments_are_dropped_from_the_class_name() {
        assert_eq!(
            demangle_type_name("NSt6__ndk19basic_iosIcNS_11char_traitsIcEEEE").as_deref(),
            Some("std::__ndk1::basic_ios")
        );
    }

    /// Dumps the raw context of three classes that came back with no slots in
    /// the 26.60 output. Ignored by default because it needs the real library:
    ///
    /// ```text
    /// BEDROCK_LIB=/path/libminecraftpe.so cargo test -- --ignored dump_vtable_context
    /// ```
    #[test]
    #[ignore = "needs a real libminecraftpe.so; set BEDROCK_LIB"]
    fn dump_vtable_context() {
        let Ok(path) = std::env::var("BEDROCK_LIB") else {
            eprintln!("BEDROCK_LIB is not set");
            return;
        };
        let image = std::fs::read(&path).expect("read library");
        let map = crate::sections::build_section_map(&image).expect("section map");
        let relocations = RelocationIndex::build(&image).expect("relocations");
        let vtables = resolve_vtables(&image).expect("vtables");
        let read = |address: u64| -> Option<u64> {
            let section = map
                .sections
                .iter()
                .find(|section| {
                    section.allocated
                        && address >= section.address
                        && address + 8 <= section.address + section.size
                })
                .or_else(|| {
                    (address >= map.text.address && address + 8 <= map.text.address + map.text.size)
                        .then_some(&map.text)
                })?;
            let offset = section.offset_of(address)? as usize;
            let bytes = image.get(offset..offset + 8)?;
            Some(read_u64(bytes, 0))
        };
        for name in ["Actor", "Level", "Player"] {
            let Some(vtable) = vtables.get(name) else {
                println!("{name}: not resolved");
                continue;
            };
            println!(
                "{name}: vtable={:#x} typeInfo={:#x}",
                vtable.vtable_address, vtable.type_info_address
            );
            for delta in [-16i64, -8] {
                let address = vtable.vtable_address.wrapping_add_signed(delta);
                println!("  header[{delta}]: {:#x}", read(address).unwrap_or(0));
            }
            for slot in 0..8u64 {
                let address = vtable.vtable_address + 16 + slot * 8;
                println!(
                    "  slot {slot} @{address:#x}: raw={:#x} reloc={:?}",
                    read(address).unwrap_or(0),
                    relocations.target_of(address)
                );
            }
        }
    }

    #[test]
    fn malformed_type_names_are_rejected() {
        assert!(demangle_type_name("").is_none());
        assert!(demangle_type_name("0").is_none());
        assert!(demangle_type_name("99short").is_none());
        assert!(demangle_type_name("N6icu_69").is_none());
    }
}
