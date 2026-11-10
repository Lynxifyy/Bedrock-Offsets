//! Android logging initialisation and the panic hook.
//!
//! Panics are caught at the JNI boundary and logged with a backtrace instead of
//! tearing down the process, so a malformed input can never take the app with
//! it.

use std::backtrace::Backtrace;
use std::panic;

/// Log tag used for every line the engine writes to logcat.
pub const LOG_TAG: &str = "OffsetCore";

/// Installs the platform logger and the panic hook.
///
/// Safe to call more than once; the logger is only installed on the first call.
pub fn install() {
    install_logger();
    install_panic_hook();
}

#[cfg(target_os = "android")]
fn install_logger() {
    use android_logger::Config;

    // A release build keeps logcat at warnings and above. The engine logs
    // section boundaries and library sizes at info level, and those are the
    // details of the file the user chose; logcat is readable by other apps on
    // an old enough device, so they do not belong in a shipping build.
    android_logger::init_once(
        Config::default()
            .with_max_level(log_level())
            .with_tag(LOG_TAG),
    );
}

/// Log level used for the platform logger.
///
/// Debug builds keep info; release builds drop to warnings and errors.
#[cfg(target_os = "android")]
const fn log_level() -> log::LevelFilter {
    if cfg!(debug_assertions) {
        log::LevelFilter::Info
    } else {
        log::LevelFilter::Warn
    }
}

#[cfg(not(target_os = "android"))]
fn install_logger() {
    // Host builds (tests, clippy) use the environment logger when present.
    let _ = env_logger_try_init();
}

#[cfg(not(target_os = "android"))]
fn env_logger_try_init() -> bool {
    // No dependency on env_logger: the host simply keeps the default no-op
    // logger, which is enough for `cargo test`.
    false
}

fn install_panic_hook() {
    panic::set_hook(Box::new(|info| {
        let backtrace = Backtrace::force_capture();
        log::error!("panic in offset_core: {info}\n{backtrace}");
    }));
}

/// Runs `body`, converting any panic into an error value.
///
/// The hook above still logs the panic and its backtrace first, so a caught
/// panic is never silent.
///
/// # Errors
///
/// Returns the panic payload rendered as text when `body` panics.
pub fn catch_panics<T, F>(what: &str, body: F) -> Result<T, String>
where
    F: FnOnce() -> T + panic::UnwindSafe,
{
    panic::catch_unwind(body).map_err(|payload| {
        let detail = payload
            .downcast_ref::<&str>()
            .map(|text| (*text).to_string())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic payload".to_string());
        format!("panic while {what}: {detail}")
    })
}
