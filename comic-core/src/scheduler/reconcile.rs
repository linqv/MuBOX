use std::collections::{HashMap, HashSet};

use super::range_planner::{ByteRange, PlannedPageRange};

/// Kotlin's current default budget for speculative planned-range reads.
pub const DEFAULT_PREFETCH_BYTE_BUDGET: u64 = 48 * 1024 * 1024;

/// Kotlin's current cap for the cache ranges protected by each prefetch task.
pub const DEFAULT_PROTECTED_BYTE_BUDGET: u64 = 32 * 1024 * 1024;

/// Number of neighboring pages retained around the pages in a range plan.
pub const DEFAULT_PROTECTION_PAGE_RADIUS: usize = 4;

/// Tunable limits for planned-range reconciliation.
///
/// The regular entry point accepts only the prefetch byte budget because the other
/// values are policy constants today. This type keeps the calculation testable and
/// lets a future JNI boundary change those policies without splitting the operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReconcileLimits {
    pub prefetch_byte_budget: u64,
    pub protected_byte_budget: u64,
    pub protection_page_radius: usize,
}

impl Default for ReconcileLimits {
    fn default() -> Self {
        Self {
            prefetch_byte_budget: DEFAULT_PREFETCH_BYTE_BUDGET,
            protected_byte_budget: DEFAULT_PROTECTED_BYTE_BUDGET,
            protection_page_radius: DEFAULT_PROTECTION_PAGE_RADIUS,
        }
    }
}

/// One native prefetch operation and the cache ranges it should protect.
///
/// Protection is task-specific: the task's own byte range is excluded, matching the
/// existing Kotlin cache contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconciledPrefetchTask {
    pub range: PlannedPageRange,
    pub protected_ranges: Vec<ByteRange>,
}

/// Pure result of reconciling a new range plan with in-flight and completed work.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconciledPrefetchPlan {
    pub tasks: Vec<ReconciledPrefetchTask>,
    pub retained_pages: Vec<usize>,
    pub budget_skipped_count: usize,
    pub budget_skipped_bytes: u64,
}

/// Reconciles planned ranges using the production protection policies.
///
/// `active_ranges` may contain work from the previous viewport. Ranges whose pages
/// do not intersect `retained_pages` are deliberately removed before coverage is
/// subtracted, because Kotlin cancels that work before it computes missing segments.
/// Completed ranges always participate in coverage subtraction, regardless of page.
pub fn reconcile_prefetch_plan(
    planned_ranges: &[PlannedPageRange],
    active_ranges: &[PlannedPageRange],
    completed_ranges: &[PlannedPageRange],
    byte_budget: u64,
) -> ReconciledPrefetchPlan {
    reconcile_prefetch_plan_with_limits(
        planned_ranges,
        active_ranges,
        completed_ranges,
        ReconcileLimits {
            prefetch_byte_budget: byte_budget,
            ..ReconcileLimits::default()
        },
    )
}

/// Reconciles planned ranges with explicit limits.
///
/// All byte ranges are inclusive and are expected to be valid (`start <=
/// end_inclusive`). Invalid planned ranges produce no task; invalid coverage ranges
/// do not cover any bytes. The returned page and task ordering is deterministic and
/// matches the stable ordering used by the Kotlin implementation.
pub fn reconcile_prefetch_plan_with_limits(
    planned_ranges: &[PlannedPageRange],
    active_ranges: &[PlannedPageRange],
    completed_ranges: &[PlannedPageRange],
    limits: ReconcileLimits,
) -> ReconciledPrefetchPlan {
    let merged_ranges = merge_same_start_ranges(planned_ranges);
    let retained_pages = protection_pages(&merged_ranges, limits.protection_page_radius);
    let retained_page_set = retained_pages.iter().copied().collect::<HashSet<_>>();

    // Kotlin removes stale jobs from its map before missingSegments observes active
    // coverage. Mirror that ordering even though the caller supplies one snapshot.
    let retained_active_ranges = active_ranges
        .iter()
        .filter(|range| pages_intersect(&range.pages, &retained_page_set))
        .cloned()
        .collect::<Vec<_>>();

    let covered_ranges = retained_active_ranges
        .iter()
        .chain(completed_ranges)
        .map(|range| range.range)
        .collect::<Vec<_>>();
    let missing_ranges = merged_ranges
        .iter()
        .flat_map(|range| missing_segments(range, &covered_ranges))
        .collect::<Vec<_>>();
    let budgeted = select_by_budget(missing_ranges, limits.prefetch_byte_budget);

    let mut tasks = Vec::with_capacity(budgeted.ranges.len());
    let mut scheduled_keys = HashSet::new();
    for range in &budgeted.ranges {
        let key = range_key(range.range);
        // Kotlin's job map suppresses a second task if independently-subtracted
        // planned ranges converge to the same missing byte range.
        if !scheduled_keys.insert(key) {
            continue;
        }
        tasks.push(ReconciledPrefetchTask {
            range: range.clone(),
            protected_ranges: protected_ranges_for_task(
                &budgeted.ranges,
                &retained_active_ranges,
                completed_ranges,
                range.range,
                limits.protected_byte_budget,
                limits.protection_page_radius,
            ),
        });
    }

    ReconciledPrefetchPlan {
        tasks,
        retained_pages,
        budget_skipped_count: budgeted.skipped_count,
        budget_skipped_bytes: budgeted.skipped_bytes,
    }
}

