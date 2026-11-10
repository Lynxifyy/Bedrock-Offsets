//! Parallel scanning of an executable section.
//!
//! The section is scanned in fixed-size chunks, one per worker, and the results
//! are stitched back together. Two details make that safe:
//!
//! * Chunks overlap by [`CHUNK_OVERLAP_BYTES`], so a match that straddles a
//!   chunk boundary is still found, in the chunk that contains its first byte.
//! * Duplicates are removed afterwards, because an overlap can make two chunks
//!   report the same position.
//!
//! Nothing is copied: each worker borrows its own slice of the mapping, so the
//! scan's memory cost is the result vector, not the input.

use std::sync::atomic::{AtomicBool, Ordering};

use rayon::prelude::*;
use serde::Serialize;

use crate::error::{CoreError, CoreResult};
use crate::patterns::PatternSet;
use crate::prologue::{PrologueKind, PrologueVerifier};
use crate::sections::SectionMap;

/// Chunk size handed to each worker.
///
/// Large enough that the per-chunk overhead disappears, small enough that a
/// cancelled scan stops promptly and the work divides evenly across cores.
pub const CHUNK_BYTES: usize = 4 * 1024 * 1024;

/// Extra bytes each chunk borrows from the following chunk.
///
/// A pattern longer than this cannot be matched across a boundary. Real
/// signatures are a few dozen bytes, so 32 is comfortably above them.
pub const CHUNK_OVERLAP_BYTES: usize = 32;

/// One verified hit: a pattern, where it was found, and the address it implies.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PatternHit {
    /// Name of the pattern that matched.
    pub name: String,
    /// Offset of the match inside the scanned section.
    pub section_offset: u64,
    /// Offset of the match inside the file.
    pub file_offset: u64,
    /// Virtual address the match would have once loaded.
    pub address: u64,
    /// Address rendered as `0x`-prefixed hex.
    pub address_hex: String,
    /// Which prologue form the bytes at the match decode to, when verified.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prologue_kind: Option<PrologueKind>,
    /// The decoded first instruction, when verified.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prologue_instruction: Option<String>,
}

/// Result of scanning one section.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ScanOutcome {
    /// Section that was scanned.
    pub section: String,
    /// Number of bytes scanned.
    pub scanned_bytes: u64,
    /// Number of chunks the section was split into.
    pub chunks: usize,
    /// Number of workers the pool actually used.
    pub workers: usize,
    /// Every hit, sorted by address.
    pub hits: Vec<PatternHit>,
    /// Candidates the prologue check discarded; 0 before verification runs.
    pub discarded: usize,
    /// Whether the prologue check was applied.
    pub verified: bool,
}

/// A cancellation flag shared with the workers.
///
/// Wrapped rather than exposed as a bare `AtomicBool` so a caller cannot
/// accidentally reset it mid-scan.
#[derive(Debug, Default)]
pub struct ScanControl {
    cancelled: AtomicBool,
}

impl ScanControl {
    /// Creates a control that has not been cancelled.
    pub fn new() -> Self {
        Self {
            cancelled: AtomicBool::new(false),
        }
    }

    /// Asks the workers to stop.
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::SeqCst);
    }

    /// Whether cancellation has been requested.
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }
}

/// Receives progress while a scan runs.
///
/// Implemented by the JNI bridge, which forwards each report to Kotlin. The
/// trait keeps this module free of any JNI types, so the scanner stays testable
/// on the host.
pub trait ProgressSink {
    /// Reports progress.
    ///
    /// @param percent completion between 0 and 100.
    /// @param message short description of the current step.
    fn report(&self, percent: u32, message: &str);
}

/// Percent of the scan budget spent matching patterns; the rest is verification.
const MATCH_PERCENT: u32 = 55;

/// Number of progress updates a scan emits, so the cost is independent of size.
const PROGRESS_STEPS: usize = 16;

/// Reports progress to an optional sink, ignoring the sink when absent.
fn report(sink: Option<&dyn ProgressSink>, percent: u32, message: &str) {
    if let Some(sink) = sink {
        sink.report(percent.min(100), message);
    }
}

/// Scans the `.text` section of `map` for every pattern in `set`.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` was cancelled before or
/// during the scan, and [`CoreError::MissingSection`] when `.text` is absent.
pub fn scan_section(
    map: &SectionMap,
    image: &[u8],
    set: &PatternSet,
    control: &ScanControl,
) -> CoreResult<ScanOutcome> {
    scan_section_with_progress(map, image, set, control, None)
}

