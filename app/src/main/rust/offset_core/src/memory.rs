//! Peak resident memory reporting.
//!
//! The engine promises that scanning a multi-hundred-megabyte library does not
//! grow the resident set beyond a small bound. That promise is only meaningful
//! if it is measured, so the kernel's own high-water mark is read rather than
//! estimated.

/// Reads the peak resident set size of this process, in bytes.
///
/// On Linux and Android this comes from `VmHWM` in `/proc/self/status`, the
/// kernel's high-water mark. The value is the process peak, so it is a
/// conservative upper bound for the engine alone.
///
/// # Returns
///
/// The peak in bytes, or `None` when the file is unreadable or the field is
/// absent, which is the case on platforms without `/proc`.
pub fn peak_resident_bytes() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    parse_peak_resident(&status)
}

/// Extracts `VmHWM` from the text of `/proc/self/status`.
///
/// Split out so the parsing is testable without the file.
///
/// # Returns
///
/// The peak in bytes, or `None` when the field is missing or malformed.
pub fn parse_peak_resident(status: &str) -> Option<u64> {
    for line in status.lines() {
        let Some(rest) = line.strip_prefix("VmHWM:") else {
            continue;
        };
        let mut fields = rest.split_whitespace();
        let value = fields.next()?.parse::<u64>().ok()?;
        // The kernel reports kB on every Linux and Android release.
        let unit = fields.next().unwrap_or("kB");
        let multiplier = match unit {
            "kB" | "KB" => 1024,
            "mB" | "MB" => 1024 * 1024,
            "B" => 1,
            _ => 1024,
        };
        return value.checked_mul(multiplier);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peak_resident_is_parsed_from_a_status_blob() {
        let status =
            "Name:\toffset_core\nVmPeak:\t  123456 kB\nVmHWM:\t  204800 kB\nVmRSS:\t  102400 kB\n";
        assert_eq!(parse_peak_resident(status), Some(204_800 * 1024));
    }

    #[test]
    fn a_status_blob_without_the_field_yields_nothing() {
        assert_eq!(parse_peak_resident("Name:\tx\n"), None);
        assert_eq!(parse_peak_resident(""), None);
    }

    #[test]
    fn a_malformed_value_yields_nothing() {
        assert_eq!(parse_peak_resident("VmHWM:\tnot-a-number kB\n"), None);
        assert_eq!(parse_peak_resident("VmHWM:\n"), None);
    }

    #[test]
    fn the_unit_is_respected() {
        assert_eq!(
            parse_peak_resident("VmHWM:\t  2 mB\n"),
            Some(2 * 1024 * 1024)
        );
        assert_eq!(parse_peak_resident("VmHWM:\t  512 B\n"), Some(512));
    }

    #[test]
    fn a_real_process_reports_a_plausible_peak() {
        // On Linux and Android this must succeed; elsewhere the function is
        // allowed to return `None` and the test simply passes.
        if let Some(peak) = peak_resident_bytes() {
            assert!(peak > 0, "peak resident must be positive");
        }
    }
}
