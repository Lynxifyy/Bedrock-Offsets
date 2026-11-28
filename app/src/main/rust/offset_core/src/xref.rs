//! Cross-references recovered from `.text`.
//!
//! The engine knows where every function starts and ends from `.eh_frame`, but
//! a name alone does not let a modder move: they need "who calls this", "what
//! string does this use", "which vtable does this construct". This module walks
//! the instructions of each function once and records those edges.
//!
//! Only the forms that produce a resolvable edge are decoded. Everything else
//! (conditional branches inside a function body) is skipped by length, so an
//! unknown instruction cannot desynchronise the walk: AArch64 is fixed-width,
//! and the FDE bounds come from the unwind tables rather than from decoding.
//!
//! # Memory
//!
//! A large Bedrock library has close to 800k functions and tens of millions of
//! edges. Keeping one `Vec` per function plus a global `BTreeMap` for each
//! direction made the pass cost several hundred megabytes and aborted the
//! process on a mid-range phone. Edges are therefore stored in two flat arenas
//! and a function only carries a span into them. The reverse direction ("who
//! references X") is not materialised for every address: a caller passes the
//! handful of targets it cares about and gets a compact sorted edge slice back,
//! so the cost scales with the query, not with the library.

use std::collections::HashSet;

use crate::error::{CoreError, CoreResult};
use crate::internal::is_executable;
use crate::scanner::ScanControl;
use crate::sections::SectionMap;

/// A direct call edge.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Call {
    /// Address of the `BL` instruction.
    pub site: u64,
    /// Address of the callee.
    pub target: u64,
}

/// A span of edges inside [`XrefTable`]'s flat arena.
///
/// `start` and `len` index the arena the [`FunctionXrefs`] belongs to; the pair
/// is two `u32`s so a function costs eight bytes instead of the twenty-four a
/// `Vec` header takes.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct EdgeSpan {
    start: u32,
    len: u32,
}

impl EdgeSpan {
    /// Number of edges in the span.
    pub fn len(&self) -> usize {
        self.len as usize
    }

    /// Whether the span is empty.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    fn slice<'a>(&self, arena: &'a [u64]) -> &'a [u64] {
        let start = self.start as usize;
        &arena[start..start + self.len as usize]
    }
}

/// What one function references.
///
/// `calls` and `data_refs` are spans into [`XrefTable`]; read them through
/// [`XrefTable::calls_of`] and [`XrefTable::data_refs_of`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FunctionXrefs {
    /// Address of the function, as given by the FDE list.
    pub address: u64,
    /// `BL` targets, sorted and deduplicated.
    pub calls: EdgeSpan,
    /// Addresses built by `ADRP`/`ADR`+`ADD`/`LDR` that are not code: data
    /// objects, vtables and string literals all appear here.
    pub data_refs: EdgeSpan,
    /// First direct call site in address order. Only the first is kept: the
    /// anchor search uses it as a locator and the full list is already in the
    /// `calls` arena.
    pub first_call_site: Option<Call>,
    /// Whether the function ends in `BR`/`RET`, which is how tail calls and
    /// thunks are recognised.
    pub has_indirect_exit: bool,
}

/// The whole cross-reference table.
#[derive(Debug, Clone, Default)]
pub struct XrefTable {
    /// Per-function edges, ordered by address.
    pub functions: Vec<FunctionXrefs>,
    calls: Vec<u64>,
    data_refs: Vec<u64>,
}

impl XrefTable {
    /// The callees of `function`, sorted and deduplicated.
    pub fn calls_of(&self, function: &FunctionXrefs) -> &[u64] {
        function.calls.slice(&self.calls)
    }

    /// The addresses `function` references, sorted and deduplicated.
    pub fn data_refs_of(&self, function: &FunctionXrefs) -> &[u64] {
        function.data_refs.slice(&self.data_refs)
    }

