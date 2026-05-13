const MERGE_GAP_BYTES: u64 = 64 * 1024;
const MAX_MERGED_BYTES: u64 = 8 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteRange {
    pub start: u64,
    pub end_inclusive: u64,
}

impl ByteRange {
    pub fn new(start: u64, end_inclusive: u64) -> Self {
        Self {
            start,
            end_inclusive,
        }
    }

    fn len(self) -> u64 {
        self.end_inclusive.saturating_sub(self.start) + 1
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedRanges {
    pub ranges: Vec<ByteRange>,
    pub request_count: usize,
}

pub fn plan_ranges(mut ranges: Vec<ByteRange>) -> PlannedRanges {
    ranges.sort_by_key(|range| range.start);
    let mut planned: Vec<ByteRange> = Vec::new();

    for range in ranges {
        let Some(last) = planned.last_mut() else {
            planned.push(range);
            continue;
        };
        let gap = range
            .start
            .saturating_sub(last.end_inclusive.saturating_add(1));
        let merged = ByteRange::new(last.start, last.end_inclusive.max(range.end_inclusive));
        if gap <= MERGE_GAP_BYTES && merged.len() <= MAX_MERGED_BYTES {
            *last = merged;
        } else {
            planned.push(range);
        }
    }

    PlannedRanges {
        request_count: planned.len(),
        ranges: planned,
    }
}
