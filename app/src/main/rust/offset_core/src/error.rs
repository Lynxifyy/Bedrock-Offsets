//! Error types shared by every engine stage.

use thiserror::Error;

/// Errors the engine can report back to Kotlin.
#[derive(Debug, Error)]
pub enum CoreError {
    /// The archive does not contain the requested entry.
    #[error("missing .so entry: {0}")]
    MissingSoFile(String),

    /// The archive could not be read or parsed.
    #[error("invalid zip archive: {0}")]
    InvalidZip(String),

    /// The supplied file descriptor is not usable.
    #[error("invalid file descriptor: {0}")]
    InvalidDescriptor(String),

    /// A filesystem operation failed.
    #[error("io error: {0}")]
    Io(String),

    /// The native library could not be parsed as ELF.
    #[error("invalid elf: {0}")]
    InvalidElf(String),

    /// The native library targets an architecture other than AArch64.
    #[error("unsupported architecture: {0}")]
    UnsupportedArchitecture(String),

    /// A required section is absent from the native library.
    #[error("missing section: {0}")]
    MissingSection(String),

    /// A caller-supplied pattern could not be compiled.
    #[error("invalid pattern: {0}")]
    InvalidPattern(String),

    /// The scan was cancelled by the caller.
    #[error("scan cancelled")]
    Cancelled,
}

/// Convenience alias used throughout the engine.
pub type CoreResult<T> = Result<T, CoreError>;

impl From<std::io::Error> for CoreError {
    fn from(value: std::io::Error) -> Self {
        CoreError::Io(value.to_string())
    }
}

impl From<zip::result::ZipError> for CoreError {
    fn from(value: zip::result::ZipError) -> Self {
        CoreError::InvalidZip(value.to_string())
    }
}
