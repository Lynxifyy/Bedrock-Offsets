//! Single-pass signature planning.
//!
//! # Why this exists
//!
//! The first implementation grew every unresolved pattern by 16 bytes a round
//! and re-matched the whole table each time: 29 rounds, each one re-extracting,
//! re-rendering and re-scanning about 500,000 patterns against 792,000 function
//! starts. On a real library that is hundreds of gigabytes of memory traffic and
//! a resident set the low-memory killer ends mid-scan.
//!
//! This module replaces it with the standard way to answer "what is the
//! shortest prefix of each string that no other string shares":
//!
//! 1. normalise the first [`HEAD_WORDS`] instruction words of every function at
//!    bit level, keeping opcodes and registers and zeroing only the fields that
//!    carry addresses, offsets, stack sizes and materialised constants;
//! 2. sort the function indices by their normalised head;
//! 3. the shortest unique prefix of a string in sorted order is
//!    `max(lcp with previous, lcp with next) + 1`, so one pass over the sorted
//!    order yields every length at once;
//! 4. functions whose whole body normalises to the same words cannot be told
//!    apart by bytes at all; they are grouped, counted, and left to an anchor
//!    locator instead of burning rounds that can never converge.
//!
//! The proof is exact rather than empirical: two functions share a prefix of
//! `L` words if and only if they are adjacent in the sorted order with an lcp of
//! at least `L`, which is the classic suffix-array property.
//!
//! # Invariants
//!
//! * `masks[i]` only ever clears bits of `words[i]`, and the instruction class
//!   bits survive the mask, so [`normalise`] is idempotent.
//! * A pattern built from function A matches position P if and only if every
//!   word matches under its own mask; a match therefore implies the two
//!   normalised word sequences are equal.
//! * Sorting is deterministic: equal heads are ordered by address, so the same
//!   library always produces the same output.

use crate::error::{CoreError, CoreResult};
use crate::scanner::{ProgressSink, ScanControl};

/// Allocates a filled vector, reporting a failed allocation instead of
/// aborting the process.
///
/// `vec![value; len]` calls the infallible allocator, whose failure is
/// `handle_alloc_error`: a `SIGABRT` that `catch_unwind` cannot intercept. The
/// plan arrays are sized by the function count, so on a library with 800k
/// functions a low-memory device can reach that path.
fn try_filled<T: Clone>(len: usize, value: T, what: &str) -> CoreResult<Vec<T>> {
    let mut out: Vec<T> = Vec::new();
    out.try_reserve(len)
        .map_err(|error| CoreError::OutOfMemory(format!("{what}: {error}")))?;
    out.resize(len, value);
    Ok(out)
}

/// Instruction words kept in the head array that drives the sort.
///
/// Eight words is 32 bytes, which separates all but identical bodies in
/// practice; the rest is refined from the text only for the runs that collide.
pub const HEAD_WORDS: usize = 8;

/// Longest signature the planner will emit, in instruction words (512 bytes).
pub const MAX_SIGNATURE_WORDS: usize = 128;

/// Shortest signature the planner will emit, in instruction words.
///
/// Four words is 16 bytes: enough that a wildcarded prologue plus one call
/// cannot match by accident, and short enough to stay cheap in a mod.
pub const MIN_SIGNATURE_WORDS: usize = 4;

/// Bits of the instruction that carry a branch target, a page address, a
/// structure offset, a stack adjustment or a materialised constant.
///
/// Each entry is a *keep* mask: a set bit survives normalisation, a clear bit is
/// zeroed. The class bits are always kept, which is what makes
/// [`normalise`] idempotent and lets two words from different classes compare
/// unequal.
///
/// The previous implementation did the opposite: it wildcarded bytes 1 to 3 of
/// every branch and `ADRP`, which threw away the opcode and the register and
/// kept the low byte of the displacement — the one part that changes between
/// builds.
const fn keep_mask(word: u32) -> u32 {
    // ADR, ADRP first: immlo lives in bits 30:29, so a page delta can set the
    // bits a `B`/`BL` test would otherwise claim.
    if word & 0x1F00_0000 == 0x1000_0000 {
        return 0x9F00_001F;
    }
    // B, BL: imm26 carries the target.
    if word & 0x7C00_0000 == 0x1400_0000 {
        return 0xFC00_0000;
    }
    // B.cond: imm19 carries the target, cond is the low nibble.
    if word & 0xFF00_0010 == 0x5400_0000 {
        return 0xFF00_001F;
    }
    // CBZ, CBNZ: imm19 carries the target.
    if word & 0x7E00_0000 == 0x3400_0000 {
        return 0x7F00_001F;
    }
    // TBZ, TBNZ: bit index (b5, b40) and imm14 carry position and target.
    if word & 0x7E00_0000 == 0x3600_0000 {
        return 0x7F00_001F;
    }
    // ADD, SUB (immediate): sh and imm12 carry structure offsets and sizes.
    if word & 0x1F00_0000 == 0x1100_0000 {
        return 0xFF80_03FF;
    }
    // ADDG, SUBG (immediate): imm6, uimm6 and the tag.
    if word & 0x7F80_0000 == 0x1180_0000 {
        return 0xFFC0_03FF;
    }
    // MOVZ, MOVN, MOVK: hw and imm16 carry materialised constants and hashes.
    if word & 0x1F80_0000 == 0x1280_0000 {
        return 0xFF80_001F;
    }
    // LDR, STR (unsigned immediate): imm12 is a structure offset.
    if word & 0x3B00_0000 == 0x3900_0000 {
        return 0xFFC0_03FF;
    }
    // LDR, STR (pre/post-indexed and unscaled): simm9 is a structure offset.
    if word & 0x3B20_0000 == 0x3800_0000 {
        return 0xFFE0_0C1F;
    }
    // LDR (literal): imm19 is an address.
    if word & 0x3B00_0000 == 0x1800_0000 {
        return 0x3B00_001F;
    }
    // LDP, STP, LDNP, STNP: imm7 is a stack or structure offset.
    if word & 0x3A00_0000 == 0x2800_0000 {
        return 0xFFC0_7FFF;
    }
    // Everything else is algorithmic: shift amounts, logical immediates and
    // bitfield widths describe what the code does, not where anything lives, so
    // they stay in the pattern.
    0xFFFF_FFFF
}

