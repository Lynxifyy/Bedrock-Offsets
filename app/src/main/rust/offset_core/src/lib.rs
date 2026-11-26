//! Zero-copy extraction and parsing core for the Bedrock Offset Extractor.
//!
//! Every public item is documented; the lint below keeps it that way.
#![warn(missing_docs)]
//!
//! The engine never loads a whole APK or native library into memory. Archives
//! are streamed through a fixed-size buffer and shared objects are mapped with
//! `mmap`, so peak resident memory stays flat regardless of input size.

pub mod android;
pub mod categories;
pub mod elf;
pub mod error;
pub mod fd;
pub mod internal;
pub mod jni;
pub mod jni_bridge;
pub mod jninatives;
pub mod memory;
pub mod patterns;
pub mod progress;
pub mod prologue;
pub mod rtti;
pub mod scanner;
pub mod sections;
pub mod shards;
pub mod symbols;
pub mod verify;
pub mod zip_extract;

pub use error::{CoreError, CoreResult};

/// Shape of the data the engine writes: the summary fields, the table header,
/// the class and function records.
///
/// Bump this whenever a consumer would misread output from an older engine —
/// a removed or renamed field, or a unit change such as an address becoming
/// base-relative. [`engine_version`] folds it into the string the app keys its
/// result cache on, so a bump invalidates every cached scan instead of letting
/// a device keep reusing a table it can no longer interpret.
pub const ENGINE_SCHEMA_VERSION: u32 = 2;

/// Version of the engine, for display and for the result-cache key.
///
/// The crate version alone is not enough: it stays `0.1.x` across releases, so
/// a change in output shape would not invalidate anything. The schema suffix
/// does, because it is bumped in the same change as the shape.
///
/// # Examples
///
/// ```
/// let version = offset_core::engine_version();
/// assert!(version.contains("+"));
/// ```
#[must_use]
pub fn engine_version() -> String {
    format!("{}+{}", env!("CARGO_PKG_VERSION"), ENGINE_SCHEMA_VERSION)
}

#[cfg(test)]
mod tests {
    use super::{engine_version, ENGINE_SCHEMA_VERSION};

    #[test]
    fn the_engine_version_carries_the_schema_so_a_shape_change_invalidates_caches() {
        assert_eq!(
            engine_version(),
            format!("{}+{}", env!("CARGO_PKG_VERSION"), ENGINE_SCHEMA_VERSION)
        );
        // A reader of `manifest.json` and of the category documents compares the
        // schema they find against the one they understand, so it must be a real
        // version and not the 1 that shipped before the class record changed.
        let schema_marker = ENGINE_SCHEMA_VERSION.to_string();
        assert_ne!(
            schema_marker, "1",
            "version 1 predates the class file offset"
        );
    }
}
