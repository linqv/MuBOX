use comic_core::cache::index_cache::{
    IndexCacheKey, load_index_cache, load_index_cache_with_options, open_cbz_with_index_cache,
    store_index_cache, store_index_cache_with_options,
};
use comic_core::cbz::{CbzIndex, CbzPageEntry};
use comic_core::image::ImageFormatOptions;
use comic_core::scheduler::prefetch::{
    NetworkClass, plan_prefetch, plan_prefetch_with_forward_window,
};
use comic_core::scheduler::range_planner::{
    ByteRange, PageByteRange, plan_page_ranges, plan_ranges,
};
use comic_core::zip::RangeReader;
use std::cell::Cell;
use tempfile::TempDir;

#[test]
fn unchanged_size_and_validator_loads_index_cache() {
    let temp = TempDir::new().unwrap();
    let key = IndexCacheKey {
        comic_key: "comic-a".to_string(),
        file_size: 123,
        validator: "etag-1".to_string(),
    };
    let index = sample_index();

    store_index_cache(temp.path(), &key, &index).unwrap();

    let loaded = load_index_cache(temp.path(), &key).unwrap();
    assert_eq!(Some(index), loaded);
}

#[test]
fn changed_size_invalidates_index_cache() {
    let temp = TempDir::new().unwrap();
    let original = IndexCacheKey {
        comic_key: "comic-a".to_string(),
        file_size: 123,
        validator: "etag-1".to_string(),
    };
    let changed = IndexCacheKey {
        file_size: 124,
        ..original.clone()
    };

    store_index_cache(temp.path(), &original, &sample_index()).unwrap();

    assert_eq!(None, load_index_cache(temp.path(), &changed).unwrap());
}

#[test]
fn changed_image_format_options_invalidate_index_cache() {
    let temp = TempDir::new().unwrap();
    let key = IndexCacheKey {
        comic_key: "comic-a".to_string(),
        file_size: 123,
        validator: "etag-1".to_string(),
    };
    let index = sample_index();
    store_index_cache_with_options(
        temp.path(),
        &key,
        ImageFormatOptions { avif: false },
        &index,
    )
    .unwrap();

    let loaded =
        load_index_cache_with_options(temp.path(), &key, ImageFormatOptions { avif: true })
            .unwrap();

    assert_eq!(None, loaded);
}

#[test]
fn cached_index_is_loaded_without_range_reads() {
    let temp = TempDir::new().unwrap();
    let key = IndexCacheKey {
        comic_key: "comic-a".to_string(),
        file_size: 123,
        validator: "etag-1".to_string(),
    };
    let index = sample_index();
    store_index_cache(temp.path(), &key, &index).unwrap();
    let reader = CountingRangeReader::new(123);

    let loaded = open_cbz_with_index_cache(&reader, temp.path(), &key).unwrap();

    assert_eq!(index, loaded);
    assert_eq!(0, reader.read_count());
}

#[test]
fn range_planner_merges_ranges_separated_by_63_kib_under_max_size() {
    let planned = plan_ranges(vec![
        ByteRange::new(0, 1023),
        ByteRange::new(1024 + 63 * 1024, 1024 + 63 * 1024 + 1023),
    ]);

    assert_eq!(1, planned.request_count);
    assert_eq!(
        vec![ByteRange::new(0, 1024 + 63 * 1024 + 1023)],
        planned.ranges
    );
}

#[test]
fn range_planner_does_not_merge_ranges_separated_by_65_kib() {
    let planned = plan_ranges(vec![
        ByteRange::new(0, 1023),
        ByteRange::new(1024 + 65 * 1024, 1024 + 65 * 1024 + 1023),
    ]);

    assert_eq!(2, planned.request_count);
}