/// Zeroes the fields of one instruction word that move between builds.
#[must_use]
pub const fn normalise(word: u32) -> u32 {
    word & keep_mask(word)
}

/// Whether a word carries an address, offset or materialised constant.
#[must_use]
pub const fn is_relocatable(word: u32) -> bool {
    keep_mask(word) != 0xFFFF_FFFF
}

/// One generated signature, in the two forms the export needs.
///
/// The word form is exact: a position matches when every word matches under its
/// own mask. The rendered form is the IDA-style byte pattern, which can only
/// express whole-byte wildcards, so a byte that is only partly masked is shown
/// as `??` and the exact form stays authoritative.
#[derive(Debug, Clone)]
pub struct Signature {
    words: Box<[u32]>,
    masks: Box<[u32]>,
    /// Group id when the body is byte-identical to another function.
    identical_group: Option<u32>,
    /// Members in that group, including this one.
    group_size: u32,
    /// Whether the body has more words than the signature covers.
    truncated: bool,
    /// A locator for a body that no byte pattern can isolate.
    anchor: Option<Box<str>>,
    /// Whether no other function start shares this prefix.
    unique: bool,
    /// Position inside `identical_group`, in address order, starting at zero.
    ordinal: u32,
}

/// Two signatures are equal when they match the same bytes.
///
/// The raw words of a masked instruction differ between builds while the
/// signature is the same, so equality is defined on the bits the mask keeps.
impl PartialEq for Signature {
    fn eq(&self, other: &Self) -> bool {
        self.masks == other.masks
            && self.words.len() == other.words.len()
            && self
                .words
                .iter()
                .zip(other.words.iter())
                .zip(self.masks.iter())
                .all(|((left, right), mask)| (left & mask) == (right & mask))
    }
}

impl Eq for Signature {}

impl Signature {
    /// Builds a signature from raw instruction words.
    #[must_use]
    pub fn from_words(words: &[u32]) -> Self {
        let words: Box<[u32]> = words.into();
        let masks: Box<[u32]> = words.iter().map(|word| keep_mask(*word)).collect();
        Self {
            words,
            masks,
            identical_group: None,
            group_size: 1,
            truncated: false,
            anchor: None,
            unique: true,
            ordinal: 0,
        }
    }

    /// Rebuilds a signature from the exported hex and bit-mask strings.
    ///
    /// Used by the tests and by anything that reads a signature back out of an
    /// export; it is the inverse of [`Signature::hex`] plus
    /// [`Signature::bit_mask_hex`].
    ///
    /// # Errors
    ///
    /// Returns [`crate::error::CoreError::InvalidElf`] when the two strings do
    /// not describe the same number of whole instruction words.
    pub fn from_hex_and_mask(hex: &str, mask: &str) -> CoreResult<Self> {
        let bytes = parse_hex(hex)?;
        let masks = parse_hex(mask)?;
        if bytes.len() != masks.len() || bytes.len() % 4 != 0 {
            return Err(crate::error::CoreError::InvalidElf(
                "signature and mask must describe the same whole words".to_string(),
            ));
        }
        let mut words = Vec::with_capacity(bytes.len() / 4);
        let mut word_masks = Vec::with_capacity(bytes.len() / 4);
        for chunk in 0..bytes.len() / 4 {
            let mut word = 0u32;
            let mut mask_word = 0u32;
            for byte in 0..4 {
                word |= u32::from(bytes[chunk * 4 + byte]) << (byte * 8);
                mask_word |= u32::from(masks[chunk * 4 + byte]) << (byte * 8);
            }
            words.push(word);
            word_masks.push(mask_word);
        }
        Ok(Self {
            words: words.into(),
            masks: word_masks.into(),
            identical_group: None,
            group_size: 1,
            truncated: false,
            anchor: None,
            unique: true,
            ordinal: 0,
        })
    }

    /// Instruction words the signature covers.
    #[must_use]
    pub fn word_len(&self) -> usize {
        self.words.len()
    }

    /// Bytes the signature covers.
    #[must_use]
    pub fn byte_len(&self) -> usize {
        self.words.len() * 4
    }

    /// Bytes the signature holds in memory.
    #[must_use]
    pub fn storage_bytes(&self) -> usize {
        (self.words.len() + self.masks.len()) * 4
    }

    /// The identical-body group this signature belongs to, if any.
    #[must_use]
    pub fn identical_group(&self) -> Option<u32> {
        self.identical_group
    }

    /// How many functions share this signature's normalised body.
    #[must_use]
    pub fn group_size(&self) -> u32 {
        self.group_size
    }

    /// Whether the function body continues past the signature.
    #[must_use]
    pub fn is_truncated(&self) -> bool {
        self.truncated
    }