    /// The function starting at `address`, when the table has one.
    ///
    /// `functions` is sorted by address, so this is a binary search rather than
    /// the 800k-entry side map the naming pass used to build.
    pub fn function_at(&self, address: u64) -> Option<&FunctionXrefs> {
        self.functions
            .binary_search_by_key(&address, |function| function.address)
            .ok()
            .map(|index| &self.functions[index])
    }

    /// Builds the reverse index for call edges into `wanted`.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::OutOfMemory`] when the index cannot grow.
    pub fn callers_of(&self, wanted: &HashSet<u64>) -> CoreResult<ReverseIndex> {
        self.reverse_index(wanted, true)
    }

    /// Builds the reverse index for data references into `wanted`.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::OutOfMemory`] when the index cannot grow.
    pub fn referrers_of(&self, wanted: &HashSet<u64>) -> CoreResult<ReverseIndex> {
        self.reverse_index(wanted, false)
    }

    fn reverse_index(&self, wanted: &HashSet<u64>, calls: bool) -> CoreResult<ReverseIndex> {
        let mut edges: Vec<(u64, u64)> = Vec::new();
        if wanted.is_empty() {
            return Ok(ReverseIndex { edges });
        }
        for function in &self.functions {
            let targets = if calls {
                self.calls_of(function)
            } else {
                self.data_refs_of(function)
            };
            for target in targets {
                if wanted.contains(target) {
                    if edges.len() == edges.capacity() {
                        edges.try_reserve(ARENA_GROWTH).map_err(|error| {
                            CoreError::OutOfMemory(format!("xref reverse index: {error}"))
                        })?;
                    }
                    edges.push((*target, function.address));
                }
            }
        }
        edges.sort_unstable();
        edges.dedup();
        Ok(ReverseIndex { edges })
    }
}

/// A compact reverse index: `(target, source)` pairs sorted by target.
///
/// Built only for the addresses a caller asks about, so a library with tens of
/// millions of edges never has a map entry per edge.
#[derive(Debug, Clone, Default)]
pub struct ReverseIndex {
    edges: Vec<(u64, u64)>,
}

impl ReverseIndex {
    /// The functions that reference `target`, sorted and deduplicated.
    pub fn sources(&self, target: u64) -> Vec<u64> {
        let start = self.edges.partition_point(|(value, _)| *value < target);
        let end = self.edges.partition_point(|(value, _)| *value <= target);
        let mut sources: Vec<u64> = self.edges[start..end]
            .iter()
            .map(|(_, source)| *source)
            .collect();
        sources.dedup();
        sources
    }

    /// Number of edges the index kept.
    pub fn len(&self) -> usize {
        self.edges.len()
    }

    /// Whether the index is empty.
    pub fn is_empty(&self) -> bool {
        self.edges.is_empty()
    }
}

const BL: u32 = 0x9400_0000;
const B: u32 = 0x1400_0000;
const ADRP: u32 = 0x9000_0000;
const ADR: u32 = 0x1000_0000;
const ADD_IMMEDIATE: u32 = 0x1100_0000;
const SUB_IMMEDIATE: u32 = 0x5100_0000;
const MOV_REGISTER: u32 = 0x2A00_03E0;
const LDR_LITERAL: u32 = 0x1800_0000;
const LOAD_STORE_UNSIGNED: u32 = 0x3900_0000;
const LOAD_STORE_UNSCALED: u32 = 0x3800_0000;
const LOAD_STORE_PAIR: u32 = 0x2800_0000;
const RET: u32 = 0xD65F_0000;
const BR: u32 = 0xD61F_0000;
const BLR: u32 = 0xD63F_0000;

/// Edges to reserve at a time while filling an arena.
const ARENA_GROWTH: usize = 8192;

