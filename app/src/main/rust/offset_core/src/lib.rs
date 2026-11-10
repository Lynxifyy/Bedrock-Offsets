//! Zero-copy extraction and parsing core for the Bedrock Offset Extractor.
//!
//! The engine never loads a whole APK or native library into memory. Archives
//! are streamed through a fixed-size buffer and shared objects are mapped with
//! `mmap`, so peak resident memory stays flat regardless of input size.

pub mod android;
pub mod elf;
pub mod error;
pub mod fd;
pub mod jni_bridge;
pub mod zip_extract;

pub use error::{CoreError, CoreResult};
