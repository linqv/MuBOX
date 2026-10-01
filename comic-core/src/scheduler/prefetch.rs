use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkClass {
    Unknown,
    Mobile,
    Wifi,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PrefetchTask {
    pub page_index: usize,
    pub priority: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrefetchPlan {
    pub tasks: Vec<PrefetchTask>,
}

pub fn plan_prefetch(
    page_count: usize,
    current_page: usize,
    network_class: NetworkClass,
) -> PrefetchPlan {
    plan_prefetch_with_windows(
        page_count,
        current_page,
        default_forward_window(network_class),
        3,
    )
}

pub fn plan_prefetch_with_forward_window(
    page_count: usize,
    current_page: usize,
    _network_class: NetworkClass,
    forward_window: usize,
) -> PrefetchPlan {
    plan_prefetch_with_windows(page_count, current_page, forward_window, 1)
}

pub fn plan_prefetch_with_windows(
    page_count: usize,
    current_page: usize,
    forward_window: usize,
    backward_window: usize,
) -> PrefetchPlan {
    if page_count == 0 || current_page >= page_count {
        return PrefetchPlan { tasks: Vec::new() };
    }

    let mut tasks = Vec::new();
    let mut seen = HashSet::new();
    push_unique(&mut tasks, &mut seen, current_page, 0);
    if current_page + 1 < page_count {
        push_unique(&mut tasks, &mut seen, current_page + 1, 1);
    }
    if current_page > 0 {
        push_unique(&mut tasks, &mut seen, current_page - 1, 2);
    }
    // Nearby reverse pages must be scheduled before distant speculative forward
    // work so a quick change of reading direction does not wait behind it.
    for offset in 2..=backward_window.min(16) {
        if current_page >= offset {
            // Kotlin treats priority <= 2 as high priority. Keep the first
            // three reverse pages in that lane while preserving their order.
            let priority = 2 + offset.saturating_sub(3) as u8;
            push_unique(&mut tasks, &mut seen, current_page - offset, priority);
        }
    }
    for offset in 2..=forward_window.min(16) {
        let Some(page) = current_page.checked_add(offset) else {
            break;
        };
        if page < page_count {
            push_unique(&mut tasks, &mut seen, page, 3 + offset as u8);
        }
    }
    tasks.sort_by_key(|task| task.priority);
    PrefetchPlan { tasks }
}

fn default_forward_window(network_class: NetworkClass) -> usize {
    match network_class {
        NetworkClass::Wifi => 4,
        NetworkClass::Mobile => 2,
        NetworkClass::Unknown => 3,
    }
}

fn push_unique(
    tasks: &mut Vec<PrefetchTask>,
    seen: &mut HashSet<usize>,
    page_index: usize,
    priority: u8,
) {
    if !seen.insert(page_index) {
        return;
    }
    tasks.push(PrefetchTask {
        page_index,
        priority,
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_page_count() {
        let plan = plan_prefetch(0, 0, NetworkClass::Wifi);
        assert!(plan.tasks.is_empty());
    }

    #[test]
    fn current_page_out_of_range() {
        let plan = plan_prefetch(5, 10, NetworkClass::Wifi);
        assert!(plan.tasks.is_empty());
    }

    #[test]
    fn no_duplicate_pages() {
        let plan = plan_prefetch(20, 5, NetworkClass::Wifi);
        let mut indices: Vec<usize> = plan.tasks.iter().map(|t| t.page_index).collect();
        let len_before = indices.len();
        indices.sort();
        indices.dedup();
        assert_eq!(indices.len(), len_before, "duplicate page indices in plan");
    }

    #[test]
    fn output_sorted_by_priority() {
        let plan = plan_prefetch(20, 5, NetworkClass::Wifi);
        for w in plan.tasks.windows(2) {
            assert!(w[0].priority <= w[1].priority);
        }
    }

    #[test]
    fn first_page_no_backward() {
        let plan = plan_prefetch(10, 0, NetworkClass::Wifi);
        assert_eq!(plan.tasks[0].page_index, 0);
        assert_eq!(plan.tasks[0].priority, 0);
        // No page with index wrapping around
        assert!(plan.tasks.iter().all(|t| t.page_index < 10));
    }

    #[test]
    fn wifi_forward_window_is_4() {
        let plan = plan_prefetch(20, 5, NetworkClass::Wifi);
        // Should include pages 5,6,4,7,8,9 (current, +1, -1, +2..+4)
        let indices: Vec<usize> = plan.tasks.iter().map(|t| t.page_index).collect();
        assert!(indices.contains(&5));
        assert!(indices.contains(&6));
        assert!(indices.contains(&7));
        assert!(indices.contains(&8));
        assert!(indices.contains(&9));
    }

    #[test]
    fn mobile_forward_window_is_2() {
        let plan = plan_prefetch(20, 5, NetworkClass::Mobile);
        let indices: Vec<usize> = plan.tasks.iter().map(|t| t.page_index).collect();
        assert!(indices.contains(&6));
        assert!(indices.contains(&7));
        // +3 should NOT be included
        assert!(!indices.contains(&8));
    }

    #[test]
    fn backward_window_includes_nearby_pages_before_distant_forward_work() {
        let plan = plan_prefetch_with_windows(20, 8, 4, 3);
        let pages = plan
            .tasks
            .iter()
            .map(|task| task.page_index)
            .collect::<Vec<_>>();
        assert_eq!(vec![8, 9, 7, 6, 5, 10, 11, 12], pages);
        assert!(plan.tasks.iter().take(5).all(|task| task.priority <= 2));
        assert!(plan.tasks.iter().skip(5).all(|task| task.priority > 2));
    }

    #[test]
    fn legacy_forward_only_entry_point_keeps_one_backward_page() {
        let plan = plan_prefetch_with_forward_window(20, 8, NetworkClass::Wifi, 4);
        let pages = plan
            .tasks
            .iter()
            .map(|task| task.page_index)
            .collect::<Vec<_>>();
        assert_eq!(vec![8, 9, 7, 10, 11, 12], pages);
    }

    #[test]
    fn backward_window_clamps_at_first_page_without_duplicates() {
        let plan = plan_prefetch_with_windows(20, 1, 4, 3);
        let pages = plan
            .tasks
            .iter()
            .map(|task| task.page_index)
            .collect::<Vec<_>>();
        assert_eq!(vec![1, 2, 0, 3, 4, 5], pages);
    }
}