/// Scans every function for cross-references.
///
/// `functions` holds `(start, size)` pairs from the FDE list; a function the
/// unwind tables do not describe is not walked, because there is no trustworthy
/// end address.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` is cancelled, and
/// [`CoreError::OutOfMemory`] when an arena cannot grow.
pub fn scan(
    map: &SectionMap,
    image: &[u8],
    functions: &[(u64, u64)],
    control: &ScanControl,
) -> CoreResult<XrefTable> {
    let mut table = XrefTable::default();
    table
        .functions
        .try_reserve(functions.len())
        .map_err(|error| CoreError::OutOfMemory(format!("xref functions: {error}")))?;

    for (index, &(start, size)) in functions.iter().enumerate() {
        if index % 4096 == 0 && control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let end = start.saturating_add(size);
        let mut xrefs = FunctionXrefs {
            address: start,
            ..FunctionXrefs::default()
        };
        if !is_executable(map, start) || end <= start {
            table.functions.push(xrefs);
            continue;
        }
        scan_function(map, image, start, end, &mut xrefs, &mut table)?;
        table.functions.push(xrefs);
    }
    Ok(table)
}

/// Scans a single function body.
///
/// Used by the on-demand inspector so opening one function does not pay for a
/// whole-library pass.
///
/// # Errors
///
/// Returns [`CoreError::OutOfMemory`] when an arena cannot grow.
pub fn scan_one(map: &SectionMap, image: &[u8], start: u64, size: u64) -> CoreResult<XrefTable> {
    let mut table = XrefTable::default();
    table
        .functions
        .try_reserve(1)
        .map_err(|error| CoreError::OutOfMemory(format!("xref functions: {error}")))?;
    let mut xrefs = FunctionXrefs {
        address: start,
        ..FunctionXrefs::default()
    };
    let end = start.saturating_add(size);
    if is_executable(map, start) && end > start {
        scan_function(map, image, start, end, &mut xrefs, &mut table)?;
    }
    table.functions.push(xrefs);
    Ok(table)
}

/// Finds every function whose body directly calls `target`.
///
/// Unlike [`XrefTable::callers_of`], this never materialises the edge table:
/// only a call whose target matches is kept, so the inspector can answer "who
/// calls this" on a full library without a second copy of every edge.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` is cancelled.
pub fn callers_of(
    map: &SectionMap,
    image: &[u8],
    functions: &[(u64, u64)],
    target: u64,
    control: &ScanControl,
) -> CoreResult<Vec<u64>> {
    let mut callers: Vec<u64> = Vec::new();
    for (index, &(start, size)) in functions.iter().enumerate() {
        if index % 4096 == 0 && control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let end = start.saturating_add(size);
        if !is_executable(map, start) || end <= start {
            continue;
        }
        let mut offset = start;
        while offset < end {
            let Some(word) = read_word(map, image, offset) else {
                break;
            };
            let opcode = word & 0xFC00_0000;
            if opcode == BL || opcode == B {
                let destination = branch_target(offset, word & 0x03FF_FFFF, 26);
                let outside = destination < start || destination >= end;
                if destination == target && (opcode == BL || outside) {
                    callers.push(start);
                    break;
                }
            }
            offset = offset.saturating_add(4);
        }
    }
    callers.sort_unstable();
    callers.dedup();
    Ok(callers)
}

