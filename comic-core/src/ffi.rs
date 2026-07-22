use anyhow::{Result, anyhow};
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use jni::sys::{JNI_ERR, JNI_VERSION_1_6, jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM, NativeMethod};
use once_cell::sync::Lazy;
use serde::Serialize;
use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fs;
use std::os::raw::{c_char, c_void};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use crate::archive::{ArchiveFormat, LocalArchiveSession, open_local_archive_fd_with_options};
use crate::cache::index_cache::{
    IndexCacheKey, open_cbz_with_index_cache_options, store_index_cache_with_options,
};
use crate::cbz::{CbzIndex, CbzPageEntry, open_cbz_with_options};
use crate::error::ComicCoreError;
use crate::image::ImageFormatOptions;
use crate::remote::jni_range_reader::JniRangeReader;
use crate::scheduler::prefetch::{NetworkClass, plan_prefetch_with_forward_window};
use crate::scheduler::range_planner::{
    ByteRange, PageByteRange, PlannedPageRange, plan_page_ranges,
};
use crate::zip::local_header::LOCAL_HEADER_MIN_SIZE;
use crate::zip::{FileRangeReader, RangeReader};

pub type ComicHandle = u64;

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
        let _ = store_index_cache_with_options(&cache.cache_dir, &cache.key, cache.options, index);
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
    options: ImageFormatOptions,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
struct PlannedRangeDto {
    start: u64,
    end_inclusive: u64,
    pages: Vec<usize>,
    priority: u8,
}

impl PlannedRangeDto {
    fn byte_len(&self) -> u64 {
        self.end_inclusive.saturating_sub(self.start) + 1
    }
}

enum SessionReader {
    Local(FileRangeReader),
    Remote(JniRangeReader),
    #[cfg(test)]
    Test(Box<dyn RangeReader + Send>),
}

enum SessionKind {
    Zip {
        reader: SessionReader,
        index: CbzIndex,
    },
    LocalArchive(Box<LocalArchiveSession>),
}

impl RangeReader for SessionReader {
    fn size(&self) -> Result<u64> {
        match self {
            SessionReader::Local(reader) => reader.size(),
            SessionReader::Remote(reader) => reader.size(),
            #[cfg(test)]
            SessionReader::Test(reader) => reader.size(),
        }
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        match self {
            SessionReader::Local(reader) => reader.read_range(start, end_inclusive),
            SessionReader::Remote(reader) => reader.read_range(start, end_inclusive),
            #[cfg(test)]
            SessionReader::Test(reader) => reader.read_range(start, end_inclusive),
        }
    }

    fn read_cached_range(&self, start: u64, end_inclusive: u64) -> Result<Option<Vec<u8>>> {
        match self {
            SessionReader::Local(reader) => reader.read_cached_range(start, end_inclusive),
            SessionReader::Remote(reader) => reader.read_cached_range(start, end_inclusive),
            #[cfg(test)]
            SessionReader::Test(reader) => reader.read_cached_range(start, end_inclusive),
        }
    }
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
type SharedSession = Arc<Mutex<CbzSession>>;

static SESSIONS: Lazy<Mutex<HashMap<ComicHandle, SharedSession>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));
const MAX_SESSIONS: usize = 256;
thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