fn merge_same_start_ranges(ranges: &[PlannedPageRange]) -> Vec<PlannedPageRange> {
    let mut merged = Vec::<PlannedPageRange>::new();
    let mut positions = HashMap::<u64, usize>::new();

    for range in ranges {
        if let Some(&position) = positions.get(&range.range.start) {
            let current = &mut merged[position];
            current.range.end_inclusive =
                current.range.end_inclusive.max(range.range.end_inclusive);
            current.priority = current.priority.min(range.priority);
            current.pages.extend_from_slice(&range.pages);
        } else {
            positions.insert(range.range.start, merged.len());
            merged.push(range.clone());
        }
    }

    for range in &mut merged {
        range.pages.sort_unstable();
        range.pages.dedup();
    }
    merged
}

fn protection_pages(ranges: &[PlannedPageRange], radius: usize) -> Vec<usize> {
    let mut pages = ranges.iter().flat_map(|range| range.pages.iter().copied());
    let Some(first_page) = pages.next() else {
        return Vec::new();
    };
    let (minimum, maximum) = pages.fold((first_page, first_page), |(minimum, maximum), page| {
        (minimum.min(page), maximum.max(page))
    });
    let first_page = minimum.saturating_sub(radius);
    let last_page = maximum.saturating_add(radius);
    (first_page..=last_page).collect()
}

fn pages_intersect(pages: &[usize], retained_pages: &HashSet<usize>) -> bool {
    pages.iter().any(|page| retained_pages.contains(page))
}

fn missing_segments(range: &PlannedPageRange, covered: &[ByteRange]) -> Vec<PlannedPageRange> {
    subtract_covered_range(range.range, covered)
        .into_iter()
        .map(|missing| PlannedPageRange {
            range: missing,
            pages: range.pages.clone(),
            priority: range.priority,
        })
        .collect()
}

fn subtract_covered_range(requested: ByteRange, covered: &[ByteRange]) -> Vec<ByteRange> {
    if requested.end_inclusive < requested.start {
        return Vec::new();
    }

    let mut clipped = covered
        .iter()
        .filter_map(|range| {
            let start = requested.start.max(range.start);
            let end_inclusive = requested.end_inclusive.min(range.end_inclusive);
            (start <= end_inclusive).then_some(ByteRange::new(start, end_inclusive))
        })
        .collect::<Vec<_>>();
    clipped.sort_by_key(|range| range.start);
    if clipped.is_empty() {
        return vec![requested];
    }

    let mut missing = Vec::new();
    let mut cursor = requested.start;
    for range in clipped {
        if range.end_inclusive < cursor {
            continue;
        }
        if range.start > cursor {
            missing.push(ByteRange::new(cursor, range.start - 1));
        }
        let Some(next_cursor) = range.end_inclusive.checked_add(1) else {
            return missing;
        };
        cursor = cursor.max(next_cursor);
    }
    if cursor <= requested.end_inclusive {
        missing.push(ByteRange::new(cursor, requested.end_inclusive));
    }
    missing
}