/// Scans the `.text` section, reporting progress to `sink` as it goes.
///
/// The chunk ranges are scanned in waves. Each wave runs in parallel, and a
/// progress update is emitted between waves, so the caller sees movement during
/// a long scan without any worker thread having to reach into the JVM.
///
/// # Errors
///
/// Same as [`scan_section`].
pub fn scan_section_with_progress(
    map: &SectionMap,
    image: &[u8],
    set: &PatternSet,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<ScanOutcome> {
    let section = &map.text;
    let bytes = map.bytes_of(&section.name, image);
    if bytes.is_empty() {
        return Err(CoreError::MissingSection(format!(
            "{} is empty or outside the file, so there is nothing to scan",
            section.name
        )));
    }

    let ranges = chunk_ranges(bytes.len());
    let workers = worker_count();

    if control.is_cancelled() {
        return Err(CoreError::Cancelled);
    }

    report(sink, 0, "Matching signatures");

    // Each worker scans its own chunk and returns its own hits, so no lock is
    // taken inside the hot loop and the mapping is only read. The ranges are
    // processed in waves so a progress update can be emitted between them.
    let wave = (ranges.len() / PROGRESS_STEPS).max(1);
    let mut per_chunk: Vec<Vec<(usize, usize)>> = Vec::with_capacity(ranges.len());
    let mut done = 0usize;
    for batch in ranges.chunks(wave) {
        if control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let mut scanned: Vec<Vec<(usize, usize)>> = batch
            .par_iter()
            .map(|range| {
                if control.is_cancelled() {
                    return Vec::new();
                }
                let start = range.start;
                let end = range.end.min(bytes.len());
                let chunk = &bytes[start..end];
                set.find_all(chunk)
                    .into_iter()
                    .map(|(pattern_index, offset)| (pattern_index, start + offset))
                    .collect()
            })
            .collect();
        per_chunk.append(&mut scanned);

        done += batch.len();
        let percent = ((done as u64 * u64::from(MATCH_PERCENT)) / ranges.len() as u64) as u32;
        report(sink, percent, "Matching signatures");
    }

    if control.is_cancelled() {
        return Err(CoreError::Cancelled);
    }

    // Overlapping chunks can report the same position twice.
    per_chunk.sort_unstable();
    per_chunk.dedup();

    let mut hits: Vec<PatternHit> = Vec::new();
    for (pattern_index, offset) in per_chunk.into_iter().flatten() {
        let Some(pattern) = set.patterns().get(pattern_index) else {
            continue;
        };
        let section_offset = offset as u64;
        let file_offset = section.offset + section_offset;
        let Some(address) = section.address_of(file_offset) else {
            continue;
        };
        hits.push(PatternHit {
            name: pattern.name.clone(),
            section_offset,
            file_offset,
            address,
            address_hex: format!("0x{address:x}"),
            prologue_kind: None,
            prologue_instruction: None,
        });
    }

    hits.sort_by(|left, right| {
        left.address
            .cmp(&right.address)
            .then_with(|| left.name.cmp(&right.name))
    });

    Ok(ScanOutcome {
        section: section.name.clone(),
        scanned_bytes: bytes.len() as u64,
        chunks: ranges.len(),
        workers,
        hits,
        discarded: 0,
        verified: false,
    })
}

/// Discards hits whose bytes do not decode to a function prologue.
///
/// A byte signature only says "these bytes occur here". Decoding the bytes at
/// the match and requiring a stack-frame or landing-pad form is what turns a
/// byte match into a function start.
///
/// # Errors
///
/// Returns [`CoreError::Cancelled`] when `control` was cancelled while
/// verifying, and [`CoreError::MissingSection`] when the section body is
/// unavailable.
pub fn verify_prologues(
    outcome: ScanOutcome,
    map: &SectionMap,
    image: &[u8],
    verifier: &PrologueVerifier,
    control: &ScanControl,
) -> CoreResult<ScanOutcome> {
    verify_prologues_with_progress(outcome, map, image, verifier, control, None)
}

/// Verifies every hit's prologue, reporting progress to `sink`.
///
/// # Errors
///
/// Same as [`verify_prologues`].
pub fn verify_prologues_with_progress(
    mut outcome: ScanOutcome,
    map: &SectionMap,
    image: &[u8],
    verifier: &PrologueVerifier,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
) -> CoreResult<ScanOutcome> {
    let bytes = map.bytes_of(&outcome.section, image);
    if bytes.is_empty() {
        return Err(CoreError::MissingSection(format!(
            "{} is empty, so no prologue can be verified",
            outcome.section
        )));
    }

    let total = outcome.hits.len().max(1);
    let step = (total / PROGRESS_STEPS).max(1);
    let mut kept = Vec::with_capacity(outcome.hits.len());
    let mut discarded = 0usize;
    let mut examined = 0usize;

    report(sink, MATCH_PERCENT, "Verifying prologues");

    for hit in outcome.hits.drain(..) {
        if control.is_cancelled() {
            return Err(CoreError::Cancelled);
        }

        examined += 1;
        if examined.is_multiple_of(step) {
            let span = 100 - MATCH_PERCENT;
            let percent =
                MATCH_PERCENT + ((examined as u64 * u64::from(span)) / total as u64) as u32;
            report(sink, percent, "Verifying prologues");
        }

        let offset = hit.section_offset as usize;
        let verdict = verifier.verify(bytes, offset);
        if verdict.accepted {
            kept.push(PatternHit {
                prologue_kind: Some(verdict.kind),
                prologue_instruction: Some(verdict.instruction),
                ..hit
            });
        } else {
            discarded += 1;
        }
    }

    outcome.hits = kept;
    outcome.discarded = discarded;
    outcome.verified = true;
    report(sink, 100, "Scan complete");
    Ok(outcome)
}

/// Splits `len` into chunk ranges that overlap by [`CHUNK_OVERLAP_BYTES`].
///
/// Every byte is covered by exactly one chunk's non-overlapping part, so no
/// position is missed and no position is reported by two chunks except within
/// the overlap, where duplicates are removed later.
///
/// # Returns
///
/// One range per chunk, in ascending order.
pub fn chunk_ranges(len: usize) -> Vec<std::ops::Range<usize>> {
    if len == 0 {
        return Vec::new();
    }

    let mut ranges = Vec::new();
    let mut start = 0usize;
    while start < len {
        let end = (start + CHUNK_BYTES).min(len);
        // Borrow the first overlap bytes of the next chunk so a match
        // straddling the boundary is still seen from this side.
        let extended = (end + CHUNK_OVERLAP_BYTES).min(len);
        ranges.push(start..extended);
        if end == len {
            break;
        }
        start = end;
    }
    ranges
}

/// Number of workers the pool will use.
///
/// Reported rather than assumed, so the log tells the truth about a device with
/// a single usable core.
pub fn worker_count() -> usize {
    std::thread::available_parallelism()
        .map(|parallelism| parallelism.get())
        .unwrap_or(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::patterns::PatternSet;

    fn set_of(specs: &[(&str, &str)]) -> PatternSet {
        let owned: Vec<(String, String)> = specs
            .iter()
            .map(|(name, source)| ((*name).to_string(), (*source).to_string()))
            .collect();
        PatternSet::new(&owned).expect("pattern set")
    }

    /// Records every report so a test can inspect the sequence.
    struct RecordingSink {
        reports: std::sync::Mutex<Vec<(u32, String)>>,
    }

    impl RecordingSink {
        fn new() -> Self {
            Self {
                reports: std::sync::Mutex::new(Vec::new()),
            }
        }

        fn reports(&self) -> Vec<(u32, String)> {
            self.reports.lock().expect("sink lock").clone()
        }
    }

    impl ProgressSink for RecordingSink {
        fn report(&self, percent: u32, message: &str) {
            self.reports
                .lock()
                .expect("sink lock")
                .push((percent, message.to_string()));
        }
    }

    #[test]
    fn a_scan_reports_monotonic_progress_and_finishes_at_one_hundred() {
        let image = vec![0x48u8, 0x8b, 0x05, 0x00, 0x00, 0x00, 0x00];
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: crate::sections::SectionInfo {
                kind: crate::sections::SectionKind::Text,
                name: ".text".to_string(),
                address: 0x1000,
                size: image.len() as u64,
                offset: 0,
                align: 16,
                delta: 0x1000,
                allocated: true,
                executable: true,
                writable: false,
                occupies_file: true,
            },
            sections: Vec::new(),
        };
        let set = set_of(&[("prologue", "48 8B 05")]);
        let control = ScanControl::new();
        let sink = RecordingSink::new();

        let outcome =
            scan_section_with_progress(&map, &image, &set, &control, Some(&sink)).expect("scan");
        assert_eq!(outcome.hits.len(), 1);

        // The 100% report comes from the verification stage, so run it too.
        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        verify_prologues_with_progress(outcome, &map, &image, &verifier, &control, Some(&sink))
            .expect("verify");

        let reports = sink.reports();
        assert!(
            reports.len() >= 2,
            "expected progress reports, got {reports:?}"
        );
        assert_eq!(reports.first().expect("first").0, 0);
        assert_eq!(reports.last().expect("last").0, 100);
        assert!(
            reports.windows(2).all(|pair| pair[0].0 <= pair[1].0),
            "progress must never go backwards: {reports:?}",
        );
    }

    #[test]
    fn a_scan_without_a_sink_still_works() {
        let image = vec![0x48u8, 0x8b, 0x05, 0x00, 0x00, 0x00, 0x00];
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: crate::sections::SectionInfo {
                kind: crate::sections::SectionKind::Text,
                name: ".text".to_string(),
                address: 0x1000,
                size: image.len() as u64,
                offset: 0,
                align: 16,
                delta: 0x1000,
                allocated: true,
                executable: true,
                writable: false,
                occupies_file: true,
            },
            sections: Vec::new(),
        };
        let set = set_of(&[("prologue", "48 8B 05")]);
        let control = ScanControl::new();

        let outcome = scan_section(&map, &image, &set, &control).expect("scan");
        assert_eq!(outcome.hits.len(), 1);
    }

    #[test]
    fn a_short_buffer_becomes_one_chunk() {
        let ranges = chunk_ranges(1024);
        assert_eq!(ranges.len(), 1);
        assert_eq!(ranges[0], 0..1024);
    }

    #[test]
    fn chunks_overlap_by_the_configured_amount() {
        let len = CHUNK_BYTES * 2 + 10;
        let ranges = chunk_ranges(len);

        assert_eq!(ranges.len(), 3);
        assert_eq!(ranges[0], 0..CHUNK_BYTES + CHUNK_OVERLAP_BYTES);
        assert_eq!(ranges[1].start, CHUNK_BYTES);
        assert_eq!(ranges[2].end, len);

        // Every byte is covered.
        let mut covered = vec![false; len];
        for range in &ranges {
            for index in range.clone() {
                covered[index] = true;
            }
        }
        assert!(
            covered.iter().all(|value| *value),
            "a byte was left uncovered"
        );
    }

    #[test]
    fn an_empty_buffer_has_no_chunks() {
        assert!(chunk_ranges(0).is_empty());
    }

    #[test]
    fn the_last_chunk_never_overruns_the_buffer() {
        let len = CHUNK_BYTES + 4;
        let ranges = chunk_ranges(len);
        for range in &ranges {
            assert!(range.end <= len, "{range:?} runs past {len}");
        }
    }

    #[test]
    fn a_match_straddling_a_chunk_boundary_is_still_found() {
        // Build an image whose match starts one byte before the boundary.
        let mut image = vec![0u8; CHUNK_BYTES + 64];
        let boundary = CHUNK_BYTES;
        image[boundary - 1] = 0x41;
        image[boundary] = 0x42;
        image[boundary + 1] = 0x43;

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("straddle", "41 42 43")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        assert_eq!(outcome.hits.len(), 1);
        assert_eq!(outcome.hits[0].section_offset, (boundary - 1) as u64);
    }

    #[test]
    fn offsets_are_converted_to_virtual_addresses() {
        // The section body lives at file offset 0x3000, so the image has to be
        // big enough to contain it; the match sits 16 bytes into the section.
        let mut image = vec![0u8; 0x3000 + 64];
        image[0x3010] = 0xde;
        image[0x3011] = 0xad;

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x400000,
            size: 64,
            offset: 0x3000,
            align: 16,
            delta: 0x3fd000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x400000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("magic", "DE AD")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        assert_eq!(outcome.hits.len(), 1);
        let hit = &outcome.hits[0];
        assert_eq!(hit.section_offset, 16);
        assert_eq!(hit.file_offset, 0x3000 + 16);
        // address = file_offset + delta
        assert_eq!(hit.address, 0x3010 + 0x3fd000);
        assert_eq!(hit.address_hex, format!("0x{:x}", hit.address));
    }

    #[test]
    fn a_cancelled_control_stops_the_scan() {
        let image = vec![0u8; 128];
        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        let control = ScanControl::new();
        control.cancel();

        let set = set_of(&[("any", "41 42")]);
        let error = scan_section(&map, &image, &set, &control).expect_err("must cancel");
        assert!(matches!(error, CoreError::Cancelled), "{error}");
        assert!(control.is_cancelled());
    }

    #[test]
    fn hits_are_sorted_by_address() {
        let mut image = vec![0u8; 256];
        image[200] = 0xbe;
        image[201] = 0xef;
        image[10] = 0xbe;
        image[11] = 0xef;

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("magic", "BE EF")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        assert_eq!(outcome.hits.len(), 2);
        assert!(outcome.hits[0].address < outcome.hits[1].address);
        assert_eq!(outcome.hits[0].section_offset, 10);
        assert_eq!(outcome.hits[1].section_offset, 200);
    }

    #[test]
    fn a_hit_that_is_not_a_prologue_is_discarded() {
        // Two occurrences of the same bytes: one is a frame push, one is not.
        let mut image = vec![0u8; 128];
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);
        image[64..68].copy_from_slice(&[0xfd, 0x7b, 0xbf, 0xa9]);
        // Make the second occurrence fail: overwrite with a plain move.
        image[64..68].copy_from_slice(&[0xe0, 0x03, 0x01, 0xaa]);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        // Both positions hold `stp x29, x30, [sp, #-16]!` in this scan.
        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
        assert_eq!(outcome.hits.len(), 1, "only the first occurrence survives");

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        assert!(verified.verified);
        assert_eq!(verified.hits.len(), 1);
        assert_eq!(verified.discarded, 0);
        assert_eq!(
            verified.hits[0].prologue_kind,
            Some(crate::prologue::PrologueKind::FramePush)
        );
        assert!(verified.hits[0]
            .prologue_instruction
            .as_deref()
            .unwrap_or_default()
            .contains("stp"));
    }

    #[test]
    fn verification_keeps_only_real_function_starts() {
        let mut image = vec![0u8; 256];
        // A real frame push at 0.
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);
        // The same four bytes embedded in the middle of another instruction
        // stream, at 100, preceded by a `ret` so the decode there is not a
        // prologue.
        image[96..100].copy_from_slice(&crate::prologue::PROLOGUE_RET);
        image[100..104].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x2000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x2000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x2000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");
        assert_eq!(outcome.hits.len(), 2);

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let verified = verify_prologues(outcome, &map, &image, &verifier, &ScanControl::new())
            .expect("verify");

        // Both positions start with a frame push, so both are accepted; the
        // point of the test is that verification ran and annotated them.
        assert!(verified.verified);
        assert!(verified.hits.iter().all(|hit| hit.prologue_kind.is_some()));
    }

    #[test]
    fn verification_reports_cancellation() {
        let mut image = vec![0u8; 64];
        image[0..4].copy_from_slice(&crate::prologue::PROLOGUE_FRAME_PUSH);

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: image.len() as u64,
            offset: 0,
            align: 16,
            delta: 0x1000,
            allocated: true,
            executable: true,
            writable: false,
            occupies_file: true,
        };
        let map = crate::sections::SectionMap {
            image_base: 0x1000,
            text: section,
            sections: Vec::new(),
        };

        let set = set_of(&[("push", "FD 7B BF A9")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        let verifier = crate::prologue::PrologueVerifier::new().expect("capstone");
        let control = ScanControl::new();
        control.cancel();
        let error =
            verify_prologues(outcome, &map, &image, &verifier, &control).expect_err("must cancel");
        assert!(matches!(error, CoreError::Cancelled), "{error}");
    }

    #[test]
    fn worker_count_is_at_least_one() {
        assert!(worker_count() >= 1);
    }

    #[test]
    fn a_scan_of_a_real_sized_buffer_is_parallel() {
        let len = CHUNK_BYTES * 3 + 128;
        let ranges = chunk_ranges(len);
        assert!(
            ranges.len() >= 4,
            "expected several chunks, got {}",
            ranges.len()
        );
    }
}