    /// Fraction of bits that are still literal, as a percentage.
    ///
    /// A signature that keeps most of its bits survives a rebuild that moves
    /// code around; one that is mostly wildcards survives by luck.
    #[must_use]
    pub fn stability(&self) -> u8 {
        let total: u32 = self.masks.len() as u32 * 32;
        if total == 0 {
            return 0;
        }
        let kept: u32 = self.masks.iter().map(|mask| mask.count_ones()).sum();
        u8::try_from(kept * 100 / total).unwrap_or(100)
    }

    /// Whether the byte at `index` is a wildcard in the IDA form.
    #[must_use]
    pub fn is_wildcard(&self, index: usize) -> bool {
        self.mask_byte(index) != 0xFF
    }

    /// The AND mask of one byte, `0xFF` for a literal byte.
    #[must_use]
    pub fn mask_byte(&self, index: usize) -> u8 {
        let word = index / 4;
        let byte = index % 4;
        match self.masks.get(word) {
            Some(mask) => ((mask >> (byte * 8)) & 0xFF) as u8,
            None => 0,
        }
    }

    /// The raw byte at `index`, ignoring the mask.
    #[must_use]
    pub fn byte(&self, index: usize) -> u8 {
        let word = index / 4;
        let byte = index % 4;
        match self.words.get(word) {
            Some(value) => ((value >> (byte * 8)) & 0xFF) as u8,
            None => 0,
        }
    }

    /// The exact form: raw bytes, two hex digits per byte.
    #[must_use]
    pub fn hex(&self) -> String {
        let mut out = String::with_capacity(self.byte_len() * 3);
        for index in 0..self.byte_len() {
            if index > 0 {
                out.push(' ');
            }
            out.push_str(&format!("{:02X}", self.byte(index)));
        }
        out
    }

    /// The exact form's companion: the AND mask, one hex byte per signature
    /// byte. `FF` means the byte must match, `00` means it is ignored.
    #[must_use]
    pub fn bit_mask_hex(&self) -> String {
        let mut out = String::with_capacity(self.byte_len() * 3);
        for index in 0..self.byte_len() {
            if index > 0 {
                out.push(' ');
            }
            out.push_str(&format!("{:02X}", self.mask_byte(index)));
        }
        out
    }

    /// The IDA-style pattern: one token per byte, `?` for every nibble the mask
    /// does not pin down.
    ///
    /// A byte the mask clears entirely renders as `??`, the usual wildcard; a
    /// byte the mask only partly covers keeps the nibbles it knows, so the
    /// opcode of a masked `BL` still shows as `9?` instead of vanishing. The
    /// nibble form is a lossy view of [`Signature::bit_mask_hex`], which stays
    /// the exact one.
    #[must_use]
    pub fn render(&self) -> String {
        let mut out = String::with_capacity(self.byte_len() * 3);
        for index in 0..self.byte_len() {
            if index > 0 {
                out.push(' ');
            }
            let mask = self.mask_byte(index);
            let byte = self.byte(index);
            if mask == 0 {
                out.push_str("??");
                continue;
            }
            for nibble in [4u8, 0u8] {
                if (mask >> nibble) & 0xF == 0xF {
                    out.push(
                        char::from_digit(u32::from((byte >> nibble) & 0xF), 16)
                            .unwrap_or('0')
                            .to_ascii_uppercase(),
                    );
                } else {
                    out.push('?');
                }
            }
        }
        out
    }

    /// Whether the signature matches the bytes at `offset`.
    #[must_use]
    pub fn matches_at(&self, data: &[u8], offset: usize) -> bool {
        let Some(tail) = data.get(offset..) else {
            return false;
        };
        for (word_index, (value, mask)) in self.words.iter().zip(self.masks.iter()).enumerate() {
            let start = word_index * 4;
            let Some(chunk) = tail.get(start..start + 4) else {
                return false;
            };
            let word = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
            if word & mask != value & mask {
                return false;
            }
        }
        true
    }

    /// Position inside the identical-body group, in address order.
    #[must_use]
    pub fn ordinal(&self) -> u32 {
        self.ordinal
    }

    /// Whether the signature matched exactly one function start.
    #[must_use]
    pub fn is_unique(&self) -> bool {
        self.unique
    }

    /// The anchor that locates a function whose bytes are not unique.
    #[must_use]
    pub fn anchor(&self) -> Option<&str> {
        self.anchor.as_deref()
    }

    /// Attaches an anchor locator.
    pub fn set_anchor(&mut self, anchor: Option<Box<str>>) {
        self.anchor = anchor;
    }

    fn set_group(&mut self, group: Option<u32>, size: u32) {
        self.identical_group = group;
        self.group_size = size;
    }

    fn set_truncated(&mut self, truncated: bool) {
        self.truncated = truncated;
    }
}

fn parse_hex(text: &str) -> CoreResult<Vec<u8>> {
    let mut out = Vec::new();
    for token in text.split_whitespace() {
        let token = token.trim_start_matches("0x");
        let value = u8::from_str_radix(token, 16)
            .map_err(|_| crate::error::CoreError::InvalidElf(format!("not a hex byte: {token}")))?;
        out.push(value);
    }
    Ok(out)
}

/// One function's place in the plan.
#[derive(Debug, Clone, Copy, Default)]
pub struct PlanEntry {
    /// Words the signature needs to be unique, before clamping.
    pub needed_words: u32,
    /// Group id when the body is byte-identical to another function.
    pub identical_group: Option<u32>,
    /// Members of that group.
    pub group_size: u32,
    /// Position inside the group, in address order.
    pub ordinal: u32,
}

