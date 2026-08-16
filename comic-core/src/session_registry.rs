use anyhow::{Result, anyhow};
use once_cell::sync::Lazy;
use serde::Serialize;
use std::collections::HashMap;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

#[cfg(unix)]
use std::os::fd::FromRawFd;

use crate::cache::index_cache::{IndexCacheKey, open_cbz_with_index_cache, store_index_cache};
use crate::cbz::{CbzIndex, CbzPageEntry, open_cbz};
use crate::error::ComicCoreError;
use crate::remote::jni_range_transport::JniRangeTransport;
use crate::remote::range_session::RemoteRangeSession;
use crate::scheduler::prefetch::{NetworkClass, plan_prefetch_with_forward_window};
use crate::scheduler::range_planner::{
    ByteRange, PageByteRange, PlannedPageRange, plan_page_ranges,
};
use crate::scheduler::reconcile::{ReconciledPrefetchPlan, reconcile_prefetch_plan};
use crate::zip::local_header::LOCAL_HEADER_MIN_SIZE;
use crate::zip::{FileRangeReader, RangeReader};

pub(crate) type ComicHandle = u64;

struct CbzSession {
    kind: SessionKind,
    diagnostics: SessionDiagnostics,
    index_cache: Option<SessionIndexCache>,
    index_cache_dirty: bool,
}

impl Drop for CbzSession {
    fn drop(&mut self) {
        if !self.index_cache_dirty {
            return;
        }
        let (Some(cache), SessionKind::Zip { index, .. }) = (&self.index_cache, &self.kind) else {
            return;
        };
        let _ = store_index_cache(&cache.cache_dir, &cache.key, index);
    }
}

#[derive(Debug, Default, Clone)]
struct SessionDiagnostics {
    viewport_page: Option<usize>,
    planned_request_count: usize,
    planned_bytes: u64,
}

#[derive(Debug, Clone)]
struct SessionIndexCache {
    cache_dir: PathBuf,
    key: IndexCacheKey,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub(crate) struct PlannedRangeDto {
    pub(crate) start: u64,
    pub(crate) end_inclusive: u64,
    pub(crate) pages: Vec<usize>,
    pub(crate) priority: u8,
}

impl PlannedRangeDto {
    fn byte_len(&self) -> u64 {
        self.end_inclusive.saturating_sub(self.start) + 1
    }
}

enum SessionReader {
    Local(FileRangeReader),
    RemoteRange(Arc<RemoteRangeSession<JniRangeTransport>>),
    #[cfg(test)]
    Test(Box<dyn RangeReader + Send>),
}

enum SessionKind {
    Zip {
        reader: SessionReader,
        index: CbzIndex,
    },
}

impl RangeReader for SessionReader {
    fn size(&self) -> Result<u64> {
        match self {
            SessionReader::Local(reader) => reader.size(),
            SessionReader::RemoteRange(reader) => reader.as_ref().size(),
            #[cfg(test)]
            SessionReader::Test(reader) => reader.size(),
        }
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        match self {
            SessionReader::Local(reader) => reader.read_range(start, end_inclusive),
            SessionReader::RemoteRange(reader) => {
                RangeReader::read_range(reader.as_ref(), start, end_inclusive)
            }
            #[cfg(test)]
            SessionReader::Test(reader) => reader.read_range(start, end_inclusive),
        }
    }

    fn read_cached_range(&self, start: u64, end_inclusive: u64) -> Result<Option<Vec<u8>>> {
        match self {
            SessionReader::Local(reader) => reader.read_cached_range(start, end_inclusive),
            SessionReader::RemoteRange(reader) => {
                RangeReader::read_cached_range(reader.as_ref(), start, end_inclusive)
            }
            #[cfg(test)]
            SessionReader::Test(reader) => reader.read_cached_range(start, end_inclusive),
        }
    }
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
struct SessionEntry {
    core: Mutex<CbzSession>,
    remote_io: Option<Arc<RemoteRangeSession<JniRangeTransport>>>,
}

type SharedSession = Arc<SessionEntry>;

static SESSIONS: Lazy<Mutex<HashMap<ComicHandle, SharedSession>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));
const MAX_SESSIONS: usize = 256;