struct BudgetSelection {
    ranges: Vec<PlannedPageRange>,
    skipped_count: usize,
    skipped_bytes: u64,
}

fn select_by_budget(mut ranges: Vec<PlannedPageRange>, byte_budget: u64) -> BudgetSelection {
    // Stable sorting preserves native plan order for equal priorities.
    ranges.sort_by_key(|range| range.priority);

    let mut selected = Vec::new();
    let mut selected_bytes = 0u64;
    let mut skipped_count = 0usize;
    let mut skipped_bytes = 0u64;
    for range in ranges {
        let byte_length = byte_length(range.range);
        let next_selected_bytes = selected_bytes
            .checked_add(byte_length)
            .filter(|total| *total <= byte_budget);
        if let Some(next_selected_bytes) = next_selected_bytes {
            selected_bytes = next_selected_bytes;
            selected.push(range);
        } else {
            skipped_count += 1;
            skipped_bytes = skipped_bytes.saturating_add(byte_length);
        }
    }

    BudgetSelection {
        ranges: selected,
        skipped_count,
        skipped_bytes,
    }
}

#[derive(Clone, Copy)]
struct ProtectionCandidate {
    range: ByteRange,
    source_rank: u8,
    page_distance: usize,
    priority: u8,
    byte_length: u64,
}

fn protected_ranges_for_task(
    current_ranges: &[PlannedPageRange],
    active_ranges: &[PlannedPageRange],
    completed_ranges: &[PlannedPageRange],
    excluded_range: ByteRange,
    byte_budget: u64,
    page_radius: usize,
) -> Vec<ByteRange> {
    let protection_pages = protection_pages(current_ranges, page_radius)
        .into_iter()
        .collect::<HashSet<_>>();
    let current_pages = current_ranges
        .iter()
        .flat_map(|range| range.pages.iter().copied())
        .collect::<HashSet<_>>();

    let mut candidates = Vec::new();
    candidates.extend(
        current_ranges
            .iter()
            .map(|range| protection_candidate(range, 0, &current_pages)),
    );
    candidates.extend(
        active_ranges
            .iter()
            .filter(|range| pages_intersect(&range.pages, &protection_pages))
            .map(|range| protection_candidate(range, 1, &current_pages)),
    );
    candidates.extend(
        completed_ranges
            .iter()
            .filter(|range| pages_intersect(&range.pages, &protection_pages))
            .map(|range| protection_candidate(range, 2, &current_pages)),
    );

    // Stable sorting preserves source insertion order after all explicit keys tie.
    candidates.sort_by(|left, right| {
        left.source_rank
            .cmp(&right.source_rank)
            .then_with(|| left.page_distance.cmp(&right.page_distance))
            .then_with(|| left.priority.cmp(&right.priority))
            .then_with(|| left.byte_length.cmp(&right.byte_length))
    });

    let excluded_key = range_key(excluded_range);
    let mut seen = HashSet::new();
    let mut selected = Vec::new();
    let mut selected_bytes = 0u64;
    for candidate in candidates {
        let key = range_key(candidate.range);
        if key == excluded_key || !seen.insert(key) {
            continue;
        }
        let Some(next_selected_bytes) = selected_bytes.checked_add(candidate.byte_length) else {
            continue;
        };
        if next_selected_bytes > byte_budget {
            continue;
        }
        selected.push(candidate.range);
        selected_bytes = next_selected_bytes;
    }
    selected
}

fn protection_candidate(
    range: &PlannedPageRange,
    source_rank: u8,
    current_pages: &HashSet<usize>,
) -> ProtectionCandidate {
    ProtectionCandidate {
        range: range.range,
        source_rank,
        page_distance: page_distance(&range.pages, current_pages),
        priority: range.priority,
        byte_length: byte_length(range.range),
    }
}

fn page_distance(candidate_pages: &[usize], current_pages: &HashSet<usize>) -> usize {
    if candidate_pages.is_empty() || current_pages.is_empty() {
        return usize::MAX;
    }
    candidate_pages
        .iter()
        .flat_map(|candidate| {
            current_pages
                .iter()
                .map(move |current| candidate.abs_diff(*current))
        })
        .min()
        .unwrap_or(usize::MAX)
}

