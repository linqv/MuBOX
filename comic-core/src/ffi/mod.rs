mod jni;
mod prefetch_wire;
mod range_io_wire;

use std::cell::RefCell;
use std::ffi::CString;

use crate::session_registry::PlannedRangeDto;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

pub(super) enum PlannedRangesWire<'a> {
    Success(&'a [PlannedRangeDto]),
    Error,
}

pub(super) enum DiagnosticsWire<'a> {
    Success(&'a str),
    Error,
}

pub(super) fn encode_diagnostics(result: DiagnosticsWire<'_>) -> String {
    match result {
        DiagnosticsWire::Success("") => "v2;ok".to_string(),
        DiagnosticsWire::Success(payload) => format!("v2;ok;{payload}"),
        DiagnosticsWire::Error => "v2;error".to_string(),
    }
}

pub(super) fn encode_planned_ranges(result: PlannedRangesWire<'_>) -> String {
    let PlannedRangesWire::Success(ranges) = result else {
        return "v2;error".to_string();
    };
    if ranges.is_empty() {
        return "v2;ok".to_string();
    }
    let entries = ranges
        .iter()
        .map(|range| {
            let pages = range
                .pages
                .iter()
                .map(|page| page.to_string())
                .collect::<Vec<_>>()
                .join("|");
            format!(
                "{},{},{},{}",
                range.start, range.end_inclusive, range.priority, pages
            )
        })
        .collect::<Vec<_>>()
        .join(";");
    format!("v2;ok;{entries}")
}

pub(super) fn set_last_error(error: impl std::fmt::Display) {
    let sanitized = error.to_string().replace('\0', "\\0");
    LAST_ERROR.with(|cell| {
        *cell.borrow_mut() = CString::new(sanitized).unwrap_or_default();
    });
}

pub(super) fn last_error_message_string() -> String {
    LAST_ERROR.with(|cell| cell.borrow().to_str().unwrap_or_default().to_owned())
}

#[cfg(test)]
mod tests {
    use super::{
        DiagnosticsWire, PlannedRangesWire, encode_diagnostics, encode_planned_ranges,
        last_error_message_string, set_last_error,
    };
    use crate::session_registry::PlannedRangeDto;

    #[test]
    fn planned_ranges_wire_distinguishes_empty_success_from_native_error() {
        assert_eq!(
            "v2;ok",
            encode_planned_ranges(PlannedRangesWire::Success(&[]))
        );
        assert_eq!("v2;error", encode_planned_ranges(PlannedRangesWire::Error));
    }

    #[test]
    fn planned_ranges_wire_encodes_successful_ranges() {
        let ranges = [PlannedRangeDto {
            start: 10,
            end_inclusive: 29,
            pages: vec![2, 3],
            priority: 1,
        }];

        assert_eq!(
            "v2;ok;10,29,1,2|3",
            encode_planned_ranges(PlannedRangesWire::Success(&ranges))
        );
    }

    #[test]
    fn diagnostics_wire_preserves_payload_delimiters_and_distinguishes_error() {
        let payload = "viewport_page=3;planned_request_count=2;planned_bytes=4096";

        assert_eq!(
            format!("v2;ok;{payload}"),
            encode_diagnostics(DiagnosticsWire::Success(payload))
        );
        assert_eq!("v2;ok", encode_diagnostics(DiagnosticsWire::Success("")));
        assert_eq!("v2;error", encode_diagnostics(DiagnosticsWire::Error));
    }

    #[test]
    fn last_error_is_thread_local() {
        set_last_error("error_A");
        assert_eq!(last_error_message_string(), "error_A");

        let child = std::thread::spawn(|| {
            set_last_error("error_B");
            assert_eq!(last_error_message_string(), "error_B");
        });
        child.join().unwrap();

        assert_eq!(last_error_message_string(), "error_A");
    }
}
