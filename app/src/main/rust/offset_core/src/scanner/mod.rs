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
//!
//! The work is split across three modules: [`chunk`] plans the ranges,
//! [`matcher`] runs the pattern search and [`verifier`] keeps only the hits that
//! decode to a function prologue.

pub mod chunk;
pub mod matcher;
pub mod verifier;

use std::sync::atomic::{AtomicBool, Ordering};

use serde::Serialize;

use crate::prologue::PrologueKind;
use crate::verify::VerificationStatus;

pub use chunk::{chunk_ranges, worker_count, CHUNK_BYTES, CHUNK_OVERLAP_BYTES};
pub use matcher::{scan_section, scan_section_releasing_pages, scan_section_with_progress};
pub use verifier::{verify_prologues, verify_prologues_with_progress};

/// Fixtures shared by the module tests.
#[cfg(test)]
pub(crate) mod test_support {
    use crate::patterns::PatternSet;
    use crate::scanner::ProgressSink;

    /// Compiles `specs` into a pattern set, panicking on a bad spec.
    pub(crate) fn set_of(specs: &[(&str, &str)]) -> PatternSet {
        let owned: Vec<(String, String)> = specs
            .iter()
            .map(|(name, source)| ((*name).to_string(), (*source).to_string()))
            .collect();
        PatternSet::new(&owned).expect("pattern set")
    }

    /// Records every report so a test can inspect the sequence.
    pub(crate) struct RecordingSink {
        reports: std::sync::Mutex<Vec<(u32, String)>>,
    }

    impl RecordingSink {
        /// An empty recorder.
        pub(crate) fn new() -> Self {
            Self {
                reports: std::sync::Mutex::new(Vec::new()),
            }
        }

        /// Everything recorded so far, in order.
        pub(crate) fn reports(&self) -> Vec<(u32, String)> {
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

    /// Records every range offered for reclamation.
    pub(crate) struct RecordingReleaser {
        ranges: std::sync::Mutex<Vec<(usize, usize)>>,
    }

    impl RecordingReleaser {
        /// An empty recorder.
        pub(crate) fn new() -> Self {
            Self {
                ranges: std::sync::Mutex::new(Vec::new()),
            }
        }

        /// Everything recorded so far, in order.
        pub(crate) fn ranges(&self) -> Vec<(usize, usize)> {
            self.ranges.lock().expect("releaser lock").clone()
        }
    }

    impl crate::scanner::PageReleaser for RecordingReleaser {
        fn release(&self, start: usize, end: usize) {
            self.ranges
                .lock()
                .expect("releaser lock")
                .push((start, end));
        }
    }
}

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
    /// How the hit's address agrees with the library's symbol table.
    #[serde(default)]
    pub verification_status: VerificationStatus,
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
    /// Whether the hit list was capped at [`MAX_HITS_PER_PATTERN`] /
    /// [`MAX_HITS_TOTAL`]. A capped scan still reports the lowest offsets.
    #[serde(default)]
    pub truncated: bool,
    /// Symbol cross-check totals, present once the check has run.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verification: Option<crate::verify::VerificationSummary>,
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

/// Tells the kernel that a byte range of the mapping is no longer needed.
///
/// Scanning faults in every page it reads, so the resident set climbs to the
/// size of `.text` even though nothing is copied. Once a wave of chunks has been
/// scanned its pages are dead weight: the file is still on disk, so dropping
/// them only costs a re-read if a later phase touches the range again. Releasing
/// them wave by wave keeps the peak close to one wave rather than the whole
/// section.
///
/// The hook is passed in rather than reaching for the mapping so the scanner
/// stays independent of how the image was obtained.
pub trait PageReleaser {
    /// Offers the range `start..end` for reclamation. Best-effort.
    fn release(&self, start: usize, end: usize);
}

/// Percent of the scan budget spent matching patterns; the rest is verification.
const MATCH_PERCENT: u32 = 55;

/// Number of progress updates a scan emits, so the cost is independent of size.
const PROGRESS_STEPS: usize = 16;

/// Most hits kept for any one pattern.
///
/// A loose signature -- one fixed byte out of eight, say -- matches roughly one
/// position in 256, which on a 300 MB `.text` is over a million candidates. Each
/// candidate costs a `PatternHit`, so without a bound a single bad signature
/// would exhaust the memory the engine promises to stay under. The lowest
/// offsets are kept, which is deterministic because the candidates are sorted.
pub const MAX_HITS_PER_PATTERN: usize = 4096;

/// Most hits kept across every pattern in one scan.
pub const MAX_HITS_TOTAL: usize = 32768;

/// Reports progress to an optional sink, ignoring the sink when absent.
fn report(sink: Option<&dyn ProgressSink>, percent: u32, message: &str) {
    if let Some(sink) = sink {
        sink.report(percent.min(100), message);
    }
}
