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
