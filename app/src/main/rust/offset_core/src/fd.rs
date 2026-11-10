//! Helpers for borrowing an integer file descriptor without taking ownership.
//!
//! The descriptor is owned by Kotlin's `ParcelFileDescriptor`. Wrapping it in a
//! [`std::fs::File`] directly would close it when the `File` is dropped, so the
//! descriptor is duplicated first and only the duplicate is owned here.

use std::fs::File;
use std::os::unix::io::{FromRawFd, RawFd};

use crate::error::{CoreError, CoreResult};

/// Duplicates `fd` and returns a [`File`] that owns only the duplicate.
///
/// The caller's descriptor stays open and usable afterwards.
///
/// # Errors
///
/// Returns [`CoreError::InvalidDescriptor`] when `fd` is negative or when the
/// kernel refuses to duplicate it.
pub fn duplicate_fd(fd: RawFd) -> CoreResult<File> {
    if fd < 0 {
        return Err(CoreError::InvalidDescriptor(format!("fd {fd} is negative")));
    }

    // SAFETY: `dup` only inspects the integer and returns a new descriptor or
    // -1. The result is checked before it is wrapped in a `File`.
    let duplicated = unsafe { libc::dup(fd) };
    if duplicated < 0 {
        return Err(CoreError::InvalidDescriptor(format!(
            "dup failed for fd {fd}: {}",
            std::io::Error::last_os_error()
        )));
    }

    // SAFETY: `duplicated` is a fresh descriptor owned by this function, so
    // transferring ownership to `File` is correct and it will be closed once.
    Ok(unsafe { File::from_raw_fd(duplicated) })
}