#[unsafe(no_mangle)]
pub extern "C" fn comic_open_local(path: *const c_char) -> ComicHandle {
    match read_c_string(path)
        .and_then(|path| open_local_path(Path::new(&path), ImageFormatOptions::default()))
    {
        Ok(handle) => handle,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn comic_page_count(handle: ComicHandle) -> i32 {
    match page_count(handle) {
        Ok(count) => count,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn comic_load_page_to_file(
    handle: ComicHandle,
    page_index: u32,
    output_path: *const c_char,
) -> i32 {
    match read_c_string(output_path)
        .and_then(|path| load_page_to_file(handle, page_index as usize, Path::new(&path)))
    {
        Ok(()) => 0,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn comic_close(handle: ComicHandle) {
    if let Ok(mut sessions) = SESSIONS.lock() {
        sessions.remove(&handle);
    }
}

/// Returns a pointer to the current thread's last error CString.
/// The pointer is only valid on the same thread and only until the next native error is set.
#[unsafe(no_mangle)]
pub extern "C" fn comic_last_error_message() -> *const c_char {
    LAST_ERROR.with(|cell| cell.borrow().as_ptr())
}

/// Called by the JVM when the dynamic library is loaded.
///
/// # Safety
///
/// `vm` must be the valid `JavaVM` pointer provided by the JVM for this library load.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    let vm = match unsafe { JavaVM::from_raw(vm) } {
        Ok(vm) => vm,
        Err(error) => {
            set_last_error(error);
            return JNI_ERR;
        }
    };
    let mut env = match vm.get_env() {
        Ok(env) => env,
        Err(error) => {
            set_last_error(error);
            return JNI_ERR;
        }
    };

    match register_natives(&mut env) {
        Ok(()) => JNI_VERSION_1_6,
        Err(error) => {
            set_last_error(error);
            JNI_ERR
        }
    }
}

fn register_natives(env: &mut JNIEnv<'_>) -> Result<()> {
    let class = env.find_class("com/example/comicdav/nativebridge/ComicNative")?;
    let methods = [
        native_method(
            "openLocal",
            "(Ljava/lang/String;Z)J",
            native_open_local as *const () as *mut c_void,
        ),
        native_method(
            "openRemote",
            "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)J",
            native_open_remote as *const () as *mut c_void,
        ),
        native_method(
            "openLocalFd",
            "(IJLjava/lang/String;Z)J",
            native_open_local_fd as *const () as *mut c_void,
        ),
        native_method(
            "pageCount",
            "(J)I",
            native_page_count as *const () as *mut c_void,
        ),
        native_method(
            "loadPageToFile",
            "(JILjava/lang/String;)I",
            native_load_page_to_file as *const () as *mut c_void,
        ),
        native_method(
            "updateViewport",
            "(JIII)I",
            native_update_viewport as *const () as *mut c_void,
        ),
        native_method(
            "diagnostics",
            "(J)Ljava/lang/String;",
            native_diagnostics as *const () as *mut c_void,
        ),
        native_method(
            "plannedRanges",
            "(JIII)Ljava/lang/String;",
            native_planned_ranges as *const () as *mut c_void,
        ),
        native_method("close", "(J)V", native_close as *const () as *mut c_void),
        native_method(
            "lastErrorMessage",
            "()Ljava/lang/String;",
            native_last_error_message as *const () as *mut c_void,
        ),
    ];
    env.register_native_methods(class, &methods)?;
    Ok(())
}

fn native_method(name: &str, sig: &str, fn_ptr: *mut c_void) -> NativeMethod {
    NativeMethod {
        name: JNIString::from(name),
        sig: JNIString::from(sig),
        fn_ptr,
    }
}

extern "system" fn native_open_local(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    avif_enabled: jboolean,
) -> jlong {
    let options = image_format_options(avif_enabled);
    match jstring_to_string(&mut env, &path)
        .and_then(|path| open_local_path(Path::new(&path), options))
    {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_open_remote(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    file_id: jlong,
    size: jlong,
    cache_dir: JString<'_>,
    comic_key: JString<'_>,
    validator: JString<'_>,
    avif_enabled: jboolean,
) -> jlong {
    if file_id <= 0 || size <= 0 {
        set_last_error(ComicCoreError::InvalidZip(
            "remote file id and size must be positive".to_string(),
        ));
        return 0;
    }

    let options = image_format_options(avif_enabled);
    match jstring_to_string(&mut env, &cache_dir).and_then(|cache_dir| {
        let comic_key = jstring_to_string(&mut env, &comic_key)?;
        let validator = jstring_to_string(&mut env, &validator)?;
        let vm = env.get_java_vm()?;
        let reader = JniRangeReader::new(vm, file_id as u64, size as u64);
        open_remote_reader(
            reader,
            PathBuf::from(cache_dir),
            comic_key,
            size as u64,
            validator,
            options,
        )
    }) {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_open_local_fd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    fd: jint,
    size: jlong,
    format: JString<'_>,
    avif_enabled: jboolean,
) -> jlong {
    let size_hint = if size > 0 { Some(size as u64) } else { None };
    let options = image_format_options(avif_enabled);
    match jstring_to_string(&mut env, &format).and_then(|format| {
        let format = archive_format_from_name(&format)?;
        let archive = open_local_archive_fd_with_options(fd, size_hint, format, options)?;
        insert_session(SessionKind::LocalArchive(Box::new(archive)), None)
    }) {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_update_viewport(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    page_index: jint,
    network_class: jint,
    forward_prefetch_page_count: jint,
) -> jint {
    if page_index < 0 {
        set_last_error(ComicCoreError::InvalidZip(
            "page index out of bounds".to_string(),
        ));
        return -1;
    }
    match update_viewport(
        handle as ComicHandle,
        page_index as usize,
        network_class_from_i32(network_class),
        forward_prefetch_window_from_i32(forward_prefetch_page_count),
    ) {
        Ok(()) => 0,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

extern "system" fn native_page_count(_env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong) -> jint {
    match page_count(handle as ComicHandle) {
        Ok(count) => count as jint,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

extern "system" fn native_load_page_to_file(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    page_index: jint,
    output_path: JString<'_>,
) -> jint {
    if page_index < 0 {
        set_last_error(ComicCoreError::InvalidZip(
            "page index out of bounds".to_string(),
        ));
        return -1;
    }

    match jstring_to_string(&mut env, &output_path).and_then(|path| {
        load_page_to_file(handle as ComicHandle, page_index as usize, Path::new(&path))
    }) {
        Ok(()) => 0,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

extern "system" fn native_close(_env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong) {
    comic_close(handle as ComicHandle);
}

extern "system" fn native_diagnostics(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let message = session_diagnostics(handle as ComicHandle).unwrap_or_default();
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

extern "system" fn native_planned_ranges(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    page_index: jint,
    network_class: jint,
    forward_prefetch_page_count: jint,
) -> jstring {
    let message = if page_index < 0 {
        set_last_error(ComicCoreError::InvalidZip(
            "page index out of bounds".to_string(),
        ));
        "v1".to_string()
    } else {
        match planned_ranges_for_viewport(
            handle as ComicHandle,
            page_index as usize,
            network_class_from_i32(network_class),
            forward_prefetch_window_from_i32(forward_prefetch_page_count),
        ) {
            Ok(ranges) => encode_planned_ranges(&ranges),
            Err(error) => {
                set_last_error(error);
                "v1".to_string()
            }
        }
    };
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

extern "system" fn native_last_error_message(env: JNIEnv<'_>, _class: JClass<'_>) -> jstring {
    let message = last_error_message_string();
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn image_format_options(avif_enabled: jboolean) -> ImageFormatOptions {
    ImageFormatOptions {
        avif: avif_enabled != 0,
    }
}

fn open_local_path(path: &Path, options: ImageFormatOptions) -> Result<ComicHandle> {
    let reader = FileRangeReader::open(path)?;
    let index = open_cbz_with_options(&reader, options)?;
    insert_session(
        SessionKind::Zip {
            reader: SessionReader::Local(reader),
            index,
        },
        None,
    )
}

fn open_remote_reader(
    reader: JniRangeReader,
    cache_dir: PathBuf,
    comic_key: String,
    file_size: u64,
    validator: String,
    options: ImageFormatOptions,
) -> Result<ComicHandle> {
    let key = IndexCacheKey {
        comic_key,
        file_size,
        validator,
    };
    let index = open_cbz_with_index_cache_options(&reader, &cache_dir, &key, options)?;
    insert_session(
        SessionKind::Zip {
            reader: SessionReader::Remote(reader),
            index,
        },
        Some(SessionIndexCache {
            cache_dir,
            key,
            options,
        }),
    )
}

fn insert_session(
    kind: SessionKind,
    index_cache: Option<SessionIndexCache>,
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
        Arc::new(Mutex::new(CbzSession {
            kind,
            diagnostics: SessionDiagnostics::default(),
            index_cache,
            index_cache_dirty: false,
        })),
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

fn page_count(handle: ComicHandle) -> Result<i32> {
    let session = session_for_handle(handle)?;
    let session = session
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    Ok(match &session.kind {
        SessionKind::Zip { index, .. } => index.pages.len() as i32,
        SessionKind::LocalArchive(archive) => archive.page_count() as i32,
    })
}

fn load_page_to_file(handle: ComicHandle, page_index: usize, output_path: &Path) -> Result<()> {
    if output_path.is_file() && output_path.metadata()?.len() > 0 {
        return Ok(());
    }
    let bytes = {
        let session = session_for_handle(handle)?;
        let mut session = session
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
            SessionKind::LocalArchive(archive) => {
                if archive.materialize_solid_page_to_file(page_index, output_path)? {
                    return Ok(());
                }
                archive.extract_page(page_index)?
            }
        }
    };
    let tmp_path = output_path.with_extension("tmp");
    fs::write(&tmp_path, bytes)?;
    fs::rename(&tmp_path, output_path)?;
    Ok(())
}

fn update_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
) -> Result<()> {
    let session = session_for_handle(handle)?;
    let mut session = session
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    let (index, reader) = match &session.kind {
        SessionKind::Zip { reader, index } => (index, reader),
        SessionKind::LocalArchive(_) => {
            session.diagnostics = SessionDiagnostics::default();
            return Ok(());
        }
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
    session.diagnostics = SessionDiagnostics {
        viewport_page: Some(page_index),
        planned_request_count: planned.len(),
        planned_bytes: planned.iter().map(PlannedRangeDto::byte_len).sum(),
    };
    Ok(())
}

fn planned_ranges_for_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
    forward_prefetch_window: usize,
) -> Result<Vec<PlannedRangeDto>> {
    let session = session_for_handle(handle)?;
    let session = session
        .lock()
        .map_err(|_| anyhow!("native session lock poisoned"))?;
    let (index, reader) = match &session.kind {
        SessionKind::Zip { reader, index } => (index, reader),
        SessionKind::LocalArchive(_) => return Ok(Vec::new()),
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

fn encode_planned_ranges(ranges: &[PlannedRangeDto]) -> String {
    if ranges.is_empty() {
        return "v1".to_string();
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
    format!("v1;{entries}")
}

fn session_diagnostics(handle: ComicHandle) -> Result<String> {
    let session = session_for_handle(handle)?;
    let session = session
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

fn network_class_from_i32(value: jint) -> NetworkClass {
    match value {
        1 => NetworkClass::Mobile,
        2 => NetworkClass::Wifi,
        _ => NetworkClass::Unknown,
    }
}

fn forward_prefetch_window_from_i32(value: jint) -> usize {
    if value <= 0 {
        4
    } else {
        (value as usize).min(16)
    }
}

fn archive_format_from_name(value: &str) -> Result<ArchiveFormat> {
    match value {
        "zip" => Ok(ArchiveFormat::Zip),
        "7z" => Ok(ArchiveFormat::SevenZ),
        "tar" => Ok(ArchiveFormat::Tar),
        _ => Err(anyhow!("unsupported local archive format: {value}")),
    }
}

fn read_c_string(value: *const c_char) -> Result<String> {
    if value.is_null() {
        return Err(anyhow!("null string pointer"));
    }
    let value = unsafe { CStr::from_ptr(value) };
    Ok(value.to_string_lossy().into_owned())
}

fn jstring_to_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String> {
    Ok(env.get_string(value)?.into())
}

fn set_last_error(error: impl std::fmt::Display) {
    let sanitized = error.to_string().replace('\0', "\\0");
    LAST_ERROR.with(|cell| {
        *cell.borrow_mut() = CString::new(sanitized).unwrap_or_default();
    });
}

fn last_error_message_string() -> String {
    LAST_ERROR.with(|cell| cell.borrow().to_str().unwrap_or_default().to_owned())
}

#[cfg(test)]
mod tests {
    use super::{
        CbzIndex, CbzPageEntry, CbzSession, SessionIndexCache, SessionKind, SessionReader,
    };
    use super::{comic_close, comic_open_local, comic_page_count};
    use super::{insert_session, load_page_to_file};
    use super::{last_error_message_string, set_last_error};
    use crate::cache::index_cache::{IndexCacheKey, load_index_cache};
    use crate::image::ImageFormatOptions;
    use crate::zip::RangeReader;
    use anyhow::Result;
    use std::ffi::CString;
    use std::fs::File;
    use std::io::Write;
    use std::sync::{Mutex, mpsc};
    use std::thread;
    use std::time::Duration;
    use tempfile::{NamedTempFile, TempDir};
    use zip::write::SimpleFileOptions;
    use zip::{CompressionMethod, ZipWriter};

    #[test]
    fn last_error_is_thread_local() {
        // Main thread sets error A
        set_last_error("error_A");
        assert_eq!(last_error_message_string(), "error_A");

        // Child thread sets error B and reads it back
        let child = std::thread::spawn(|| {
            set_last_error("error_B");
            assert_eq!(last_error_message_string(), "error_B");
        });
        child.join().unwrap();

        // Main thread still reads error A
        assert_eq!(last_error_message_string(), "error_A");
    }

    #[test]
    fn opens_counts_and_closes_local_cbz_session() {
        let archive = make_zip(&[
            ("1.jpg", b"one".as_slice(), CompressionMethod::Stored),
            ("2.jpg", b"two".as_slice(), CompressionMethod::Stored),
        ]);
        let path = CString::new(archive.path().to_string_lossy().as_bytes()).unwrap();

        let handle = comic_open_local(path.as_ptr());

        assert_ne!(0, handle);
        assert_eq!(2, comic_page_count(handle));

        comic_close(handle);
        assert_eq!(-1, comic_page_count(handle));
    }

    #[test]
    fn session_reader_forwards_cache_only_reads() {
        let reader = SessionReader::Test(Box::new(CacheOnlyReader));

        let cached = reader.read_cached_range(10, 12).unwrap();

        assert_eq!(Some(vec![10, 11, 12]), cached);
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
                options: ImageFormatOptions::default(),
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
            comic_close(slow_handle);
            let _ = close_done_tx.send(());
        });
        let (count_tx, count_rx) = mpsc::channel();
        let count_thread = thread::spawn(move || {
            let _ = count_tx.send(comic_page_count(fast_handle));
        });

        let close_result = close_done_rx.recv_timeout(Duration::from_secs(1));
        let fast_count_result = count_rx.recv_timeout(Duration::from_secs(1));
        let closed_count = close_result.is_ok().then(|| comic_page_count(slow_handle));

        let _ = release_tx.send(());
        let load_result = load_thread.join().unwrap();
        close_thread.join().unwrap();
        count_thread.join().unwrap();
        comic_close(fast_handle);

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
        )
        .unwrap()
    }

    fn make_zip(entries: &[(&str, &[u8], CompressionMethod)]) -> NamedTempFile {
        let file = NamedTempFile::new().unwrap();
        {
            let writer = File::create(file.path()).unwrap();
            let mut zip = ZipWriter::new(writer);
            for (name, bytes, method) in entries {
                let options = SimpleFileOptions::default().compression_method(*method);
                zip.start_file(*name, options).unwrap();
                zip.write_all(bytes).unwrap();
            }
            zip.finish().unwrap();
        }
        file
    }
}