pub(crate) fn open_local_path(path: &Path) -> Result<ComicHandle> {
    let reader = FileRangeReader::open(path)?;
    let index = open_cbz(&reader)?;
    insert_session(
        SessionKind::Zip {
            reader: SessionReader::Local(reader),
            index,
        },
        None,
        None,
    )
}

pub(crate) fn open_remote_range_session(
    transport: JniRangeTransport,
    cache_dir: PathBuf,
    comic_key: String,
    file_size: u64,
    validator: String,
) -> Result<ComicHandle> {
    if file_size == 0 {
        return Err(anyhow!("remote file size must be positive"));
    }
    let reader = Arc::new(RemoteRangeSession::new(file_size, transport));
    let key = IndexCacheKey {
        comic_key,
        file_size,
        validator,
    };
    let index = open_cbz_with_index_cache(reader.as_ref(), &cache_dir, &key)?;
    insert_session(
        SessionKind::Zip {
            reader: SessionReader::RemoteRange(Arc::clone(&reader)),
            index,
        },
        Some(SessionIndexCache { cache_dir, key }),
        Some(reader),
    )
}

pub(crate) fn open_local_fd(fd: i32, size_hint: Option<u64>) -> Result<ComicHandle> {
    let reader = open_local_fd_reader(fd, size_hint)?;
    let index = open_cbz(&reader)?;
    insert_session(
        SessionKind::Zip {
            reader: SessionReader::Local(reader),
            index,
        },
        None,
        None,
    )
}

#[cfg(unix)]
fn open_local_fd_reader(fd: i32, size_hint: Option<u64>) -> Result<FileRangeReader> {
    if fd < 0 {
        return Err(anyhow!("invalid local comic file descriptor"));
    }
    // SAFETY: the caller transfers ownership of a valid, open file descriptor.
    let file = unsafe { File::from_raw_fd(fd) };
    FileRangeReader::from_file(file, size_hint)
}

#[cfg(not(unix))]
fn open_local_fd_reader(_fd: i32, _size_hint: Option<u64>) -> Result<FileRangeReader> {
    Err(anyhow!(
        "local file descriptors are not supported on this platform"
    ))
}

fn insert_session(
    kind: SessionKind,
    index_cache: Option<SessionIndexCache>,
    remote_io: Option<Arc<RemoteRangeSession<JniRangeTransport>>>,
) -> Result<ComicHandle> {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle == 0 {
        return Err(anyhow!("native handle counter overflowed"));
    }
    let mut sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    if sessions.len() >= MAX_SESSIONS {
        return Err(anyhow!(
            "native session table full (max {}); close existing sessions before opening new ones",
            MAX_SESSIONS
        ));
    }
    sessions.insert(
        handle,
        Arc::new(SessionEntry {
            core: Mutex::new(CbzSession {
                kind,
                diagnostics: SessionDiagnostics::default(),
                index_cache,
                index_cache_dirty: false,
            }),
            remote_io,
        }),
    );
    Ok(handle)
}

fn session_for_handle(handle: ComicHandle) -> Result<SharedSession> {
    let sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    sessions
        .get(&handle)
        .cloned()
        .ok_or_else(|| ComicCoreError::InvalidZip("native handle not found".to_string()).into())
}

pub(crate) fn close_session(handle: ComicHandle) {
    let removed = SESSIONS
        .lock()
        .ok()
        .and_then(|mut sessions| sessions.remove(&handle));
    if let Some(remote_io) = removed
        .as_ref()
        .and_then(|session| session.remote_io.as_ref())
    {
        let _ = remote_io.close();
    }
}

