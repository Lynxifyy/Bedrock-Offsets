//! Signature patterns: parsing, compilation and matching.
//!
//! A pattern is written the way an offset hunter writes it, with `??` for a
//! byte that may hold anything:
//!
//! ```text
//! 48 8B ?? ?? 89
//! ```
//!
//! Comparing the mask at every offset would be quadratic in the worst case.
//! Instead each pattern is split into its *literal runs* — the stretches of
//! consecutive fixed bytes between wildcards — and every run is handed to a
//! single Aho-Corasick automaton. One pass over the buffer yields all candidate
//! positions; each candidate is then anchored back to the start of its pattern
//! and verified against the mask. A pattern made only of wildcards has no run
//! and is rejected, because it would match everywhere.

use std::time::Instant;

use aho_corasick::{AhoCorasick, AhoCorasickBuilder, MatchKind};
use serde::Serialize;

use crate::error::{CoreError, CoreResult};

/// Longest accepted pattern, in bytes.
///
/// Real prologue and vtable signatures are well under this. The cap keeps a
/// pathological input from building a huge automaton.
pub const MAX_PATTERN_BYTES: usize = 512;

/// A stretch of consecutive fixed bytes inside a pattern.
#[derive(Debug, Clone, PartialEq, Eq)]
struct LiteralRun {
    /// Index of the pattern this run belongs to.
    pattern_index: usize,
    /// Offset of the run's first byte within the pattern.
    pattern_offset: usize,
    /// The fixed bytes themselves.
    bytes: Vec<u8>,
}

/// A compiled signature: the mask plus the literal runs searched for.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Pattern {
    /// Name the caller uses to refer to this signature.
    pub name: String,
    /// The original text, kept so logs and JSON can echo it back.
    pub source: String,
    /// One entry per byte position: `true` means "any byte is acceptable".
    pub wildcards: Vec<bool>,
    /// The byte expected at each position; wildcard positions hold zero.
    pub bytes: Vec<u8>,
    /// Indices of the non-wildcard positions, in order.
    pub literal_positions: Vec<usize>,
}

impl Pattern {
    /// Total number of bytes the pattern spans.
    pub fn len(&self) -> usize {
        self.wildcards.len()
    }

    /// Splits the pattern into its literal runs.
    fn literal_runs(&self) -> Vec<(usize, Vec<u8>)> {
        let mut runs = Vec::new();
        let mut current_start: Option<usize> = None;
        let mut current = Vec::new();

        for index in 0..self.len() {
            if self.wildcards[index] {
                if let Some(start) = current_start.take() {
                    runs.push((start, std::mem::take(&mut current)));
                }
            } else {
                if current_start.is_none() {
                    current_start = Some(index);
                }
                current.push(self.bytes[index]);
            }
        }
        if let Some(start) = current_start {
            runs.push((start, current));
        }
        runs
    }

    /// Whether the pattern spans no bytes at all.
    pub fn is_empty(&self) -> bool {
        self.wildcards.is_empty()
    }

    /// Whether the pattern contains no wildcards.
    pub fn is_exact(&self) -> bool {
        self.wildcards.iter().all(|wildcard| !*wildcard)
    }

    /// Verifies a candidate against the wildcard mask.
    ///
    /// `start` is the offset of the first byte of the candidate.
    ///
    /// # Returns
    ///
    /// `true` when every non-wildcard position matches.
    pub fn matches_at(&self, data: &[u8], start: usize) -> bool {
        let end = start.saturating_add(self.len());
        if end > data.len() {
            return false;
        }
        self.wildcards
            .iter()
            .enumerate()
            .all(|(index, wildcard)| *wildcard || data[start + index] == self.bytes[index])
    }
}

