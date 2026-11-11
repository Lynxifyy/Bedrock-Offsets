//! The pattern-matching pass.
//!
//! Each chunk is searched independently by a Rayon worker, so the hot loop
//! takes no lock and only reads the mapping. Progress is reported between
//! waves, which keeps every update on the calling thread.

use rayon::prelude::*;

use crate::error::{CoreError, CoreResult};
use crate::patterns::PatternSet;
use crate::sections::SectionMap;
use crate::verify::VerificationStatus;

use super::chunk::{chunk_ranges, worker_count};
use super::{
    report, PageReleaser, PatternHit, ProgressSink, ScanControl, ScanOutcome, MATCH_PERCENT,
    MAX_HITS_PER_PATTERN, MAX_HITS_TOTAL, PROGRESS_STEPS,
};

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
    scan_section_releasing_pages(map, image, set, control, sink, None)
}

/// Scans the `.text` section, offering each scanned wave for reclamation.
///
/// Identical to [`scan_section_with_progress`], but each wave of chunks is
/// offered to `releaser` once it has been scanned. A caller that mapped the
/// image from a file can use this to drop the pages behind the wave, which keeps
/// the resident set near one wave instead of the whole section.
///
/// # Errors
///
/// Same as [`scan_section`].
pub fn scan_section_releasing_pages(
    map: &SectionMap,
    image: &[u8],
    set: &PatternSet,
    control: &ScanControl,
    sink: Option<&dyn ProgressSink>,
    releaser: Option<&dyn PageReleaser>,
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

        // The wave has been read into `per_chunk`; its pages are no longer
        // needed, so offer them for reclamation before the next wave faults in
        // more. The section body starts at `section.offset` inside the image.
        if let Some(releaser) = releaser {
            if let (Some(first), Some(last)) = (batch.first(), batch.last()) {
                let start = section.offset as usize + first.start;
                let end = section.offset as usize + last.end.min(bytes.len());
                releaser.release(start, end);
            }
        }

        done += batch.len();
        let percent = ((done as u64 * u64::from(MATCH_PERCENT)) / ranges.len() as u64) as u32;
        report(sink, percent, "Matching signatures");
    }

    if control.is_cancelled() {
        return Err(CoreError::Cancelled);
    }

    // Overlapping chunks can report the same position twice. The hits must be
    // flattened before deduplicating: sorting and deduplicating the per-chunk
    // vectors only removes identical chunks, not duplicated positions.
    let mut positions: Vec<(usize, usize)> = per_chunk.into_iter().flatten().collect();
    positions.sort_unstable();
    positions.dedup();

    // Candidates arrive sorted by (pattern, offset), so the first
    // `MAX_HITS_PER_PATTERN` of each pattern are its lowest offsets. The caps
    // keep a loose signature from exhausting memory; the count that was dropped
    // is surfaced through `truncated` rather than silently ignored.
    let mut hits: Vec<PatternHit> = Vec::new();
    let mut per_pattern: std::collections::HashMap<usize, usize> = std::collections::HashMap::new();
    let mut truncated = false;
    for (pattern_index, offset) in positions {
        let Some(pattern) = set.patterns().get(pattern_index) else {
            continue;
        };
        let seen = per_pattern.entry(pattern_index).or_insert(0);
        if *seen >= MAX_HITS_PER_PATTERN || hits.len() >= MAX_HITS_TOTAL {
            truncated = true;
            continue;
        }
        *seen += 1;
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
            verification_status: VerificationStatus::Unverified,
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
        truncated,
        verification: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scanner::test_support::{set_of, RecordingReleaser, RecordingSink};
    use crate::scanner::{verify_prologues_with_progress, CHUNK_BYTES};

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
    fn a_match_inside_the_chunk_overlap_is_reported_once() {
        // A match that starts after the boundary but inside the first chunk's
        // 32-byte overlap is seen by chunk 0 (via the overlap) and by chunk 1
        // (as its own start), so two chunks report the same position. A second
        // match, past the overlap, belongs to chunk 1 alone and makes the two
        // per-chunk vectors differ, which is when deduplicating the vectors
        // instead of the flattened hits stops working.
        let mut image = vec![0u8; CHUNK_BYTES + 256];
        let shared = CHUNK_BYTES + 8;
        let later = CHUNK_BYTES + 128;
        for at in [shared, later] {
            image[at] = 0x41;
            image[at + 1] = 0x42;
            image[at + 2] = 0x43;
        }

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

        let set = set_of(&[("overlap", "41 42 43")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        let offsets: Vec<u64> = outcome.hits.iter().map(|hit| hit.section_offset).collect();
        assert_eq!(
            offsets,
            vec![shared as u64, later as u64],
            "{:?}",
            outcome.hits
        );
    }

    #[test]
    fn a_loose_pattern_is_capped_instead_of_growing_without_bound() {
        // A single fixed byte matches one position in 256. On a large section
        // that is over a million candidates, so the cap must bound the result
        // and the scan must say it truncated rather than drop silently.
        let size = MAX_HITS_PER_PATTERN * 300;
        let mut image = vec![0u8; size];
        // Place one match every 256 bytes so the count exceeds the cap.
        for at in (0..size).step_by(256) {
            image[at] = 0x91;
        }

        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: size as u64,
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

        let set = set_of(&[("loose", "91")]);
        let outcome = scan_section(&map, &image, &set, &ScanControl::new()).expect("scan");

        assert_eq!(outcome.hits.len(), MAX_HITS_PER_PATTERN);
        assert!(outcome.truncated, "a capped scan must say so");
    }

    #[test]
    fn scanning_offers_each_wave_for_reclamation() {
        // The scan must tell the caller which pages it no longer needs, or a
        // 342 MB `.text` stays resident for the whole pass. The ranges have to
        // ascend and cover the section, or a page would never be released.
        let size = CHUNK_BYTES * 4;
        let image = vec![0u8; size];
        let section = crate::sections::SectionInfo {
            name: ".text".to_string(),
            kind: crate::sections::SectionKind::Text,
            address: 0x1000,
            size: size as u64,
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

        let set = set_of(&[("never", "AA BB CC DD EE")]);
        let releaser = RecordingReleaser::new();
        let outcome = scan_section_releasing_pages(
            &map,
            &image,
            &set,
            &ScanControl::new(),
            None,
            Some(&releaser),
        )
        .expect("scan");

        assert_eq!(outcome.scanned_bytes, size as u64);
        let ranges = releaser.ranges();
        assert!(!ranges.is_empty(), "no range was offered for reclamation");
        assert_eq!(ranges.first().expect("first").0, 0);
        assert_eq!(ranges.last().expect("last").1, size);
        // Consecutive ranges may overlap by the chunk overlap, which is
        // harmless: releasing a page twice is idempotent. They must not go
        // backwards, and every range must be non-empty.
        assert!(
            ranges.iter().all(|(start, end)| end > start),
            "a released range was empty: {ranges:?}"
        );
        assert!(
            ranges.windows(2).all(|pair| pair[0].0 < pair[1].0),
            "ranges must ascend: {ranges:?}"
        );
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
}