fn byte_length(range: ByteRange) -> u64 {
    range
        .end_inclusive
        .checked_sub(range.start)
        .and_then(|length_minus_one| length_minus_one.checked_add(1))
        .unwrap_or(u64::MAX)
}

fn range_key(range: ByteRange) -> (u64, u64) {
    (range.start, range.end_inclusive)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_same_start_and_retains_the_neighbor_page_window() {
        let plan = reconcile_prefetch_plan(
            &[
                planned(100, 199, &[3], 4),
                planned(100, 249, &[2, 3], 1),
                planned(300, 399, &[8], 2),
            ],
            &[],
            &[],
            DEFAULT_PREFETCH_BYTE_BUDGET,
        );

        assert_eq!(
            vec![0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
            plan.retained_pages
        );
        assert_eq!(
            vec![planned(100, 249, &[2, 3], 1), planned(300, 399, &[8], 2)],
            task_ranges(&plan)
        );
    }

    #[test]
    fn stale_active_range_is_removed_before_coverage_subtraction() {
        let plan = reconcile_prefetch_plan(
            &[planned(100, 199, &[10], 0)],
            &[planned(100, 149, &[0], 0)],
            &[],
            DEFAULT_PREFETCH_BYTE_BUDGET,
        );

        assert_eq!(vec![planned(100, 199, &[10], 0)], task_ranges(&plan));
        assert!(plan.tasks[0].protected_ranges.is_empty());
    }

    #[test]
    fn retained_active_range_covers_bytes_and_is_protected() {
        let plan = reconcile_prefetch_plan(
            &[planned(100, 199, &[10], 0)],
            &[planned(100, 149, &[10], 0), planned(300, 309, &[30], 0)],
            &[],
            DEFAULT_PREFETCH_BYTE_BUDGET,
        );

        assert_eq!(vec![planned(150, 199, &[10], 0)], task_ranges(&plan));
        assert_eq!(
            vec![ByteRange::new(100, 149)],
            plan.tasks[0].protected_ranges
        );
    }

    #[test]
    fn completed_range_covers_bytes_even_when_its_pages_are_outside_retention() {
        let plan = reconcile_prefetch_plan(
            &[planned(100, 199, &[10], 0)],
            &[],
            &[planned(100, 149, &[0], 0)],
            DEFAULT_PREFETCH_BYTE_BUDGET,
        );

        assert_eq!(vec![planned(150, 199, &[10], 0)], task_ranges(&plan));
        // Completed coverage is global, but only nearby completed ranges are protected.
        assert!(plan.tasks[0].protected_ranges.is_empty());
    }

    #[test]
    fn overlapping_coverage_is_merged_and_clipped_to_the_request() {
        let missing = subtract_covered_range(
            ByteRange::new(10, 30),
            &[
                ByteRange::new(0, 12),
                ByteRange::new(15, 18),
                ByteRange::new(17, 25),
                ByteRange::new(40, 50),
            ],
        );

        assert_eq!(
            vec![ByteRange::new(13, 14), ByteRange::new(26, 30)],
            missing
        );
    }

    #[test]
    fn coverage_subtraction_handles_u64_max_without_overflow() {
        let missing = subtract_covered_range(
            ByteRange::new(u64::MAX - 2, u64::MAX),
            &[ByteRange::new(u64::MAX - 1, u64::MAX)],
        );

        assert_eq!(vec![ByteRange::new(u64::MAX - 2, u64::MAX - 2)], missing);
    }

    #[test]
    fn budget_keeps_priority_order_and_continues_after_an_oversized_range() {
        let plan = reconcile_prefetch_plan(
            &[
                planned(52, 67, &[2], 3),
                planned(32, 51, &[1], 1),
                planned(0, 31, &[0], 0),
            ],
            &[],
            &[],
            48,
        );

        assert_eq!(
            vec![planned(0, 31, &[0], 0), planned(52, 67, &[2], 3)],
            task_ranges(&plan)
        );
        assert_eq!(1, plan.budget_skipped_count);
        assert_eq!(20, plan.budget_skipped_bytes);
    }

    #[test]
    fn retained_pages_come_from_merged_plan_before_budget_selection() {
        let plan = reconcile_prefetch_plan(
            &[planned(0, 9, &[10], 0), planned(20, 119, &[100], 5)],
            &[],
            &[],
            10,
        );

        assert_eq!((6..=104).collect::<Vec<_>>(), plan.retained_pages);
        assert_eq!(vec![planned(0, 9, &[10], 0)], task_ranges(&plan));
    }

    #[test]
    fn protection_orders_current_active_and_completed_candidates() {
        let plan = reconcile_prefetch_plan_with_limits(
            &[planned(0, 9, &[10], 0), planned(20, 29, &[11], 1)],
            &[planned(40, 49, &[9], 0), planned(60, 69, &[30], 0)],
            &[
                planned(80, 89, &[10], 0),
                planned(100, 109, &[14], 0),
                planned(120, 129, &[30], 0),
            ],
            ReconcileLimits {
                prefetch_byte_budget: 100,
                protected_byte_budget: 100,
                protection_page_radius: 4,
            },
        );

        assert_eq!(
            vec![
                ByteRange::new(20, 29),
                ByteRange::new(40, 49),
                ByteRange::new(80, 89),
                ByteRange::new(100, 109),
            ],
            plan.tasks[0].protected_ranges
        );
        assert!(
            !plan.tasks[0]
                .protected_ranges
                .contains(&ByteRange::new(60, 69))
        );
        assert!(
            !plan.tasks[0]
                .protected_ranges
                .contains(&ByteRange::new(120, 129))
        );
    }

    #[test]
    fn protection_uses_distance_then_priority_then_length_within_a_source() {
        let plan = reconcile_prefetch_plan_with_limits(
            &[planned(0, 9, &[10], 0)],
            &[
                planned(20, 29, &[13], 0),
                planned(40, 59, &[11], 5),
                planned(60, 89, &[11], 1),
                planned(90, 94, &[11], 1),
            ],
            &[],
            ReconcileLimits {
                prefetch_byte_budget: 100,
                protected_byte_budget: 100,
                protection_page_radius: 4,
            },
        );

        assert_eq!(
            vec![
                ByteRange::new(90, 94),
                ByteRange::new(60, 89),
                ByteRange::new(40, 59),
                ByteRange::new(20, 29),
            ],
            plan.tasks[0].protected_ranges
        );
    }

    #[test]
    fn protection_budget_skips_large_candidate_and_keeps_later_small_one() {
        let plan = reconcile_prefetch_plan_with_limits(
            &[planned(0, 9, &[10], 0)],
            &[planned(20, 39, &[10], 0), planned(40, 44, &[10], 1)],
            &[],
            ReconcileLimits {
                prefetch_byte_budget: 100,
                protected_byte_budget: 10,
                protection_page_radius: 4,
            },
        );

        assert_eq!(vec![ByteRange::new(40, 44)], plan.tasks[0].protected_ranges);
    }

    #[test]
    fn zero_budget_returns_no_tasks_and_reports_all_missing_bytes() {
        let plan = reconcile_prefetch_plan(
            &[planned(0, 9, &[0], 0), planned(20, 29, &[1], 1)],
            &[],
            &[],
            0,
        );

        assert!(plan.tasks.is_empty());
        assert_eq!(2, plan.budget_skipped_count);
        assert_eq!(20, plan.budget_skipped_bytes);
        assert_eq!((0..=5).collect::<Vec<_>>(), plan.retained_pages);
    }

    #[test]
    fn converging_missing_ranges_schedule_only_the_first_key() {
        let plan = reconcile_prefetch_plan(
            &[planned(0, 9, &[0], 0), planned(5, 9, &[1], 1)],
            &[],
            &[planned(0, 4, &[100], 0)],
            100,
        );

        assert_eq!(vec![planned(5, 9, &[0], 0)], task_ranges(&plan));
    }

    fn planned(start: u64, end_inclusive: u64, pages: &[usize], priority: u8) -> PlannedPageRange {
        PlannedPageRange {
            range: ByteRange::new(start, end_inclusive),
            pages: pages.to_vec(),
            priority,
        }
    }

    fn task_ranges(plan: &ReconciledPrefetchPlan) -> Vec<PlannedPageRange> {
        plan.tasks.iter().map(|task| task.range.clone()).collect()
    }
}