/// Compiles a pattern written as space-separated hex bytes.
///
/// `??` (and `?`) mark a wildcard byte. Hex is case-insensitive.
///
/// # Errors
///
/// Returns [`CoreError::InvalidPattern`] when a token is not a hex byte or a
/// wildcard, when the pattern is empty or too long, or when it is made up
/// entirely of wildcards.
pub fn compile(name: &str, source: &str) -> CoreResult<Pattern> {
    let tokens: Vec<&str> = source.split_whitespace().collect();
    if tokens.is_empty() {
        return Err(CoreError::InvalidPattern(format!(
            "{name}: the pattern is empty"
        )));
    }
    if tokens.len() > MAX_PATTERN_BYTES {
        return Err(CoreError::InvalidPattern(format!(
            "{name}: the pattern spans {} bytes, above the {MAX_PATTERN_BYTES} byte limit",
            tokens.len()
        )));
    }

    let mut wildcards = Vec::with_capacity(tokens.len());
    let mut bytes = Vec::with_capacity(tokens.len());
    let mut literal_positions = Vec::with_capacity(tokens.len());

    for (index, token) in tokens.iter().enumerate() {
        if token.chars().all(|character| character == '?') {
            wildcards.push(true);
            bytes.push(0);
            continue;
        }
        if token.len() != 2 {
            return Err(CoreError::InvalidPattern(format!(
                "{name}: token {index} ({token}) is not a two-digit hex byte or a wildcard"
            )));
        }
        let value = u8::from_str_radix(token, 16).map_err(|_| {
            CoreError::InvalidPattern(format!("{name}: token {index} ({token}) is not a hex byte"))
        })?;
        wildcards.push(false);
        bytes.push(value);
        literal_positions.push(index);
    }

    if literal_positions.is_empty() {
        return Err(CoreError::InvalidPattern(format!(
            "{name}: the pattern is all wildcards, so it would match everywhere"
        )));
    }

    Ok(Pattern {
        name: name.to_string(),
        source: source.to_string(),
        wildcards,
        bytes,
        literal_positions,
    })
}

/// A set of compiled patterns and the automaton built from their literal runs.
#[derive(Debug)]
pub struct PatternSet {
    patterns: Vec<Pattern>,
    runs: Vec<LiteralRun>,
    automaton: Option<AhoCorasick>,
    compile_micros: u128,
}

impl PatternSet {
    /// Compiles `specs` into a matcher.
    ///
    /// `specs` is a list of `(name, source)` pairs.
    ///
    /// # Errors
    ///
    /// Returns [`CoreError::InvalidPattern`] when any pattern is unusable, and
    /// [`CoreError::InvalidElf`] when the automaton cannot be built.
    pub fn new(specs: &[(String, String)]) -> CoreResult<Self> {
        let started = Instant::now();

        let mut patterns = Vec::with_capacity(specs.len());
        for (name, source) in specs {
            patterns.push(compile(name, source)?);
        }

        let mut runs = Vec::new();
        for (index, pattern) in patterns.iter().enumerate() {
            for (pattern_offset, bytes) in pattern.literal_runs() {
                runs.push(LiteralRun {
                    pattern_index: index,
                    pattern_offset,
                    bytes,
                });
            }
        }

        let automaton = build_automaton(&runs)?;

        Ok(Self {
            patterns,
            runs,
            automaton,
            compile_micros: started.elapsed().as_micros(),
        })
    }

    /// The compiled patterns, in the order they were supplied.
    pub fn patterns(&self) -> &[Pattern] {
        &self.patterns
    }

    /// Wall-clock time spent parsing and building the automaton, in microseconds.
    pub fn compile_micros(&self) -> u128 {
        self.compile_micros
    }

    /// Whether the set holds no patterns, so matching is a no-op.
    pub fn is_empty(&self) -> bool {
        self.patterns.is_empty()
    }

    /// Finds every occurrence of every pattern in `data`.
    ///
    /// Candidate positions come from the automaton; the wildcard bytes are then
    /// verified by hand. Exact patterns are emitted directly, because the
    /// automaton already proved them.
    ///
    /// # Returns
    ///
    /// `(pattern index, offset)` pairs, sorted by offset then pattern index.
    pub fn find_all(&self, data: &[u8]) -> Vec<(usize, usize)> {
        let Some(automaton) = self.automaton.as_ref() else {
            return Vec::new();
        };
        if data.is_empty() {
            return Vec::new();
        }

        let mut hits = Vec::new();
        for found in automaton.find_overlapping_iter(data) {
            let Some(run) = self.runs.get(found.pattern().as_usize()) else {
                continue;
            };
            let Some(pattern) = self.patterns.get(run.pattern_index) else {
                continue;
            };

            // The run starts `pattern_offset` bytes into the pattern, so the
            // candidate pattern begins that many bytes before the match.
            let Some(start) = found.start().checked_sub(run.pattern_offset) else {
                continue;
            };
            if pattern.matches_at(data, start) {
                hits.push((run.pattern_index, start));
            }
        }

        hits.sort_unstable();
        hits.dedup();
        hits
    }
}

