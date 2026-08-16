use anyhow::{Result, anyhow, bail};

use crate::scheduler::range_planner::{ByteRange, PlannedPageRange};
use crate::scheduler::reconcile::ReconciledPrefetchPlan;

pub(super) const MAX_PREFETCH_WIRE_WORDS: usize = 1_000_000;
const PREFETCH_WIRE_HEADER_WORDS: usize = 2;
const MIN_PREFETCH_RANGE_WORDS: usize = 5;
const MAX_PREFETCH_WIRE_RANGES: usize =
    (MAX_PREFETCH_WIRE_WORDS - PREFETCH_WIRE_HEADER_WORDS) / MIN_PREFETCH_RANGE_WORDS;
const MAX_PREFETCH_RANGE_PAGES: usize = 4_096;
const VERSION: i64 = 1;
const STATUS_OK: i64 = 0;
const STATUS_ERROR: i64 = 1;

pub(super) enum ReconciledPrefetchPlanWire<'a> {
    Success(&'a ReconciledPrefetchPlan),
    Error,
}

pub(super) fn decode_prefetch_ranges(values: &[i64]) -> Result<Vec<PlannedPageRange>> {
    if values.len() > MAX_PREFETCH_WIRE_WORDS {
        bail!("prefetch range payload is too large");
    }
    let mut cursor = Cursor::new(values);
    if cursor.next("version")? != VERSION {
        bail!("unsupported prefetch range wire version");
    }
    let range_count = cursor.count("range count", MAX_PREFETCH_WIRE_RANGES, true)?;
    let mut ranges = Vec::with_capacity(range_count);
    for _ in 0..range_count {
        let start = cursor.non_negative("range start")? as u64;
        let end_inclusive = cursor.non_negative("range end")? as u64;
        if end_inclusive < start {
            bail!("prefetch range end precedes start");
        }
        let priority = cursor.bounded("range priority", u8::MAX as usize, true)? as u8;
        let page_count = cursor.count("range page count", MAX_PREFETCH_RANGE_PAGES, false)?;
        let mut pages = Vec::with_capacity(page_count);
        for _ in 0..page_count {
            pages.push(
                usize::try_from(cursor.non_negative("range page")?)
                    .map_err(|_| anyhow!("range page is too large"))?,
            );
        }
        ranges.push(PlannedPageRange {
            range: ByteRange::new(start, end_inclusive),
            pages,
            priority,
        });
    }
    cursor.require_fully_consumed()?;
    Ok(ranges)
}

pub(super) fn encode_reconciled_prefetch_plan(
    result: ReconciledPrefetchPlanWire<'_>,
) -> Result<Vec<i64>> {
    let ReconciledPrefetchPlanWire::Success(plan) = result else {
        return Ok(vec![VERSION, STATUS_ERROR]);
    };
    let mut values = vec![VERSION, STATUS_OK];
    values.push(to_i64(plan.retained_pages.len(), "retained page count")?);
    for page in &plan.retained_pages {
        values.push(to_i64(*page, "retained page")?);
    }
    values.push(to_i64(plan.tasks.len(), "task count")?);
    for task in &plan.tasks {
        push_range(&mut values, &task.range)?;
        values.push(to_i64(
            task.protected_ranges.len(),
            "protected range count",
        )?);
        for protected in &task.protected_ranges {
            values.push(to_i64(protected.start, "protected range start")?);
            values.push(to_i64(protected.end_inclusive, "protected range end")?);
        }
    }
    if values.len() > MAX_PREFETCH_WIRE_WORDS {
        bail!("reconciled prefetch plan payload is too large");
    }
    Ok(values)
}

fn push_range(values: &mut Vec<i64>, range: &PlannedPageRange) -> Result<()> {
    values.push(to_i64(range.range.start, "range start")?);
    values.push(to_i64(range.range.end_inclusive, "range end")?);
    values.push(i64::from(range.priority));
    values.push(to_i64(range.pages.len(), "range page count")?);
    for page in &range.pages {
        values.push(to_i64(*page, "range page")?);
    }
    Ok(())
}

fn to_i64<T>(value: T, label: &str) -> Result<i64>
where
    i64: TryFrom<T>,
{
    i64::try_from(value).map_err(|_| anyhow!("{label} is outside the JNI Long domain"))
}

