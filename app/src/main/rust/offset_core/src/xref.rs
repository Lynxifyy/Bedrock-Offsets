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

use std::collections::BTreeMap;

use crate::internal::is_executable;
use crate::sections::SectionMap;

/// A direct call edge.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Call {
    /// Address of the `BL` instruction.
    pub site: u64,
    /// Address of the callee.
    pub target: u64,
}

/// What one function references.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FunctionXrefs {
    /// Address of the function, as given by the FDE list.
    pub address: u64,
    /// `BL` targets, sorted and deduplicated.
    pub calls: Vec<u64>,
    /// Addresses built by `ADRP`+`ADD`/`LDR` that are not code: data objects,
    /// vtables and string literals all appear here.
    pub data_refs: Vec<u64>,
    /// Every direct call site, for "who calls X" from the caller's side.
    pub call_sites: Vec<Call>,
    /// Whether the function ends in `BR`/`RET`, which is how tail calls and
    /// thunks are recognised.
    pub has_indirect_exit: bool,
}

impl FunctionXrefs {
    /// Number of edges recorded for this function.
    pub fn edge_count(&self) -> usize {
        self.calls.len() + self.data_refs.len()
    }
}

/// The whole cross-reference table.
#[derive(Debug, Clone, Default)]
pub struct XrefTable {
    /// Per-function edges, ordered by address.
    pub functions: Vec<FunctionXrefs>,
    /// Callee address to the functions that call it.
    pub callers: BTreeMap<u64, Vec<u64>>,
    /// Referenced address to the functions whose body references it. Built once
    /// so "who references this string/vtable" does not rescan `.text`.
    pub referrers: BTreeMap<u64, Vec<u64>>,
}

impl XrefTable {
    /// Functions that call `target`, sorted and deduplicated.
    pub fn callers_of(&self, target: u64) -> &[u64] {
        self.callers.get(&target).map(Vec::as_slice).unwrap_or(&[])
    }

    /// Every function whose body references `address`.
    pub fn referencing(&self, address: u64) -> Vec<u64> {
        self.referrers.get(&address).cloned().unwrap_or_default()
    }
}

const BL: u32 = 0x9400_0000;
const B: u32 = 0x1400_0000;
const ADRP: u32 = 0x9000_0000;
const ADD_IMMEDIATE: u32 = 0x1100_0000;
const LDR_LITERAL: u32 = 0x1800_0000;
const LDR_UNSIGNED: u32 = 0xF940_0000;
const RET: u32 = 0xD65F_0000;
const BR: u32 = 0xD61F_0000;
const BLR: u32 = 0xD63F_0000;

/// Scans every function in `bounds` (`(start, end)` pairs) for cross-references.
///
/// `bounds` must come from the FDE list; a function that the unwind tables do
/// not describe is not walked, because there is no trustworthy end address.
pub fn scan(map: &SectionMap, image: &[u8], bounds: &[(u64, u64)]) -> XrefTable {
    let mut functions = Vec::with_capacity(bounds.len());
    let mut callers: BTreeMap<u64, Vec<u64>> = BTreeMap::new();
    let mut referrers: BTreeMap<u64, Vec<u64>> = BTreeMap::new();
    for &(start, end) in bounds {
        let mut xrefs = FunctionXrefs {
            address: start,
            ..FunctionXrefs::default()
        };
        if !is_executable(map, start) || end <= start {
            functions.push(xrefs);
            continue;
        }
        // ADRP pairs a page base with a later ADD/LDR; the register number is
        // the link between the two instructions.
        let mut page: [Option<u64>; 32] = [None; 32];
        let mut offset = start;
        while offset < end {
            let Some(word) = read_word(map, image, offset) else {
                break;
            };
            let field = |shift: u32, bits: u32| (word >> shift) & ((1u32 << bits) - 1);
            match word & 0xFC00_0000 {
                op if op == BL => {
                    let target = branch_target(offset, word & 0x03FF_FFFF, 26);
                    xrefs.calls.push(target);
                    xrefs.call_sites.push(Call {
                        site: offset,
                        target,
                    });
                }
                op if op == B => {
                    let target = branch_target(offset, word & 0x03FF_FFFF, 26);
                    // A branch outside this function is a tail call.
                    if target < start || target >= end {
                        xrefs.calls.push(target);
                        xrefs.call_sites.push(Call {
                            site: offset,
                            target,
                        });
                    }
                }
                _ => {
                    if word & 0x9F00_0000 == ADRP {
                        let rd = field(0, 5) as usize;
                        let immlo = field(29, 2) as i64;
                        let immhi = field(5, 19) as i64;
                        let delta = ((immhi << 2) | immlo) << 12;
                        let delta = sign_extend(delta, 33);
                        page[rd] = Some(((offset & !0xFFF) as i64).wrapping_add(delta) as u64);
                    } else if word & 0x7F80_0000 == ADD_IMMEDIATE {
                        let rd = field(0, 5) as usize;
                        let rn = field(5, 5) as usize;
                        let shift = field(22, 1) * 12;
                        let imm = (field(10, 12) as u64) << shift;
                        if let Some(base) = page[rn] {
                            if rd == rn {
                                xrefs.data_refs.push(base.wrapping_add(imm));
                            }
                        }
                    } else if word & 0x3B00_0000 == LDR_LITERAL {
                        let imm = sign_extend((field(5, 19) as i64) << 2, 21);
                        let target = (offset as i64).wrapping_add(imm) as u64;
                        xrefs.data_refs.push(target);
                    } else if word & 0xFFC0_0000 == LDR_UNSIGNED {
                        let rn = field(5, 5) as usize;
                        let imm = (field(10, 12) as u64) * 8;
                        if let Some(base) = page[rn] {
                            xrefs.data_refs.push(base.wrapping_add(imm));
                        }
                    } else if word & 0xFFFF_FC1F == BR || word & 0xFFFF_FC1F == BLR {
                        // Register is not resolved back to a virtual address
                        // without the register's definition; recorded as
                        // indirect so thunk detection can see it.
                        xrefs.has_indirect_exit = true;
                    } else if word == RET {
                        xrefs.has_indirect_exit = true;
                    }
                }
            }
            offset = offset.saturating_add(4);
        }
        xrefs.calls.sort_unstable();
        xrefs.calls.dedup();
        xrefs.data_refs.sort_unstable();
        xrefs.data_refs.dedup();
        for call in &xrefs.call_sites {
            callers.entry(call.target).or_default().push(start);
        }
        for reference in &xrefs.data_refs {
            referrers.entry(*reference).or_default().push(start);
        }
        functions.push(xrefs);
    }
    for list in callers.values_mut().chain(referrers.values_mut()) {
        list.sort_unstable();
        list.dedup();
    }
    XrefTable {
        functions,
        callers,
        referrers,
    }
}