/// Builds an automaton over the literal runs of every pattern.
///
/// Runs are matched independently, so the automaton is left in standard (not
/// leftmost) mode and overlapping matches are reported.
fn build_automaton(runs: &[LiteralRun]) -> CoreResult<Option<AhoCorasick>> {
    if runs.is_empty() {
        return Ok(None);
    }

    let literals: Vec<&[u8]> = runs.iter().map(|run| run.bytes.as_slice()).collect();

    AhoCorasickBuilder::new()
        .match_kind(MatchKind::Standard)
        .build(literals)
        .map(Some)
        .map_err(|error| CoreError::InvalidPattern(format!("cannot build the matcher: {error}")))
}

/// Renders a byte slice as an upper-case, space-separated hex string.
pub fn format_bytes(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_patterns_parse_into_bytes() {
        let pattern = compile("prologue", "48 8B 05").expect("valid pattern");
        assert_eq!(pattern.bytes, vec![0x48, 0x8b, 0x05]);
        assert!(pattern.is_exact());
        assert_eq!(pattern.literal_positions, vec![0, 1, 2]);
        assert_eq!(pattern.len(), 3);
        assert!(!pattern.is_empty());
    }

    #[test]
    fn wildcards_are_recorded_and_skipped_for_literals() {
        let pattern = compile("masked", "48 8B ?? ?? 89").expect("valid pattern");
        assert_eq!(pattern.wildcards, vec![false, false, true, true, false]);
        assert_eq!(pattern.literal_positions, vec![0, 1, 4]);
        assert_eq!(pattern.bytes, vec![0x48, 0x8b, 0, 0, 0x89]);
        assert!(!pattern.is_exact());
    }

    #[test]
    fn wildcards_split_the_pattern_into_runs() {
        let pattern = compile("masked", "48 8B ?? ?? 89").expect("valid pattern");
        assert_eq!(
            pattern.literal_runs(),
            vec![(0, vec![0x48, 0x8b]), (4, vec![0x89])]
        );
    }

    #[test]
    fn a_leading_wildcard_keeps_the_run_offset_correct() {
        let pattern = compile("leading", "?? 41 42").expect("valid pattern");
        assert_eq!(pattern.literal_runs(), vec![(1, vec![0x41, 0x42])]);
    }

    #[test]
    fn hex_is_case_insensitive() {
        let lower = compile("lower", "de ad be ef").expect("valid pattern");
        let upper = compile("upper", "DE AD BE EF").expect("valid pattern");
        assert_eq!(lower.bytes, upper.bytes);
    }

    #[test]
    fn an_empty_pattern_is_rejected() {
        let error = compile("empty", "   ").expect_err("empty must fail");
        assert!(matches!(error, CoreError::InvalidPattern(_)), "{error}");
    }

    #[test]
    fn an_all_wildcard_pattern_is_rejected() {
        let error = compile("all", "?? ?? ??").expect_err("all wildcards must fail");
        assert!(error.to_string().contains("all wildcards"), "{error}");
    }

    #[test]
    fn a_malformed_token_is_rejected() {
        let error = compile("bad", "48 8B ZZ").expect_err("bad token must fail");
        assert!(error.to_string().contains("not a hex byte"), "{error}");

        let error = compile("short", "4 8B").expect_err("short token must fail");
        assert!(error.to_string().contains("two-digit"), "{error}");
    }

    #[test]
    fn an_overlong_pattern_is_rejected() {
        let source = vec!["48"; MAX_PATTERN_BYTES + 1].join(" ");
        let error = compile("long", &source).expect_err("overlong must fail");
        assert!(error.to_string().contains("byte limit"), "{error}");
    }

    #[test]
    fn matching_respects_the_wildcard_mask() {
        let pattern = compile("masked", "48 8B ?? ?? 89").expect("valid pattern");

        let good = [0x48, 0x8b, 0x11, 0x22, 0x89, 0xff];
        assert!(pattern.matches_at(&good, 0));

        let bad = [0x48, 0x8b, 0x11, 0x22, 0x88, 0xff];
        assert!(!pattern.matches_at(&bad, 0));

        // A candidate that runs off the end of the buffer never matches.
        assert!(!pattern.matches_at(&good, 2));
    }

    #[test]
    fn the_scanner_finds_exact_matches_and_ignores_garbage() {
        let set = PatternSet::new(&[("prologue".to_string(), "48 8B 05".to_string())])
            .expect("pattern set");

        let mut data = vec![0x00u8; 64];
        data[8..11].copy_from_slice(&[0x48, 0x8b, 0x05]);
        data[40..43].copy_from_slice(&[0x48, 0x8b, 0x06]);

        let hits = set.find_all(&data);
        assert_eq!(hits, vec![(0, 8)]);
    }

    #[test]
    fn the_scanner_verifies_wildcard_bytes() {
        let set = PatternSet::new(&[("masked".to_string(), "48 8B ?? ?? 89".to_string())])
            .expect("pattern set");

        let mut data = vec![0u8; 64];
        data[4..9].copy_from_slice(&[0x48, 0x8b, 0xaa, 0xbb, 0x89]);
        data[20..25].copy_from_slice(&[0x48, 0x8b, 0xaa, 0xbb, 0x88]);

        let hits = set.find_all(&data);
        assert_eq!(hits, vec![(0, 4)]);
    }

    #[test]
    fn a_leading_wildcard_still_anchors_the_match() {
        let set = PatternSet::new(&[("leading".to_string(), "?? 41 42".to_string())])
            .expect("pattern set");
        let data = [0x99, 0x41, 0x42, 0x00, 0x41, 0x43];

        let hits = set.find_all(&data);
        assert_eq!(hits, vec![(0, 0)]);
    }

    #[test]
    fn a_match_running_past_the_buffer_is_ignored() {
        let set = PatternSet::new(&[("tail".to_string(), "41 42 ?? 43".to_string())])
            .expect("pattern set");

        // The literal run `41 42` sits at the very end, so the full pattern
        // would extend past the buffer and must not be reported.
        let data = [0x00, 0x41, 0x42];
        assert!(set.find_all(&data).is_empty());
    }

    #[test]
    fn several_patterns_report_their_own_index() {
        let set = PatternSet::new(&[
            ("first".to_string(), "DE AD".to_string()),
            ("second".to_string(), "BE EF".to_string()),
        ])
        .expect("pattern set");

        let data = [0x00, 0xde, 0xad, 0x00, 0xbe, 0xef];

        let hits = set.find_all(&data);
        assert_eq!(hits, vec![(0, 1), (1, 4)]);
        assert_eq!(set.patterns()[0].name, "first");
        assert_eq!(set.patterns()[1].name, "second");
    }

    #[test]
    fn overlapping_matches_are_all_reported() {
        let set = PatternSet::new(&[("aa".to_string(), "41 41".to_string())]).expect("pattern set");
        let data = [0x41, 0x41, 0x41];

        let hits = set.find_all(&data);
        assert_eq!(hits, vec![(0, 0), (0, 1)]);
    }

    #[test]
    fn an_empty_set_matches_nothing() {
        let set = PatternSet::new(&[]).expect("empty set");
        assert!(set.is_empty());
        assert!(set.find_all(&[1, 2, 3]).is_empty());
        assert!(set.find_all(&[]).is_empty());
    }

    #[test]
    fn compile_time_is_measured() {
        let set =
            PatternSet::new(&[("one".to_string(), "48 8B 05".to_string())]).expect("pattern set");
        // Only a lower bound is asserted: the clock is not monotonic enough for
        // an upper bound to be meaningful in a test.
        assert!(set.compile_micros() < 1_000_000);
    }

    #[test]
    fn byte_formatting_is_upper_case_and_padded() {
        assert_eq!(format_bytes(&[0x00, 0x0f, 0xa5]), "00 0F A5");
        assert_eq!(format_bytes(&[]), "");
    }
}
