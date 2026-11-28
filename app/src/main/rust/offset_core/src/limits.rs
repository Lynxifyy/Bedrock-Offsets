//! Machine limits the engine adapts to.
//!
//! The engine runs on the owner's tablet, not on a build server. Two numbers
//! matter there and neither is a constant: how many cores a worker pool may
//! use, and how much memory the process can afford before the low-memory killer
//! ends the scan. Both are read from the kernel once, so every stage agrees.

use std::sync::OnceLock;

/// Worker threads the engine may use.
///
/// Rayon defaults to one thread per core. On a phone with eight cores and two
/// gigabytes of memory that is the wrong trade: each worker faults its own
/// window of the mapping into the page cache and holds its own result vectors,
/// so the resident set grows with the thread count while the wall time barely
/// moves (the scan is bound by memory bandwidth, not by arithmetic).
///
/// The pool is built once and reused; a second call returns the same figure.
///
/// @return the number of threads the global pool was built with.
pub fn configure_threads() -> usize {
    static THREADS: OnceLock<usize> = OnceLock::new();
    *THREADS.get_or_init(|| {
        let cores = std::thread::available_parallelism()
            .map(|count| count.get())
            .unwrap_or(1);
        let threads = worker_threads(cores, total_memory_bytes());
        // A pool that cannot be built (one already exists) is not an error: the
        // engine then runs on whatever pool is there.
        let _ = rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .thread_name(|index| format!("offset-core-{index}"))
            .build_global();
        threads
    })
}

/// Worker count for the reported core and memory figures.
///
/// A 3 GB phone reports about 2.7–3.0 GiB in `/proc/meminfo`. Even one extra
/// worker doubles the page-fault window and the per-worker result buffers at
/// the exact moment the signature plan is at its peak, so those devices run
/// serially. Larger devices keep the existing four-worker cap.
fn worker_threads(cores: usize, memory: Option<u64>) -> usize {
    match memory {
        Some(bytes) if bytes <= 3 * GIB => 1,
        _ => cores.min(4),
    }
    .max(1)
}

/// One gibibyte, the unit `/proc/meminfo` is read in.
const GIB: u64 = 1024 * 1024 * 1024;

/// Total physical memory, when the kernel reports it.
///
/// `/proc/meminfo` is world-readable on Android; a sandbox that hides it makes
/// this return `None`, and the caller then keeps the conservative default.
fn total_memory_bytes() -> Option<u64> {
    let text = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("MemTotal:") else {
            continue;
        };
        let kilobytes = rest
            .split_whitespace()
            .next()
            .and_then(|value| value.parse::<u64>().ok())?;
        return Some(kilobytes * 1024);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_thread_count_is_between_one_and_four() {
        let threads = configure_threads();
        assert!((1..=4).contains(&threads), "{threads} threads");
        // A second call must not build a second pool or change the answer.
        assert_eq!(configure_threads(), threads);
    }

    #[test]
    fn three_gigabyte_devices_run_one_worker() {
        assert_eq!(worker_threads(8, Some(3 * GIB)), 1);
        assert_eq!(worker_threads(8, Some(3 * GIB + 1)), 4);
        assert_eq!(worker_threads(1, Some(8 * GIB)), 1);
    }

    #[test]
    fn a_missing_meminfo_is_not_an_error() {
        // Whatever the sandbox reports, the helper must not panic.
        let _ = total_memory_bytes();
    }
}