/// Reads one instruction word, honouring section file offsets.
fn read_word(map: &SectionMap, image: &[u8], address: u64) -> Option<u32> {
    let offset = crate::internal::file_offset_of(map, address)? as usize;
    let bytes = image.get(offset..offset + 4)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

fn branch_target(pc: u64, immediate: u32, bits: u32) -> u64 {
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

    #[test]
    fn a_bl_is_recorded_as_a_callee() {
        // bl +0x40 from 0x1000 lands on 0x1040.
        let word = BL | 0x10;
        let (image, map) = image_with(&[(0x1000, word), (0x1004, RET)]);
        let table = scan(&map, &image, &[(0x1000, 0x1008)]);
        assert_eq!(table.functions[0].calls, vec![0x1040]);
        assert_eq!(table.callers_of(0x1040), &[0x1000]);
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
        let (image, map) = image_with(&[
            (0x1000, adrp(0, 0x1000)),
            (0x1004, add_imm(0, 0, 0x30)),
            (0x1008, RET),
        ]);
        let table = scan(&map, &image, &[(0x1000, 0x100C)]);
        assert_eq!(table.functions[0].data_refs, vec![0x2030]);
    }

    #[test]
    fn a_branch_inside_the_function_is_not_a_tail_call() {
        // b +0x4 stays inside the function.
        let (image, map) = image_with(&[(0x1000, B | 1), (0x1004, RET)]);
        let table = scan(&map, &image, &[(0x1000, 0x1008)]);
        assert!(table.functions[0].calls.is_empty());
    }

    #[test]
    fn a_branch_outside_the_function_is_a_tail_call() {
        // b +0x400 leaves a 0x100-byte function.
        let (image, map) = image_with(&[(0x1000, B | 0x100), (0x1004, RET)]);
        let table = scan(&map, &image, &[(0x1000, 0x1008)]);
        assert_eq!(table.functions[0].calls, vec![0x1400]);
    }

    #[test]
    fn a_ret_marks_the_function_as_indirectly_exiting() {
        let (image, map) = image_with(&[(0x1000, RET)]);
        let table = scan(&map, &image, &[(0x1000, 0x1004)]);
        assert!(table.functions[0].has_indirect_exit);
    }

    #[test]
    fn referencing_finds_the_function_that_uses_an_address() {
        let adrp = adrp(0, 0x1000);
        let add = add_imm(0, 0, 0x40);
        let (image, map) = image_with(&[(0x1000, adrp), (0x1004, add), (0x1008, RET)]);
        let table = scan(&map, &image, &[(0x1000, 0x100C)]);
        assert_eq!(table.referencing(0x2040), vec![0x1000]);
        assert!(table.referencing(0x2044).is_empty());
        let _ = SectionKind::Text;
    }
}