/// Walks one function body and records its edges into `table`'s arenas.
fn scan_function(
    map: &SectionMap,
    image: &[u8],
    start: u64,
    end: u64,
    xrefs: &mut FunctionXrefs,
    table: &mut XrefTable,
) -> CoreResult<()> {
    // ADRP pairs a page base with a later ADD/LDR; the register number is the
    // link between the two instructions.
    let mut page: [Option<u64>; 32] = [None; 32];
    let calls_start = table.calls.len() as u32;
    let refs_start = table.data_refs.len() as u32;
    let mut offset = start;
    while offset < end {
        let Some(word) = read_word(map, image, offset) else {
            break;
        };
        let field = |shift: u32, bits: u32| (word >> shift) & ((1u32 << bits) - 1);
        let rd = field(0, 5) as usize;
        let rn = field(5, 5) as usize;
        match word & 0xFC00_0000 {
            op if op == BL => {
                let target = branch_target(offset, word & 0x03FF_FFFF, 26);
                push_edge(&mut table.calls, target)?;
                xrefs.first_call_site.get_or_insert(Call {
                    site: offset,
                    target,
                });
                clobber_caller_saved(&mut page);
            }
            op if op == B => {
                let target = branch_target(offset, word & 0x03FF_FFFF, 26);
                // A branch outside this function is a tail call.
                if target < start || target >= end {
                    push_edge(&mut table.calls, target)?;
                    xrefs.first_call_site.get_or_insert(Call {
                        site: offset,
                        target,
                    });
                }
                clobber_caller_saved(&mut page);
            }
            _ => {
                if word & 0x9F00_0000 == ADRP {
                    let immlo = field(29, 2) as i64;
                    let immhi = field(5, 19) as i64;
                    let delta = ((immhi << 2) | immlo) << 12;
                    let delta = sign_extend(delta, 33);
                    page[rd] = Some(((offset & !0xFFF) as i64).wrapping_add(delta) as u64);
                } else if word & 0x9F00_0000 == ADR {
                    let immlo = field(29, 2) as i64;
                    let immhi = field(5, 19) as i64;
                    let delta = sign_extend((immhi << 2) | immlo, 21);
                    let target = (offset as i64).wrapping_add(delta) as u64;
                    // ADR materialises a full address; it is a reference in its
                    // own right and a base for a later ADD.
                    push_edge(&mut table.data_refs, target)?;
                    page[rd] = Some(target);
                } else if word & 0x7F80_0000 == ADD_IMMEDIATE {
                    let shift = field(22, 1) * 12;
                    let imm = (field(10, 12) as u64) << shift;
                    if let Some(base) = page[rn] {
                        let target = base.wrapping_add(imm);
                        // A compiler routinely computes the address into a
                        // different register than the ADRP target
                        // (`adrp x8, page; add x0, x8, #off`), so the
                        // destination must not be required to equal the source.
                        push_edge(&mut table.data_refs, target)?;
                        page[rd] = Some(target);
                    } else if rd != rn {
                        page[rd] = None;
                    }
                } else if word & 0x7F80_0000 == SUB_IMMEDIATE {
                    page[rd] = None;
                } else if word & 0x7FE0_FFE0 == MOV_REGISTER {
                    let rm = field(16, 5) as usize;
                    page[rd] = page[rm];
                } else if word & 0x3B00_0000 == LDR_LITERAL {
                    let imm = sign_extend((field(5, 19) as i64) << 2, 21);
                    let target = (offset as i64).wrapping_add(imm) as u64;
                    push_edge(&mut table.data_refs, target)?;
                    page[rd] = None;
                } else if word & 0x3B00_0000 == LOAD_STORE_UNSIGNED {
                    let size_field = field(30, 2);
                    let scale = 1u64 << size_field;
                    let imm = (field(10, 12) as u64) * scale;
                    let opc = field(22, 2);
                    if let Some(base) = page[rn] {
                        push_edge(&mut table.data_refs, base.wrapping_add(imm))?;
                    }
                    if opc != 0 {
                        // A load overwrites its destination register, so the
                        // page it used to hold is no longer valid.
                        page[rd] = None;
                    }
                } else if word & 0x3B00_0000 == LOAD_STORE_UNSCALED {
                    let imm = sign_extend(field(12, 9) as i64, 9);
                    let opc = field(22, 2);
                    if let Some(base) = page[rn] {
                        push_edge(&mut table.data_refs, (base as i64).wrapping_add(imm) as u64)?;
                    }
                    if opc != 0 {
                        page[rd] = None;
                    }
                } else if word & 0x3A00_0000 == LOAD_STORE_PAIR {
                    let imm = sign_extend(field(15, 7) as i64, 7) * 8;
                    if let Some(base) = page[rn] {
                        push_edge(&mut table.data_refs, (base as i64).wrapping_add(imm) as u64)?;
                    }
                    if word & 0x0040_0000 != 0 {
                        // Load pair writes both destination registers.
                        page[rd] = None;
                        page[field(10, 5) as usize] = None;
                    }
                } else if word & 0xFFFF_FC1F == BR || word & 0xFFFF_FC1F == BLR {
                    // Register is not resolved back to a virtual address
                    // without the register's definition; recorded as
                    // indirect so thunk detection can see it.
                    xrefs.has_indirect_exit = true;
                    if word & 0xFFFF_FC1F == BLR {
                        clobber_caller_saved(&mut page);
                    }
                } else if word & 0xFFFF_FC1F == RET {
                    xrefs.has_indirect_exit = true;
                } else if writes_gpr(word) {
                    // Any other data-processing instruction overwrites its
                    // destination, so a stale page base must not survive it.
                    page[rd] = None;
                }
            }
        }
        offset = offset.saturating_add(4);
    }

    let calls_end = table.calls.len();
    let refs_end = table.data_refs.len();
    sort_dedup(&mut table.calls, calls_start as usize, calls_end);
    sort_dedup(&mut table.data_refs, refs_start as usize, refs_end);
    xrefs.calls = EdgeSpan {
        start: calls_start,
        len: table.calls.len() as u32 - calls_start,
    };
    xrefs.data_refs = EdgeSpan {
        start: refs_start,
        len: table.data_refs.len() as u32 - refs_start,
    };
    Ok(())
}