pub(crate) fn prefetch_remote_range(
    handle: ComicHandle,
    range: ByteRange,
    priority: u8,
    protected_ranges: &[ByteRange],
) -> Result<bool> {
    let session = session_for_handle(handle)?;
    let remote_io = session.remote_io.as_ref().ok_or_else(|| {
        ComicCoreError::InvalidZip(
            "native handle does not own a cached remote range session".to_string(),
        )
    })?;
    remote_io
        .prefetch(range, priority, protected_ranges)
        .map_err(Into::into)
}

pub(crate) fn cancel_remote_io(handle: ComicHandle) -> Result<()> {
    let session = session_for_handle(handle)?;
    let remote_io = session.remote_io.as_ref().ok_or_else(|| {
        ComicCoreError::InvalidZip(
            "native handle does not own a cached remote range session".to_string(),
        )
    })?;
    remote_io.cancel().map_err(Into::into)
}

pub(crate) fn page_count(handle: ComicHandle) -> Result<i32> {
    let session = session_for_handle(handle)?;
    let session = session
        .core
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    Ok(match &session.kind {
        SessionKind::Zip { index, .. } => index.pages.len() as i32,
    })
}

pub(crate) fn load_page_to_file(
    handle: ComicHandle,
    page_index: usize,
    output_path: &Path,
) -> Result<()> {
    if output_path.is_file() && output_path.metadata()?.len() > 0 {
        return Ok(());
    }
    let bytes = {
        let session = session_for_handle(handle)?;
        let mut session = session
            .core
            .lock()
            .map_err(|_| anyhow!("native session lock poisoned"))?;
        match &mut session.kind {
            SessionKind::Zip { reader, index } => {
                let page = index.extract_page(reader, page_index)?;
                if page.data_offset_updated {
                    session.index_cache_dirty = true;
                }
                page.bytes
            }
        }
    };
    let tmp_path = output_path.with_extension("tmp");
    fs::write(&tmp_path, bytes)?;
    fs::rename(&tmp_path, output_path)?;
    Ok(())
}

pub(crate) fn update_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
) -> Result<()> {
    let session = session_for_handle(handle)?;
    let mut session = session
        .core
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    let (index, reader) = match &session.kind {
        SessionKind::Zip { reader, index } => (index, reader),
    };
    if page_index >= index.pages.len() {
        return Err(ComicCoreError::InvalidZip("page index out of bounds".to_string()).into());
    }

    let file_size = reader.size()?;
    let planned = build_planned_ranges(
        index,
        file_size,
        page_index,
        network_class,
        forward_prefetch_window,
    );
    if let SessionReader::RemoteRange(reader) = reader {
        reader.set_read_ahead_bytes(demand_read_ahead_bytes(network_class));
    }
    session.diagnostics = SessionDiagnostics {
        viewport_page: Some(page_index),
        planned_request_count: planned.len(),
        planned_bytes: planned.iter().map(PlannedRangeDto::byte_len).sum(),
    };
    Ok(())
}

pub(crate) fn planned_ranges_for_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
) -> Result<Vec<PlannedRangeDto>> {
    let session = session_for_handle(handle)?;
    let session = session
        .core
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    let (index, reader) = match &session.kind {
        SessionKind::Zip { reader, index } => (index, reader),
    };
    if page_index >= index.pages.len() {
        return Err(ComicCoreError::InvalidZip("page index out of bounds".to_string()).into());
    }
    let file_size = reader.size()?;
    Ok(build_planned_ranges(
        index,
        file_size,
        page_index,
        network_class,
        forward_prefetch_window,
    ))
}