#[test]
fn planned_page_ranges_merge_adjacent_pages_when_limits_allow() {
    let planned = plan_page_ranges(vec![
        PageByteRange {
            page_index: 2,
            priority: 1,
            range: ByteRange::new(0, 1023),
        },
        PageByteRange {
            page_index: 3,
            priority: 4,
            range: ByteRange::new(1024 + 63 * 1024, 1024 + 63 * 1024 + 1023),
        },
    ]);

    assert_eq!(1, planned.len());
    assert_eq!(ByteRange::new(0, 1024 + 63 * 1024 + 1023), planned[0].range);
    assert_eq!(vec![2, 3], planned[0].pages);
    assert_eq!(1, planned[0].priority);
}

#[test]
fn planned_page_ranges_record_all_covered_page_indexes() {
    let planned = plan_page_ranges(vec![
        PageByteRange {
            page_index: 4,
            priority: 7,
            range: ByteRange::new(20, 29),
        },
        PageByteRange {
            page_index: 5,
            priority: 8,
            range: ByteRange::new(30, 39),
        },
        PageByteRange {
            page_index: 6,
            priority: 9,
            range: ByteRange::new(40, 49),
        },
    ]);

    assert_eq!(1, planned.len());
    assert_eq!(vec![4, 5, 6], planned[0].pages);
}

#[test]
fn planned_page_ranges_split_when_merged_size_exceeds_max() {
    let max_merged_bytes = 8 * 1024 * 1024;
    let planned = plan_page_ranges(vec![
        PageByteRange {
            page_index: 0,
            priority: 0,
            range: ByteRange::new(0, max_merged_bytes - 1),
        },
        PageByteRange {
            page_index: 1,
            priority: 1,
            range: ByteRange::new(max_merged_bytes, max_merged_bytes),
        },
    ]);

    assert_eq!(2, planned.len());
    assert_eq!(vec![0], planned[0].pages);
    assert_eq!(vec![1], planned[1].pages);
}

#[test]
fn prefetch_prioritizes_current_page_before_next_and_previous() {
    let plan = plan_prefetch(10, 4, NetworkClass::Wifi);

    assert_eq!(Some(4), plan.tasks.first().map(|task| task.page_index));
    let next_priority = plan
        .tasks
        .iter()
        .find(|task| task.page_index == 5)
        .unwrap()
        .priority;
    let previous_priority = plan
        .tasks
        .iter()
        .find(|task| task.page_index == 3)
        .unwrap()
        .priority;
    assert!(next_priority < previous_priority);
}

#[test]
fn viewport_jump_demotes_old_forward_window_tasks() {
    let old = plan_prefetch(20, 2, NetworkClass::Wifi);
    let new = plan_prefetch(20, 10, NetworkClass::Wifi);

    assert!(old.tasks.iter().any(|task| task.page_index == 3));
    assert_eq!(Some(10), new.tasks.first().map(|task| task.page_index));
    assert!(new.tasks.iter().take(3).all(|task| task.page_index != 3));
}

#[test]
fn prefetch_uses_custom_forward_window_when_configured() {
    let plan = plan_prefetch_with_forward_window(20, 4, NetworkClass::Wifi, 6);
    let pages = plan
        .tasks
        .iter()
        .map(|task| task.page_index)
        .collect::<Vec<_>>();

    assert!(pages.contains(&10));
    assert!(!pages.contains(&11));
}

fn sample_index() -> CbzIndex {
    CbzIndex {
        pages: vec![CbzPageEntry {
            name: "1.jpg".to_string(),
            filename_len: 5,
            local_header_offset: 42,
            data_offset: Some(72),
            compressed_size: 10,
            uncompressed_size: 10,
            compression_method: 0,
            crc32: 7,
        }],
    }
}

struct CountingRangeReader {
    size: u64,
    reads: Cell<usize>,
}

impl CountingRangeReader {
    fn new(size: u64) -> Self {
        Self {
            size,
            reads: Cell::new(0),
        }
    }

    fn read_count(&self) -> usize {
        self.reads.get()
    }
}

impl RangeReader for CountingRangeReader {
    fn size(&self) -> anyhow::Result<u64> {
        Ok(self.size)
    }

    fn read_range(&self, _start: u64, _end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        self.reads.set(self.reads.get() + 1);
        Err(anyhow::anyhow!("range read should not be needed"))
    }
}