/// What the planner did, for the manifest and the coverage report.
#[derive(Debug, Clone, Copy, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanStats {
    /// Functions the planner considered (the uniqueness universe).
    pub functions: usize,
    /// Signatures emitted.
    pub signatures: usize,
    /// Signatures unique among function starts.
    pub unique: usize,
    /// Signatures whose body is shared with at least one other function.
    pub identical_functions: usize,
    /// Distinct identical bodies.
    pub identical_groups: usize,
    /// Longest signature, in words.
    pub max_words: usize,
    /// Total signature words, for the mean.
    pub total_words: usize,
    /// Signatures that had to use the whole 512-byte reach.
    pub at_limit: usize,
    /// Wall time of the whole plan, in milliseconds.
    pub millis: u64,
    /// Passes over the whole `.text`.
    ///
    /// This is the number that used to be 29 rounds: one is the plan, one is a
    /// full-section uniqueness check. A regression test asserts it stays at one.
    pub text_passes: usize,
}

/// Plans signatures for every address in `targets`.
///
/// `functions` must be sorted by address; `text_base` is the address of
/// `text[0]`. Targets outside the text are skipped.
///
/// # Errors
///
/// Returns [`crate::error::CoreError::Cancelled`] when `control` is cancelled.
pub fn plan(
    text: &[u8],
    text_base: u64,
    functions: &[(u64, u64)],
    targets: &[u64],
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<(Vec<(u64, Signature)>, PlanStats)> {
    let started = std::time::Instant::now();
    let mut stats = PlanStats {
        functions: functions.len(),
        text_passes: 1,
        ..PlanStats::default()
    };
    if functions.is_empty() || text.is_empty() {
        return Ok((Vec::new(), stats));
    }

    // Head: the first `HEAD_WORDS` normalised words of every function, plus the
    // real body length so a short function is not compared as if it were long.
    let mut heads: Vec<[u32; HEAD_WORDS]> =
        try_filled(functions.len(), [0u32; HEAD_WORDS], "signature heads")?;
    let mut body_words: Vec<u16> = try_filled(functions.len(), 0u16, "signature body words")?;
    for (index, (address, size)) in functions.iter().enumerate() {
        let offset = address.saturating_sub(text_base) as usize;
        let available = text.len().saturating_sub(offset) / 4;
        let declared = if *size == 0 {
            available
        } else {
            (*size as usize / 4).min(available)
        };
        let words = declared.min(MAX_SIGNATURE_WORDS).min(u16::MAX as usize);
        body_words[index] = u16::try_from(words).unwrap_or(u16::MAX);
        let head = words.min(HEAD_WORDS);
        for (word, slot) in heads[index].iter_mut().enumerate().take(head) {
            let start = offset + word * 4;
            let chunk = &text[start..start + 4];
            *slot = normalise(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
        }
    }
    if control.is_cancelled() {
        return Err(crate::error::CoreError::Cancelled);
    }
    report(
        sink,
        81,
        &format!("Signatures: normalised {} heads", functions.len()),
    );

    // Sort by head. Equal heads keep address order, which is already the order
    // of the slice, so the sort is deterministic without a tiebreak comparison.
    let mut order: Vec<u32> = Vec::new();
    order
        .try_reserve(functions.len())
        .map_err(|error| CoreError::OutOfMemory(format!("signature order: {error}")))?;
    order.extend(0..functions.len() as u32);
    order.sort_unstable_by(|left, right| {
        let (left, right) = (*left as usize, *right as usize);
        let left_head = &heads[left][..usize::from(body_words[left]).min(HEAD_WORDS)];
        let right_head = &heads[right][..usize::from(body_words[right]).min(HEAD_WORDS)];
        left_head.cmp(right_head)
    });
    if control.is_cancelled() {
        return Err(crate::error::CoreError::Cancelled);
    }
    report(sink, 84, "Signatures: sorted, measuring common prefixes");

    let count = order.len();
    // Serial on purpose: this is a linear scan over a few megabytes of heads, so
    // a thread pool adds start-up and per-worker buffers for no wall time.
    let mut lcp: Vec<u16> = try_filled(count.saturating_sub(1), 0u16, "signature lcp")?;
    for (slot, value) in lcp.iter_mut().enumerate() {
        let left = order[slot] as usize;
        let right = order[slot + 1] as usize;
        let limit = usize::from(body_words[left]).min(usize::from(body_words[right]));
        let mut shared = 0usize;
        while shared < limit && shared < HEAD_WORDS && heads[left][shared] == heads[right][shared] {
            shared += 1;
        }
        *value = u16::try_from(shared).unwrap_or(u16::MAX);
    }

    // Everything that needs more than the head is a run of functions with the
    // same eight words. Refine those runs from the text; the runs are small
    // (thunk families and template instantiations), so this is a fraction of
    // the work the rounds used to do.
    let mut entries: Vec<PlanEntry> = try_filled(count, PlanEntry::default(), "signature plan")?;
    let mut group_id = 0u32;
    let mut run_start = 0usize;
    while run_start < count {
        let mut run_end = run_start + 1;
        // A run is a group of neighbours that share everything the head holds:
        // eight words, or the whole body when the function is shorter.
        while run_end < count
            && lcp[run_end - 1] as usize
                >= head_limit(
                    usize::from(body_words[order[run_end - 1] as usize]),
                    usize::from(body_words[order[run_end] as usize]),
                )
        {
            run_end += 1;
        }
        if run_end - run_start > 1 {
            refine_run(
                text,
                text_base,
                functions,
                Run {
                    members: &order[run_start..run_end],
                    body_words: &body_words,
                },
                &mut entries[run_start..run_end],
                &mut group_id,
                &mut stats,
            )?;
        }
        run_start = run_end;
    }
    if control.is_cancelled() {
        return Err(crate::error::CoreError::Cancelled);
    }

    // Shortest unique prefix from the neighbours, in the sorted order.
    for slot in 0..count {
        let previous = if slot > 0 { lcp[slot - 1] } else { 0 };
        let next = if slot + 1 < count { lcp[slot] } else { 0 };
        let entry = &mut entries[slot];
        if entry.needed_words == 0 {
            let shared = u32::from(previous.max(next));
            let needed = shared + 1;
            let body = u32::from(body_words[order[slot] as usize]);
            entry.needed_words = if needed >= body {
                // The whole body is shared with a neighbour, so no prefix can
                // separate them; the group pass below has already marked it.
                body.max(1)
            } else {
                needed
            };
        }
    }

    // Where each function sits in the sorted order, so emitting a target is a
    // lookup instead of a scan.
    let mut slot_of: Vec<u32> = try_filled(count, 0u32, "signature slots")?;
    for (slot, index) in order.iter().enumerate() {
        slot_of[*index as usize] = u32::try_from(slot).unwrap_or(u32::MAX);
    }

    // Emit.
    let mut out = Vec::with_capacity(targets.len());
    for address in targets {
        let Ok(index) = functions.binary_search_by_key(address, |(start, _)| *start) else {
            continue;
        };
        let entry = entries[slot_of[index] as usize];
        let body = usize::from(body_words[index]);
        let words = (entry.needed_words as usize)
            .clamp(MIN_SIGNATURE_WORDS, MAX_SIGNATURE_WORDS)
            .min(body.max(1));
        let offset = address.saturating_sub(text_base) as usize;
        let mut signature = Signature::from_words(&read_words(text, offset, words));
        signature.set_group(entry.identical_group, entry.group_size.max(1));
        signature.ordinal = entry.ordinal;
        signature.set_truncated(words < body);
        // Unique means no other function start shares this prefix, which is a
        // different question from whether the signature covers the whole body.
        signature.unique = entry.identical_group.is_none() && entry.needed_words as usize <= body;
        stats.signatures += 1;
        stats.total_words += signature.word_len();
        stats.max_words = stats.max_words.max(signature.word_len());
        if entry.identical_group.is_some() {
            stats.identical_functions += 1;
        } else if entry.needed_words as usize <= body {
            stats.unique += 1;
        }
        if signature.word_len() >= MAX_SIGNATURE_WORDS {
            stats.at_limit += 1;
        }
        out.push((*address, signature));
    }
    stats.millis = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
    report(
        sink,
        88,
        &format!(
            "Signatures: {} planned in {} ms ({} unique, {} identical-body)",
            stats.signatures, stats.millis, stats.unique, stats.identical_functions
        ),
    );
    Ok((out, stats))
}

/// The members of one run of neighbours sharing a head.
struct Run<'a> {
    members: &'a [u32],
    body_words: &'a [u16],
}

/// Refines a run of functions that share their whole head.
///
/// The run is compared word by word past the head, up to the body length, and
/// functions whose entire body matches are put in one identical group. The run
/// is small (a thunk family, one template instantiation), so this is a fraction
/// of the work a whole-table round used to cost.
fn refine_run(
    text: &[u8],
    text_base: u64,
    functions: &[(u64, u64)],
    run: Run<'_>,
    entries: &mut [PlanEntry],
    group_id: &mut u32,
    stats: &mut PlanStats,
) -> CoreResult<()> {
    let Run {
        members,
        body_words,
    } = run;
    let mut sequences: Vec<Vec<u32>> = Vec::new();
    sequences
        .try_reserve(members.len())
        .map_err(|error| CoreError::OutOfMemory(format!("signature run: {error}")))?;
    for index in members {
        let address = functions[*index as usize].0;
        let offset = address.saturating_sub(text_base) as usize;
        let words = usize::from(body_words[*index as usize]);
        sequences.push(
            read_words(text, offset, words)
                .iter()
                .copied()
                .map(normalise)
                .collect(),
        );
    }

    // Order the run by its full sequence so identical bodies become adjacent.
    // Positions are relative to the run, not to the whole table.
    let mut local: Vec<usize> = (0..members.len()).collect();
    local.sort_unstable_by(|left, right| sequences[*left].cmp(&sequences[*right]));

    let mut local_lcp: Vec<u32> =
        try_filled(local.len().saturating_sub(1), 0u32, "signature run lcp")?;
    for slot in 0..local_lcp.len() {
        let left = &sequences[local[slot]];
        let right = &sequences[local[slot + 1]];
        let limit = left.len().min(right.len());
        let mut shared = 0usize;
        while shared < limit && left[shared] == right[shared] {
            shared += 1;
        }
        local_lcp[slot] = u32::try_from(shared).unwrap_or(u32::MAX);
    }

    // A run of consecutive entries that share their whole body is one group.
    let mut slot = 0usize;
    while slot < local.len() {
        let mut end = slot + 1;
        while end < local.len()
            && local_lcp[end - 1] as usize
                == sequences[local[end - 1]]
                    .len()
                    .min(sequences[local[end]].len())
        {
            end += 1;
        }
        let size = end - slot;
        if size > 1 {
            *group_id += 1;
            stats.identical_groups += 1;
            for (position, member) in local[slot..end].iter().enumerate() {
                let entry = &mut entries[*member];
                entry.identical_group = Some(*group_id);
                entry.group_size = u32::try_from(size).unwrap_or(u32::MAX);
                entry.ordinal = u32::try_from(position).unwrap_or(u32::MAX);
                entry.needed_words = u32::try_from(sequences[*member].len()).unwrap_or(u32::MAX);
            }
        } else {
            let previous = if slot > 0 { local_lcp[slot - 1] } else { 0 };
            let next = if slot + 1 < local.len() {
                local_lcp[slot]
            } else {
                0
            };
            let member = local[slot];
            let body = u32::try_from(sequences[member].len()).unwrap_or(u32::MAX);
            let needed = previous.max(next) + 1;
            entries[member].needed_words = if needed >= body { body.max(1) } else { needed };
        }
        slot = end;
    }
    Ok(())
}

/// Words two functions must share before a run of them needs refining.
fn head_limit(left_body: usize, right_body: usize) -> usize {
    left_body.min(right_body).min(HEAD_WORDS)
}

fn read_words(text: &[u8], offset: usize, words: usize) -> Vec<u32> {
    let mut out = Vec::with_capacity(words);
    for index in 0..words {
        let start = offset + index * 4;
        let Some(chunk) = text.get(start..start + 4) else {
            break;
        };
        out.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    out
}

/// Counts, for every signature, how many byte offsets of `text` it matches.
///
/// This is the check that matters for a mod: the engine can only prove a
/// signature unique among function starts, but a scanner walks the whole
/// section. The pass is one linear read of `.text` behind a Bloom filter, so it
/// costs a fraction of a round of the old matcher.
#[must_use]
pub fn count_over_text(text: &[u8], signatures: &[&Signature]) -> Vec<u32> {
    let mut counts = vec![0u32; signatures.len()];
    if signatures.is_empty() || text.is_empty() {
        return counts;
    }
    // Bloom filter over the first two words, 16 bits per signature, capped so a
    // million signatures cannot ask for gigabytes.
    let bits = (signatures.len() * 16).clamp(1 << 16, 1 << 26);
    let mut filter = vec![0u64; bits / 64 + 1];
    let mut index_of: std::collections::HashMap<u64, Vec<usize>> =
        std::collections::HashMap::with_capacity(signatures.len() * 2);
    for (index, signature) in signatures.iter().enumerate() {
        let key = prefix_key(signature);
        index_of.entry(key).or_default().push(index);
        let bit = (key as usize) % bits;
        filter[bit / 64] |= 1u64 << (bit % 64);
        let bit = ((key >> 32) as usize) % bits;
        filter[bit / 64] |= 1u64 << (bit % 64);
    }
    let mask_bits = (bits - 1) as u64;
    let mut position = 0usize;
    while position + 8 <= text.len() {
        let word0 = u32::from_le_bytes([
            text[position],
            text[position + 1],
            text[position + 2],
            text[position + 3],
        ]);
        let word1 = u32::from_le_bytes([
            text[position + 4],
            text[position + 5],
            text[position + 6],
            text[position + 7],
        ]);
        let key = u64::from(normalise(word0)) | (u64::from(normalise(word1)) << 32);
        let first = (key & mask_bits) as usize;
        if filter[first / 64] & (1u64 << (first % 64)) != 0 {
            let second = ((key >> 32) & mask_bits) as usize;
            if filter[second / 64] & (1u64 << (second % 64)) != 0 {
                if let Some(candidates) = index_of.get(&key) {
                    for index in candidates {
                        if counts[*index] < u32::MAX
                            && signatures[*index].matches_at(text, position)
                        {
                            counts[*index] += 1;
                        }
                    }
                }
            }
        }
        position += 1;
    }
    counts
}

fn prefix_key(signature: &Signature) -> u64 {
    let first = signature.words.first().copied().unwrap_or(0);
    let second = signature.words.get(1).copied().unwrap_or(0);
    u64::from(normalise(first)) | (u64::from(normalise(second)) << 32)
}

fn report(sink: Option<&dyn ProgressSink>, percent: u32, message: &str) {
    if let Some(sink) = sink {
        sink.report(percent, message);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signature_of(words: &[u32]) -> Signature {
        Signature::from_words(words)
    }

    #[test]
    fn a_branch_target_is_masked_but_the_opcode_and_link_bit_survive() {
        let bl_here = 0x9400_0010u32;
        let bl_there = 0x97FF_FFF0u32;
        assert_eq!(normalise(bl_here), normalise(bl_there));
        assert_eq!(normalise(bl_here) & 0xFC00_0000, 0x9400_0000);
        // A `b` is a different class and must not collapse into the `bl`.
        assert_ne!(normalise(bl_here), normalise(0x1400_0000));
    }

    #[test]
    fn an_adrp_page_is_masked_and_the_register_survives() {
        let page_a = 0x9000_0008u32; // adrp x8, page A
        let page_b = 0x90FF_FFE8u32; // adrp x8, page B
        assert_eq!(normalise(page_a), normalise(page_b));
        // A different destination register is a different instruction.
        assert_ne!(normalise(page_a), normalise(0x9000_0009));
    }

    #[test]
    fn a_stack_offset_is_masked_and_the_pair_survives() {
        // stp x29, x30, [sp, #-16]! and the same pair with a bigger frame
        assert_eq!(normalise(0xA9BF_7BFD), normalise(0xA9BA_7BFD));
        // A different register pair is a different instruction.
        assert_ne!(normalise(0xA9BF_7BFD), normalise(0xA9BF_77FD));
    }

    #[test]
    fn a_materialised_constant_is_masked() {
        // movz x8, #0x1234 and movz x8, #0x4321
        assert_eq!(normalise(0xD282_4688), normalise(0xD288_6428));
    }

    #[test]
    fn a_register_operation_keeps_every_bit() {
        // add x0, x0, x1 has no address or offset in it.
        let word = 0x8B01_0000u32;
        assert_eq!(normalise(word), word);
        assert!(!is_relocatable(word));
    }

    #[test]
    fn normalise_is_idempotent_over_a_sweep_of_words() {
        // Every class the masker knows, plus a spread of random-looking words.
        let mut state = 0x1234_5678_9ABC_DEF0u64;
        for _ in 0..20_000 {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            let word = (state >> 32) as u32;
            let once = normalise(word);
            assert_eq!(normalise(once), once, "word {word:#010x}");
            assert_eq!(once & !keep_mask(word), 0);
        }
    }

    #[test]
    fn the_render_is_a_wildcard_where_a_whole_byte_is_masked() {
        let signature = signature_of(&[0x9400_0010, 0x8B01_0000]);
        let rendered = signature.render();
        // The low byte of the branch word is all immediate; the opcode nibble
        // and the link bit survive as `9?`.
        assert_eq!(rendered, "?? ?? ?? 9? 00 00 01 8B");
        assert_eq!(signature.hex(), "10 00 00 94 00 00 01 8B");
        assert_eq!(signature.bit_mask_hex(), "00 00 00 FC FF FF FF FF");
    }

    #[test]
    fn the_hex_and_mask_round_trip() {
        let signature = signature_of(&[0x9400_0010, 0x8B01_0000, 0xA9BF_7BFD]);
        let rebuilt =
            Signature::from_hex_and_mask(&signature.hex(), &signature.bit_mask_hex()).unwrap();
        assert_eq!(rebuilt, signature);
    }

    #[test]
    fn a_partial_byte_is_a_wildcard_in_the_ida_form_and_exact_in_the_mask_form() {
        // cbz x0, +0x40 keeps bits 4..0 of the low byte.
        let signature = signature_of(&[0xB400_0200]);
        assert_eq!(signature.render(), "?0 ?? ?? ?4");
        assert_eq!(signature.mask_byte(0), 0x1F);
        // The exact form still tells two different registers apart.
        let other = signature_of(&[0xB400_0201]);
        assert_ne!(signature.words, other.words);
    }

    #[test]
    fn matching_respects_the_mask() {
        let signature = signature_of(&[0x9400_0010]);
        let mut data = vec![0u8; 16];
        data[0..4].copy_from_slice(&0x94FF_FFF0u32.to_le_bytes());
        assert!(signature.matches_at(&data, 0));
        data[0..4].copy_from_slice(&0x14FF_FFF0u32.to_le_bytes());
        assert!(!signature.matches_at(&data, 0), "a b must not match a bl");
    }

    #[test]
    fn stability_falls_as_more_bits_are_masked() {
        let literal = signature_of(&[0x8B01_0000, 0x8B02_0000]);
        let wildcard = signature_of(&[0x9400_0000, 0x9400_0000]);
        assert_eq!(literal.stability(), 100);
        assert!(wildcard.stability() < 20);
    }

    #[test]
    fn a_body_that_is_shared_yields_a_group_not_a_signature() {
        // Two functions with the same four words, one with a different fifth.
        let mut text = Vec::new();
        for word in [0x8B01_0000u32, 0x8B02_0000, 0x8B03_0000, 0x8B04_0000] {
            text.extend_from_slice(&word.to_le_bytes());
        }
        text.extend_from_slice(&0x8B01_0000u32.to_le_bytes());
        text.extend_from_slice(&0x8B02_0000u32.to_le_bytes());
        text.extend_from_slice(&0x8B03_0000u32.to_le_bytes());
        text.extend_from_slice(&0x8B05_0000u32.to_le_bytes());
        let functions = [(0x1000u64, 16u64), (0x1010, 16)];
        let control = ScanControl::default();
        let (planned, stats) =
            plan(&text, 0x1000, &functions, &[0x1000, 0x1010], &control, None).expect("plan");
        assert_eq!(planned.len(), 2);
        assert_eq!(stats.identical_groups, 0, "the fifth word differs");
        assert_eq!(stats.unique, 2);
    }

    #[test]
    fn the_plan_reads_text_once_however_many_bodies_share_a_prefix() {
        // The engine this replaced grew every pattern by sixteen bytes per round
        // and re-matched the whole table, so a library whose functions share a
        // long prologue cost 29 full passes and never converged on the tablet.
        let mut text = Vec::new();
        for _ in 0..4096 {
            for word in [0x8B01_0000u32, 0x8B02_0000, 0x8B03_0000, 0x8B04_0000] {
                text.extend_from_slice(&word.to_le_bytes());
            }
        }
        let functions: Vec<(u64, u64)> = (0..4096u64).map(|i| (0x1000 + i * 16, 16)).collect();
        let targets: Vec<u64> = functions.iter().map(|(start, _)| *start).collect();
        let control = ScanControl::default();
        let (planned, stats) =
            plan(&text, 0x1000, &functions, &targets, &control, None).expect("plan");
        assert_eq!(stats.text_passes, 1, "one plan pass, one uniqueness pass");
        assert_eq!(planned.len(), 4096);
        assert_eq!(stats.identical_groups, 1);
        // Every member still gets a distinct ordinal, so an anchor can name it.
        let ordinals: Vec<u32> = planned.iter().map(|(_, s)| s.ordinal()).collect();
        assert_eq!(ordinals, (0..4096).collect::<Vec<u32>>());
        assert!(planned.iter().all(|(_, s)| s.group_size() == 4096));
    }

    #[test]
    fn byte_identical_bodies_are_grouped_instead_of_retried() {
        let mut text = Vec::new();
        for _ in 0..3 {
            for word in [0x8B01_0000u32, 0x8B02_0000, 0x8B03_0000, 0x8B04_0000] {
                text.extend_from_slice(&word.to_le_bytes());
            }
        }
        let functions = [(0x1000u64, 16u64), (0x1010, 16), (0x1020, 16)];
        let control = ScanControl::default();
        let (planned, stats) = plan(
            &text,
            0x1000,
            &functions,
            &[0x1000, 0x1010, 0x1020],
            &control,
            None,
        )
        .expect("plan");
        assert_eq!(planned.len(), 3);
        assert_eq!(stats.identical_groups, 1);
        assert_eq!(stats.identical_functions, 3);
        assert!(planned
            .iter()
            .all(|(_, signature)| signature.group_size() == 3));
    }

    #[test]
    fn the_shortest_prefix_is_one_word_past_the_longest_shared_one() {
        // Two bodies sharing three words, then differing.
        let mut text = Vec::new();
        for word in [
            0x8B01_0000u32,
            0x8B02_0000,
            0x8B03_0000,
            0x8B04_0000,
            0x8B05_0000,
        ] {
            text.extend_from_slice(&word.to_le_bytes());
        }
        for word in [
            0x8B01_0000u32,
            0x8B02_0000,
            0x8B03_0000,
            0x8B06_0000,
            0x8B05_0000,
        ] {
            text.extend_from_slice(&word.to_le_bytes());
        }
        let functions = [(0x1000u64, 20u64), (0x1014, 20)];
        let control = ScanControl::default();
        let (planned, _) =
            plan(&text, 0x1000, &functions, &[0x1000, 0x1014], &control, None).expect("plan");
        // Three shared words means four are needed.
        assert_eq!(planned[0].1.word_len(), 4);
        assert_eq!(planned[1].1.word_len(), 4);
    }

    #[test]
    fn a_signature_survives_a_rebuild_that_moves_the_code() {
        // The same three functions, once at low addresses and once at high
        // ones, with the callee on the other side of the caller so the branch
        // displacement changes sign and size and the `adrp` page moves.
        let body = |pc: u64, callee: i64, page: u64| -> [u32; 8] {
            [
                0xA9BF_7BFD,                 // stp x29, x30, [sp, #-16]!
                0x9100_03FD,                 // mov x29, sp
                adrp_word(8, page, pc),      // adrp x8, page
                0x8B01_0000,                 // add x0, x0, x1
                bl_word(callee - pc as i64), // bl callee
                0xD65F_03C0,                 // ret
                0xD503_201F,                 // nop
                0xD503_201F,                 // nop
            ]
        };
        let build = |base: u64, callee_delta: i64| {
            let mut text = Vec::new();
            for index in 0..3u64 {
                let pc = base + index * 0x20;
                for word in body(pc, pc as i64 + callee_delta, base + 0x4000 + index * 0x100) {
                    text.extend_from_slice(&word.to_le_bytes());
                }
            }
            let functions: Vec<(u64, u64)> =
                (0..3u64).map(|index| (base + index * 0x20, 32)).collect();
            (text, functions)
        };
        let (text_a, functions_a) = build(0x1000, 0x180);
        let (text_b, functions_b) = build(0x8000, -0x140);
        let control = ScanControl::default();
        let (planned_a, _) = plan(
            &text_a,
            0x1000,
            &functions_a,
            &[0x1000, 0x1020, 0x1040],
            &control,
            None,
        )
        .expect("plan a");
        let (planned_b, _) = plan(
            &text_b,
            0x8000,
            &functions_b,
            &[0x8000, 0x8020, 0x8040],
            &control,
            None,
        )
        .expect("plan b");
        for ((_, left), (_, right)) in planned_a.iter().zip(planned_b.iter()) {
            assert_eq!(
                left, right,
                "the same function must produce the same signature after a rebuild"
            );
        }
    }

    fn adrp_word(reg: u32, target: u64, pc: u64) -> u32 {
        let pages = (((target & !0xFFF) as i64 - (pc & !0xFFF) as i64) >> 12) as u32;
        0x9000_0000 | ((pages & 0x3) << 29) | (((pages >> 2) & 0x7FFFF) << 5) | reg
    }

    fn bl_word(delta: i64) -> u32 {
        0x9400_0000 | (((delta >> 2) as u32) & 0x03FF_FFFF)
    }

    #[test]
    fn a_signature_that_repeats_over_the_whole_text_is_not_unique_there() {
        let mut text = Vec::new();
        for _ in 0..4 {
            for word in [0x8B01_0000u32, 0x8B02_0000] {
                text.extend_from_slice(&word.to_le_bytes());
            }
        }
        let signature = signature_of(&[0x8B01_0000, 0x8B02_0000]);
        let counts = count_over_text(&text, &[&signature]);
        assert_eq!(counts[0], 4);
    }

    #[test]
    fn a_signature_present_once_over_the_text_is_counted_once() {
        let mut text = Vec::new();
        for word in [0x8B01_0000u32, 0x8B02_0000, 0x8B03_0000, 0x8B04_0000] {
            text.extend_from_slice(&word.to_le_bytes());
        }
        let signature = signature_of(&[0x8B01_0000, 0x8B02_0000]);
        let counts = count_over_text(&text, &[&signature]);
        assert_eq!(counts[0], 1);
    }
}