pub(crate) fn reconcile_prefetch_plan_for_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
    active_ranges: Vec<PlannedPageRange>,
    completed_ranges: Vec<PlannedPageRange>,
    byte_budget: u64,
) -> Result<ReconciledPrefetchPlan> {
    let session = session_for_handle(handle)?;
    let session = session
        .core
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    let (index, reader) = match &session.kind {
        SessionKind::Zip { reader, index } => (index, reader),
    };
    if page_index >= index.pages.len() {
        return Err(ComicCoreError::InvalidZip("page index out of bounds".to_string()).into());
    }

    let file_size = reader.size()?;
    validate_reconciliation_ranges("active", &active_ranges, index.pages.len(), file_size)?;
    validate_reconciliation_ranges("completed", &completed_ranges, index.pages.len(), file_size)?;
    let planned_ranges = build_planned_ranges(
        index,
        file_size,
        page_index,
        network_class,
        forward_prefetch_window,
    )
    .into_iter()
    .map(planned_page_range_from_dto)
    .collect::<Vec<_>>();
    Ok(reconcile_prefetch_plan(
        &planned_ranges,
        &active_ranges,
        &completed_ranges,
        byte_budget,
    ))
}

fn validate_reconciliation_ranges(
    label: &str,
    ranges: &[PlannedPageRange],
    page_count: usize,
    file_size: u64,
) -> Result<()> {
    for range in ranges {
        if range.range.end_inclusive < range.range.start || range.range.end_inclusive >= file_size {
            return Err(anyhow!("{label} prefetch range is outside the file"));
        }
        if range.pages.is_empty() || range.pages.iter().any(|page| *page >= page_count) {
            return Err(anyhow!("{label} prefetch range contains an invalid page"));
        }
    }
    Ok(())
}

fn build_planned_ranges(
    index: &CbzIndex,
    file_size: u64,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
) -> Vec<PlannedRangeDto> {
    let plan = plan_prefetch_with_forward_window(
        index.pages.len(),
        page_index,
        network_class,
        forward_prefetch_window,
    );
    let page_ranges = plan
        .tasks
        .iter()
        .filter_map(|task| {
            let page = index.pages.get(task.page_index)?;
            let range = planned_page_byte_range(page, file_size)?;
            Some(PageByteRange {
                page_index: task.page_index,
                priority: task.priority,
                range,
            })
        })
        .collect();
    let mut planned: Vec<PlannedRangeDto> = plan_page_ranges(page_ranges)
        .into_iter()
        .map(planned_range_dto)
        .collect();
    planned.sort_by_key(|range| range.priority);
    planned
}

fn planned_range_dto(range: PlannedPageRange) -> PlannedRangeDto {
    PlannedRangeDto {
        start: range.range.start,
        end_inclusive: range.range.end_inclusive,
        pages: range.pages,
        priority: range.priority,
    }
}

fn planned_page_range_from_dto(range: PlannedRangeDto) -> PlannedPageRange {
    PlannedPageRange {
        range: ByteRange::new(range.start, range.end_inclusive),
        pages: range.pages,
        priority: range.priority,
    }
}

fn planned_page_byte_range(page: &CbzPageEntry, file_size: u64) -> Option<ByteRange> {
    let file_end = file_size.checked_sub(1)?;
    let (start, end) = if let Some(data_offset) = page.data_offset {
        let end = data_offset.checked_add(page.compressed_size.checked_sub(1)?)?;
        (data_offset, end)
    } else {
        let header_len = LOCAL_HEADER_MIN_SIZE
            .checked_add(page.filename_len as u64)?
            .checked_add(4 * 1024)?;
        let range_len = header_len.checked_add(page.compressed_size)?;
        let end = page
            .local_header_offset
            .checked_add(range_len.checked_sub(1)?)?;
        (page.local_header_offset, end)
    };
    if start > file_end {
        return None;
    }
    Some(ByteRange::new(start, end.min(file_end)))
}

pub(crate) fn session_diagnostics(handle: ComicHandle) -> Result<String> {
    let session = session_for_handle(handle)?;
    let session = session
        .core
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    Ok(format!(
        "viewport_page={};planned_request_count={};planned_bytes={}",
        session
            .diagnostics
            .viewport_page
            .map(|page| page.to_string())
            .unwrap_or_else(|| "none".to_string()),
        session.diagnostics.planned_request_count,
        session.diagnostics.planned_bytes,
    ))
}