struct Cursor<'a> {
    values: &'a [i64],
    offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(values: &'a [i64]) -> Self {
        Self { values, offset: 0 }
    }

    fn next(&mut self, label: &str) -> Result<i64> {
        let value = self
            .values
            .get(self.offset)
            .copied()
            .ok_or_else(|| anyhow!("missing {label}"))?;
        self.offset += 1;
        Ok(value)
    }

    fn non_negative(&mut self, label: &str) -> Result<i64> {
        let value = self.next(label)?;
        if value < 0 {
            bail!("{label} must be non-negative");
        }
        Ok(value)
    }

    fn count(&mut self, label: &str, maximum: usize, allow_zero: bool) -> Result<usize> {
        let value = self.bounded(label, maximum, allow_zero)?;
        if value > self.values.len() - self.offset {
            bail!("{label} exceeds the remaining payload");
        }
        Ok(value)
    }

    fn bounded(&mut self, label: &str, maximum: usize, allow_zero: bool) -> Result<usize> {
        let value = self.non_negative(label)?;
        let value = usize::try_from(value).map_err(|_| anyhow!("{label} is too large"))?;
        if (!allow_zero && value == 0) || value > maximum {
            bail!("{label} is out of bounds");
        }
        Ok(value)
    }

    fn require_fully_consumed(&self) -> Result<()> {
        if self.offset != self.values.len() {
            bail!("trailing prefetch range payload data");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scheduler::reconcile::{ReconciledPrefetchPlan, ReconciledPrefetchTask};

    #[test]
    fn decodes_empty_and_populated_range_state() {
        assert_eq!(
            Vec::<PlannedPageRange>::new(),
            decode_prefetch_ranges(&[1, 0]).unwrap()
        );
        assert_eq!(
            vec![planned(10, 19, &[2, 3], 4)],
            decode_prefetch_ranges(&[1, 1, 10, 19, 4, 2, 2, 3]).unwrap(),
        );
    }

    #[test]
    fn rejects_malformed_range_state() {
        for values in [
            vec![],
            vec![2, 0],
            vec![1, 1, 10, 9, 0, 1, 0],
            vec![1, 1, 10, 19, 256, 1, 0],
            vec![1, 1, 10, 19, 0, 0],
            vec![1, 0, 99],
        ] {
            assert!(
                decode_prefetch_ranges(&values).is_err(),
                "accepted {values:?}"
            );
        }
    }

    #[test]
    fn accepts_more_than_the_legacy_completed_range_limit() {
        let range_count = 4_097usize;
        let mut values = Vec::with_capacity(PREFETCH_WIRE_HEADER_WORDS + range_count * 5);
        values.extend([VERSION, range_count as i64]);
        for start in 0..range_count as i64 {
            values.extend([start, start, 0, 1, 0]);
        }

        let decoded = decode_prefetch_ranges(&values).unwrap();

        assert_eq!(range_count, decoded.len());
        assert_eq!(ByteRange::new(4_096, 4_096), decoded[4_096].range);
    }

    #[test]
    fn encodes_success_with_task_specific_protection() {
        let plan = ReconciledPrefetchPlan {
            retained_pages: vec![1, 2],
            tasks: vec![ReconciledPrefetchTask {
                range: planned(10, 19, &[2], 3),
                protected_ranges: vec![ByteRange::new(0, 9), ByteRange::new(20, 29)],
            }],
            budget_skipped_count: 0,
            budget_skipped_bytes: 0,
        };

        assert_eq!(
            vec![1, 0, 2, 1, 2, 1, 10, 19, 3, 1, 2, 2, 0, 9, 20, 29],
            encode_reconciled_prefetch_plan(ReconciledPrefetchPlanWire::Success(&plan)).unwrap(),
        );
    }

    #[test]
    fn distinguishes_empty_success_from_native_error() {
        let empty = ReconciledPrefetchPlan {
            retained_pages: Vec::new(),
            tasks: Vec::new(),
            budget_skipped_count: 0,
            budget_skipped_bytes: 0,
        };
        assert_eq!(
            vec![1, 0, 0, 0],
            encode_reconciled_prefetch_plan(ReconciledPrefetchPlanWire::Success(&empty)).unwrap(),
        );
        assert_eq!(
            vec![1, 1],
            encode_reconciled_prefetch_plan(ReconciledPrefetchPlanWire::Error).unwrap(),
        );
    }

    fn planned(start: u64, end_inclusive: u64, pages: &[usize], priority: u8) -> PlannedPageRange {
        PlannedPageRange {
            range: ByteRange::new(start, end_inclusive),
            pages: pages.to_vec(),
            priority,
        }
    }
}
