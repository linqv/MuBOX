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
    if page_count == 0 || current_page >= page_count {
        return PrefetchPlan { tasks: Vec::new() };
    }

    let forward_window = match network_class {
        NetworkClass::Wifi => 4,
        NetworkClass::Mobile => 2,
        NetworkClass::Unknown => 3,
    };
    let backward_window = 1usize;
    let mut tasks = Vec::new();
    push_unique(&mut tasks, current_page, 0);
    if current_page + 1 < page_count {
        push_unique(&mut tasks, current_page + 1, 1);
    }
    if current_page > 0 {
        push_unique(&mut tasks, current_page - 1, 2);
    }
    for offset in 2..=forward_window {
        let page = current_page + offset;
        if page < page_count {
            push_unique(&mut tasks, page, 3 + offset as u8);
        }
    }
    for offset in 2..=backward_window {
        if current_page >= offset {
            push_unique(&mut tasks, current_page - offset, 16 + offset as u8);
        }
    }
    tasks.sort_by_key(|task| task.priority);
    PrefetchPlan { tasks }
}

fn push_unique(tasks: &mut Vec<PrefetchTask>, page_index: usize, priority: u8) {
    if tasks.iter().any(|task| task.page_index == page_index) {
        return;
    }
    tasks.push(PrefetchTask {
        page_index,
        priority,
    });
}