pub(crate) fn network_class_from_i32(value: i32) -> NetworkClass {
    match value {
        1 => NetworkClass::Mobile,
        2 => NetworkClass::Wifi,
        _ => NetworkClass::Unknown,
    }
}

/// Demand read-ahead is only enabled on unmetered networks. Unknown classes stay
/// conservative because speculative bytes must never surprise a metered link.
fn demand_read_ahead_bytes(network_class: NetworkClass) -> u64 {
    match network_class {
        NetworkClass::Wifi => crate::remote::range_session::WIFI_DEMAND_READ_AHEAD_BYTES,
        NetworkClass::Mobile | NetworkClass::Unknown => 0,
    }
}

pub(crate) fn forward_prefetch_window_from_i32(value: i32) -> usize {
    if value <= 0 {
        4
    } else {
        (value as usize).min(16)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        CbzIndex, CbzPageEntry, CbzSession, SessionIndexCache, SessionKind, SessionReader,
    };
    use super::{
        close_session, insert_session, load_page_to_file, page_count,
        reconcile_prefetch_plan_for_viewport,
    };
    use super::demand_read_ahead_bytes;
    use crate::cache::index_cache::{IndexCacheKey, load_index_cache};
    use crate::scheduler::prefetch::NetworkClass;
    use crate::zip::RangeReader;
    use anyhow::Result;
    use std::sync::{Mutex, mpsc};
    use std::thread;
    use std::time::Duration;
    use tempfile::TempDir;

    #[test]
    fn demand_read_ahead_is_enabled_only_for_wifi() {
        assert_eq!(0, demand_read_ahead_bytes(NetworkClass::Unknown));
        assert_eq!(0, demand_read_ahead_bytes(NetworkClass::Mobile));
        assert_eq!(
            crate::remote::range_session::WIFI_DEMAND_READ_AHEAD_BYTES,
            demand_read_ahead_bytes(NetworkClass::Wifi)
        );
    }

    #[test]
    fn session_reader_forwards_cache_only_reads() {
        let reader = SessionReader::Test(Box::new(CacheOnlyReader));

        let cached = reader.read_cached_range(10, 12).unwrap();

        assert_eq!(Some(vec![10, 11, 12]), cached);
    }

    #[test]
    fn session_reconciliation_builds_the_viewport_plan_before_post_processing() {
        let handle = insert_test_zip_session(Box::new(CacheOnlyReader));

        let plan = reconcile_prefetch_plan_for_viewport(
            handle,
            0,
            NetworkClass::Wifi,
            4,
            Vec::new(),
            Vec::new(),
            48 * 1024 * 1024,
        )
        .unwrap();
        close_session(handle);

        assert_eq!(vec![0, 1, 2, 3, 4], plan.retained_pages);
        assert_eq!(1, plan.tasks.len());
        assert_eq!(0, plan.tasks[0].range.range.start);
        assert_eq!(2, plan.tasks[0].range.range.end_inclusive);
        assert_eq!(vec![0], plan.tasks[0].range.pages);
        assert!(plan.tasks[0].protected_ranges.is_empty());
    }

    #[test]
    fn dirty_index_is_flushed_once_when_session_is_dropped() {
        let temp = TempDir::new().unwrap();
        let key = IndexCacheKey {
            comic_key: "drop-flush".to_string(),
            file_size: 3,
            validator: "etag-1".to_string(),
        };
        let index = CbzIndex {
            pages: vec![CbzPageEntry {
                name: "1.jpg".to_string(),
                filename_len: 5,
                local_header_offset: 0,
                data_offset: Some(42),
                compressed_size: 3,
                uncompressed_size: 3,
                compression_method: 0,
                crc32: crc32fast::hash(&[1, 2, 3]),
            }],
        };
        let session = CbzSession {
            kind: SessionKind::Zip {
                reader: SessionReader::Test(Box::new(CacheOnlyReader)),
                index,
            },
            diagnostics: Default::default(),
            index_cache: Some(SessionIndexCache {
                cache_dir: temp.path().to_path_buf(),
                key: key.clone(),
            }),
            index_cache_dirty: true,
        };

        drop(session);

        let cached = load_index_cache(temp.path(), &key).unwrap().unwrap();
        assert_eq!(Some(42), cached.pages[0].data_offset);
    }

    #[test]
    fn slow_page_read_does_not_block_other_sessions_or_close() {
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let slow_handle = insert_test_zip_session(Box::new(BlockingReader {
            entered: entered_tx,
            release: Mutex::new(release_rx),
        }));
        let fast_handle = insert_test_zip_session(Box::new(CacheOnlyReader));
        let temp = TempDir::new().unwrap();
        let output_path = temp.path().join("slow-page.bin");
        let thread_output_path = output_path.clone();
        let load_thread =
            thread::spawn(move || load_page_to_file(slow_handle, 0, &thread_output_path));

        if entered_rx.recv_timeout(Duration::from_secs(1)).is_err() {
            let _ = release_tx.send(());
            let _ = load_thread.join();
            panic!("slow page read did not enter the range reader");
        }

        let (close_done_tx, close_done_rx) = mpsc::channel();
        let close_thread = thread::spawn(move || {
            close_session(slow_handle);
            let _ = close_done_tx.send(());
        });
        let (count_tx, count_rx) = mpsc::channel();
        let count_thread = thread::spawn(move || {
            let _ = count_tx.send(page_count(fast_handle).unwrap_or(-1));
        });

        let close_result = close_done_rx.recv_timeout(Duration::from_secs(1));
        let fast_count_result = count_rx.recv_timeout(Duration::from_secs(1));
        let closed_count = close_result
            .is_ok()
            .then(|| page_count(slow_handle).unwrap_or(-1));

        let _ = release_tx.send(());
        let load_result = load_thread.join().unwrap();
        close_thread.join().unwrap();
        count_thread.join().unwrap();
        close_session(fast_handle);

        assert!(
            close_result.is_ok(),
            "close waited for an unrelated page read"
        );
        assert_eq!(Ok(1), fast_count_result);
        assert_eq!(Some(-1), closed_count);
        assert!(
            load_result.is_ok(),
            "in-flight read should finish after close"
        );
        assert_eq!(vec![1, 2, 3], std::fs::read(output_path).unwrap());
    }

    struct BlockingReader {
        entered: mpsc::Sender<()>,
        release: Mutex<mpsc::Receiver<()>>,
    }

    impl RangeReader for BlockingReader {
        fn size(&self) -> Result<u64> {
            Ok(3)
        }

        fn read_range(&self, _start: u64, _end_inclusive: u64) -> Result<Vec<u8>> {
            let _ = self.entered.send(());
            self.release.lock().unwrap().recv().unwrap();
            Ok(vec![1, 2, 3])
        }
    }

    struct CacheOnlyReader;

    impl RangeReader for CacheOnlyReader {
        fn size(&self) -> Result<u64> {
            Ok(100)
        }

        fn read_range(&self, _start: u64, _end_inclusive: u64) -> Result<Vec<u8>> {
            Ok(Vec::new())
        }

        fn read_cached_range(&self, start: u64, end_inclusive: u64) -> Result<Option<Vec<u8>>> {
            Ok(Some(
                (start..=end_inclusive).map(|byte| byte as u8).collect(),
            ))
        }
    }

    fn insert_test_zip_session(reader: Box<dyn RangeReader + Send>) -> u64 {
        insert_session(
            SessionKind::Zip {
                reader: SessionReader::Test(reader),
                index: CbzIndex {
                    pages: vec![CbzPageEntry {
                        name: "1.jpg".to_string(),
                        filename_len: 5,
                        local_header_offset: 0,
                        data_offset: Some(0),
                        compressed_size: 3,
                        uncompressed_size: 3,
                        compression_method: 0,
                        crc32: crc32fast::hash(&[1, 2, 3]),
                    }],
                },
            },
            None,
            None,
        )
        .unwrap()
    }
}