/// Sorts and deduplicates `arena[start..end]`, then truncates to the new end.
fn sort_dedup(arena: &mut Vec<u64>, start: usize, end: usize) {
    arena[start..end].sort_unstable();
    if end <= start + 1 {
        return;
    }
    let mut write = start + 1;
    for read in start + 1..end {
        if arena[read] != arena[write - 1] {
            arena[write] = arena[read];
            write += 1;
        }
    }
    arena.truncate(write);
}

/// Appends one edge, growing the arena fallibly.
fn push_edge(arena: &mut Vec<u64>, value: u64) -> CoreResult<()> {
    if arena.len() == arena.capacity() {
        arena
            .try_reserve(ARENA_GROWTH)
            .map_err(|error| CoreError::OutOfMemory(format!("xref arena: {error}")))?;
    }
    arena.push(value);
    Ok(())
}

/// Clears the caller-saved registers a `BL`/`BLR` may overwrite.
fn clobber_caller_saved(page: &mut [Option<u64>; 32]) {
    for slot in page.iter_mut().take(19) {
        *slot = None;
    }
}

/// Whether a data-processing instruction writes a general-purpose register.
///
/// Bits 28:25 select the encoding group: `1000` is data processing (immediate)
/// and `0101` is data processing (register). Both put the destination in bits
/// 4:0. Loads and stores are group `1100` and are handled separately because
/// their destination semantics depend on the load/store direction.
pub(crate) fn writes_gpr(word: u32) -> bool {
    let group = (word >> 25) & 0xF;
    group == 0b1000 || group == 0b0101
}

