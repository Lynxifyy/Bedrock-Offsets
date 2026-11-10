//! Chunk planning for a parallel scan.
//!
//! A section is split into fixed-size ranges, each of which one worker scans on
//! its own. Consecutive ranges overlap so a match that straddles a boundary is
//! still seen, and the overlap is small enough that the duplicated work is
//! negligible.

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
