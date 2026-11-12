//! Peak-allocation budget for the RTTI vtable phase.
//!
//! The vtable pass used to materialise the library's whole relocation table
//! before indexing it: a flat `Vec<Relocation>` of 1,043,633 entries, then a
//! second `Vec` holding the sorted index, with the first dropped only after the
//! second was built. On a real library that is ~42 MiB of heap on top of the
//! scan's resident set, which is enough to abort the process on a device with
//! little memory left.
//!
//! The budget is measured with a counting allocator rather than by sampling
//! `/proc`, so the number is the engine's own heap and not the test runner's,
//! and the check does not depend on machine load. It lives in its own test
//! binary so no other test allocates while the peak is being read.
//!
//! Gated on `MCPE_SO`, like the other live measurements: without a real library
//! there is no relocation table to budget.

use std::alloc::{GlobalAlloc, Layout, System};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

/// Live heap bytes, tracked across every allocation in this binary.
static LIVE: AtomicUsize = AtomicUsize::new(0);

/// Highest value [`LIVE`] has reached since it was last reset.
static PEAK: AtomicUsize = AtomicUsize::new(0);

/// A pass-through allocator that records how much heap is live.
struct Counting;

// SAFETY: every method forwards to `System` unchanged and only adds bookkeeping
// around the returned pointer, so the allocator contract is the system one.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = System.alloc(layout);
        if !pointer.is_null() {
            let live = LIVE.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
            PEAK.fetch_max(live, Ordering::Relaxed);
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        System.dealloc(pointer, layout);
        LIVE.fetch_sub(layout.size(), Ordering::Relaxed);
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let moved = System.realloc(pointer, layout, new_size);
        if !moved.is_null() {
            if new_size >= layout.size() {
                let live = LIVE.fetch_add(new_size - layout.size(), Ordering::Relaxed)
                    + (new_size - layout.size());
                PEAK.fetch_max(live, Ordering::Relaxed);
            } else {
                LIVE.fetch_sub(layout.size() - new_size, Ordering::Relaxed);
            }
        }
        moved
    }
}

#[global_allocator]
static ALLOCATOR: Counting = Counting;

/// Heap the vtable phase is allowed to hold at its peak.
///
/// The index itself is ~16 MiB for a real library, and the resolved candidates
/// and serialised JSON add a few more; 40 MiB leaves room for both while
/// staying far below the ~66 MiB the two-vector version reached.
const BUDGET_BYTES: usize = 40 * 1024 * 1024;

/// The library to measure against, when the environment provides one.
fn real_library() -> Option<PathBuf> {
    let path = PathBuf::from(std::env::var("MCPE_SO").ok()?);
    path.is_file().then_some(path)
}

#[test]
fn the_vtable_phase_stays_within_its_heap_budget() {
    let Some(path) = real_library() else {
        eprintln!("MCPE_SO is not set to a real library; skipping the live measurement");
        return;
    };

    let baseline = LIVE.load(Ordering::Relaxed);
    PEAK.store(baseline, Ordering::Relaxed);

    let json =
        offset_core::jni_bridge::resolve_vtables_json(path.to_str().expect("a UTF-8 path"), "live")
            .expect("the vtable phase must resolve a real library");

    let peak = PEAK.load(Ordering::Relaxed);
    let used = peak.saturating_sub(baseline);
    // A real library yields thousands of vtables; an empty result would mean the
    // phase short-circuited and the measurement proves nothing.
    assert!(
        json.len() > 4096,
        "the vtable phase returned {} bytes, so it did not run",
        json.len()
    );
    assert!(
        used <= BUDGET_BYTES,
        "the vtable phase held {} MiB at its peak, over the {} MiB budget",
        used / (1024 * 1024),
        BUDGET_BYTES / (1024 * 1024)
    );
}