/// Reads one instruction word, honouring section file offsets.
fn read_word(map: &SectionMap, image: &[u8], address: u64) -> Option<u32> {
    let offset = crate::internal::file_offset_of(map, address)? as usize;
    let bytes = image.get(offset..offset + 4)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

pub(crate) fn branch_target(pc: u64, immediate: u32, bits: u32) -> u64 {
    let shift = 2;
    let imm = sign_extend((immediate as i64) << shift, bits + shift);
    (pc as i64).wrapping_add(imm) as u64
}

fn sign_extend(value: i64, bits: u32) -> i64 {
    let shift = 64 - bits;
    (value << shift) >> shift
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sections::{build_section_map, SectionKind};

    /// Builds a tiny library with one executable section at 0x1000.
    fn image_with(words: &[(u64, u32)]) -> (Vec<u8>, SectionMap) {
        let mut image = vec![0u8; 0x3000];
        // ELF header: 64-bit, little endian, AArch64.
        image[0..4].copy_from_slice(b"\x7fELF");
        image[4] = 2;
        image[5] = 1;
        image[18] = 183;
        image[19] = 0;
        // Section headers at 0x100, four entries, `.shstrtab` at 0x400.
        let names = "\0.text\0.shstrtab\0";
        image[0x400..0x400 + names.len()].copy_from_slice(names.as_bytes());
        let write_section = |image: &mut Vec<u8>,
                             index: usize,
                             name: u32,
                             kind: u32,
                             flags: u64,
                             address: u64,
                             offset: u64,
                             size: u64| {
            let base = 0x100 + index * 64;
            image[base..base + 4].copy_from_slice(&name.to_le_bytes());
            image[base + 4..base + 8].copy_from_slice(&kind.to_le_bytes());
            image[base + 8..base + 16].copy_from_slice(&flags.to_le_bytes());
            image[base + 16..base + 24].copy_from_slice(&address.to_le_bytes());
            image[base + 24..base + 32].copy_from_slice(&offset.to_le_bytes());
            image[base + 32..base + 40].copy_from_slice(&size.to_le_bytes());
        };
        // sh_flags 0x6 = SHF_ALLOC | SHF_EXECINSTR for .text.
        write_section(&mut image, 1, 1, 1, 0x6, 0x1000, 0x1000, 0x1000);
        write_section(&mut image, 2, 7, 3, 0, 0, 0x400, names.len() as u64);
        image[0x28..0x30].copy_from_slice(&0x100u64.to_le_bytes());
        image[0x3A..0x3C].copy_from_slice(&64u16.to_le_bytes());
        image[0x3C..0x3E].copy_from_slice(&4u16.to_le_bytes());
        image[0x3E..0x40].copy_from_slice(&2u16.to_le_bytes());
        for (address, word) in words {
            let offset = *address as usize;
            image[offset..offset + 4].copy_from_slice(&word.to_le_bytes());
        }
        let map = build_section_map(&image).expect("section map");
        (image, map)
    }

    /// Scans one function described by `(start, size)`.
    fn scan_one(words: &[(u64, u32)], start: u64, end: u64) -> XrefTable {
        let (image, map) = image_with(words);
        scan(&map, &image, &[(start, end - start)], &ScanControl::new()).expect("scan")
    }

    #[test]
    fn a_bl_is_recorded_as_a_callee() {
        // bl +0x40 from 0x1000 lands on 0x1040.
        let word = BL | 0x10;
        let table = scan_one(&[(0x1000, word), (0x1004, RET)], 0x1000, 0x1008);
        assert_eq!(table.calls_of(&table.functions[0]), &[0x1040]);
        assert_eq!(
            table
                .callers_of(&HashSet::from([0x1040]))
                .unwrap()
                .sources(0x1040),
            vec![0x1000]
        );
    }

    /// `adrp rd, #page_delta` — the page number is relative to the PC page.
    fn adrp(rd: u32, page_delta: i64) -> u32 {
        let value = (page_delta >> 12) as u32;
        let immlo = (value & 0x3) << 29;
        let immhi = (value >> 2) << 5;
        ADRP | immlo | immhi | rd
    }

    fn add_imm(rd: u32, rn: u32, imm: u32) -> u32 {
        ADD_IMMEDIATE | (imm << 10) | (rn << 5) | rd
    }

    #[test]
    fn an_adrp_add_pair_resolves_to_the_page_target() {
        // adrp x0, [pc-page + 0x1000] ; add x0, x0, #0x30
        let table = scan_one(
            &[
                (0x1000, adrp(0, 0x1000)),
                (0x1004, add_imm(0, 0, 0x30)),
                (0x1008, RET),
            ],
            0x1000,
            0x100C,
        );
        assert_eq!(table.data_refs_of(&table.functions[0]), &[0x2030]);
    }

    #[test]
    fn an_adrp_add_into_a_different_register_is_recorded() {
        // adrp x8, page ; add x0, x8, #0x40 — the common compiler shape.
        let table = scan_one(
            &[
                (0x1000, adrp(8, 0x1000)),
                (0x1004, add_imm(0, 8, 0x40)),
                (0x1008, RET),
            ],
            0x1000,
            0x100C,
        );
        assert_eq!(table.data_refs_of(&table.functions[0]), &[0x2040]);
    }

    #[test]
    fn an_adr_materialises_an_address_directly() {
        // adr x0, +0x30 from 0x1000 → 0x1030.
        let word = ADR | ((0x30u32 >> 2) << 5);
        let table = scan_one(&[(0x1000, word), (0x1004, RET)], 0x1000, 0x1008);
        assert_eq!(table.data_refs_of(&table.functions[0]), &[0x1030]);
    }

    #[test]
    fn a_32_bit_ldr_is_resolved() {
        // adrp x8, page ; ldr w0, [x8, #0x20]  (0xB940_0000 | ...)
        let ldr = 0xB940_0000u32 | (0x20u32 >> 2 << 10) | (8 << 5);
        let table = scan_one(
            &[(0x1000, adrp(8, 0x1000)), (0x1004, ldr), (0x1008, RET)],
            0x1000,
            0x100C,
        );
        assert_eq!(table.data_refs_of(&table.functions[0]), &[0x2020]);
    }

    #[test]
    fn a_load_clobbers_the_page_register() {
        // adrp x8, page ; ldr x8, [x8] ; add x0, x8, #0x10 — after the load x8
        // no longer holds the page, so no data reference may be produced.
        let ldr = 0xF940_0000u32 | (8 << 5) | 8;
        let table = scan_one(
            &[
                (0x1000, adrp(8, 0x1000)),
                (0x1004, ldr),
                (0x1008, add_imm(0, 8, 0x10)),
                (0x100C, RET),
            ],
            0x1000,
            0x1010,
        );
        // Only the ldr's own [x8] reference is recorded, not the stale add.
        assert_eq!(table.data_refs_of(&table.functions[0]), &[0x2000]);
    }

    #[test]
    fn a_branch_inside_the_function_is_not_a_tail_call() {
        // b +0x4 stays inside the function.
        let table = scan_one(&[(0x1000, B | 1), (0x1004, RET)], 0x1000, 0x1008);
        assert!(table.calls_of(&table.functions[0]).is_empty());
    }

    #[test]
    fn a_branch_outside_the_function_is_a_tail_call() {
        // b +0x400 leaves a 0x100-byte function.
        let table = scan_one(&[(0x1000, B | 0x100), (0x1004, RET)], 0x1000, 0x1100);
        assert_eq!(table.calls_of(&table.functions[0]), &[0x1400]);
    }

    #[test]
    fn a_ret_marks_the_function_as_indirectly_exiting() {
        let table = scan_one(&[(0x1000, RET)], 0x1000, 0x1004);
        assert!(table.functions[0].has_indirect_exit);
    }

    #[test]
    fn referencing_finds_the_function_that_uses_an_address() {
        let table = scan_one(
            &[
                (0x1000, adrp(0, 0x1000)),
                (0x1004, add_imm(0, 0, 0x40)),
                (0x1008, RET),
            ],
            0x1000,
            0x100C,
        );
        let wanted = HashSet::from([0x2040]);
        assert_eq!(
            table.referrers_of(&wanted).unwrap().sources(0x2040),
            vec![0x1000]
        );
        let absent = HashSet::from([0x2044]);
        assert!(table
            .referrers_of(&absent)
            .unwrap()
            .sources(0x2044)
            .is_empty());
        let _ = SectionKind::Text;
    }
}
