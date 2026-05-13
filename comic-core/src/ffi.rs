use anyhow::{anyhow, Result};
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use jni::sys::{jint, jlong, jstring, JNI_ERR, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM, NativeMethod};
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fs;
use std::os::raw::{c_char, c_void};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use crate::cache::index_cache::{open_cbz_with_index_cache, IndexCacheKey};
use crate::cbz::{open_cbz, CbzIndex};
use crate::error::ComicCoreError;
use crate::remote::jni_range_reader::JniRangeReader;
use crate::scheduler::prefetch::{plan_prefetch, NetworkClass};
use crate::scheduler::range_planner::{plan_ranges, ByteRange};
use crate::zip::{FileRangeReader, RangeReader};

pub type ComicHandle = u64;

struct CbzSession {
    reader: SessionReader,
    index: CbzIndex,
    diagnostics: SessionDiagnostics,
}

#[derive(Debug, Default, Clone)]
struct SessionDiagnostics {
    viewport_page: Option<usize>,
    planned_request_count: usize,
}

enum SessionReader {
    Local(FileRangeReader),
    Remote(JniRangeReader),
}

impl RangeReader for SessionReader {
    fn size(&self) -> Result<u64> {
        match self {
            SessionReader::Local(reader) => reader.size(),
            SessionReader::Remote(reader) => reader.size(),
        }
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        match self {
            SessionReader::Local(reader) => reader.read_range(start, end_inclusive),
            SessionReader::Remote(reader) => reader.read_range(start, end_inclusive),
        }
    }
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static SESSIONS: Lazy<Mutex<HashMap<ComicHandle, CbzSession>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));
static LAST_ERROR: Lazy<Mutex<CString>> = Lazy::new(|| Mutex::new(CString::default()));

#[no_mangle]
pub extern "C" fn comic_open_local(path: *const c_char) -> ComicHandle {
    match read_c_string(path).and_then(|path| open_local_path(Path::new(&path))) {
        Ok(handle) => handle,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

#[no_mangle]
pub extern "C" fn comic_page_count(handle: ComicHandle) -> i32 {
    match page_count(handle) {
        Ok(count) => count,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

#[no_mangle]
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

#[no_mangle]
pub extern "C" fn comic_close(handle: ComicHandle) {
    if let Ok(mut sessions) = SESSIONS.lock() {
        sessions.remove(&handle);
    }
}

#[no_mangle]
pub extern "C" fn comic_last_error_message() -> *const c_char {
    LAST_ERROR
        .lock()
        .map(|message| message.as_ptr())
        .unwrap_or(std::ptr::null())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
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
            "(Ljava/lang/String;)J",
            native_open_local as *const () as *mut c_void,
        ),
        native_method(
            "openRemote",
            "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)J",
            native_open_remote as *const () as *mut c_void,
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
            "(JII)I",
            native_update_viewport as *const () as *mut c_void,
        ),
        native_method(
            "diagnostics",
            "(J)Ljava/lang/String;",
            native_diagnostics as *const () as *mut c_void,
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
) -> jlong {
    match jstring_to_string(&mut env, &path).and_then(|path| open_local_path(Path::new(&path))) {
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
) -> jlong {
    if file_id <= 0 || size <= 0 {
        set_last_error(ComicCoreError::InvalidZip(
            "remote file id and size must be positive".to_string(),
        ));
        return 0;
    }

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
        )
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

extern "system" fn native_last_error_message(env: JNIEnv<'_>, _class: JClass<'_>) -> jstring {
    let message = last_error_message_string();
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn open_local_path(path: &Path) -> Result<ComicHandle> {
    let reader = FileRangeReader::open(path)?;
    let index = open_cbz(&reader)?;
    insert_session(SessionReader::Local(reader), index)
}

fn open_remote_reader(
    reader: JniRangeReader,
    cache_dir: PathBuf,
    comic_key: String,
    file_size: u64,
    validator: String,
) -> Result<ComicHandle> {
    let key = IndexCacheKey {
        comic_key,
        file_size,
        validator,
    };
    let index = open_cbz_with_index_cache(&reader, &cache_dir, &key)?;
    insert_session(SessionReader::Remote(reader), index)
}

fn insert_session(reader: SessionReader, index: CbzIndex) -> Result<ComicHandle> {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle == 0 {
        return Err(anyhow!("native handle counter overflowed"));
    }
    let mut sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    sessions.insert(
        handle,
        CbzSession {
            reader,
            index,
            diagnostics: SessionDiagnostics::default(),
        },
    );
    Ok(handle)
}

fn page_count(handle: ComicHandle) -> Result<i32> {
    let sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    let session = sessions
        .get(&handle)
        .ok_or_else(|| ComicCoreError::InvalidZip("native handle not found".to_string()))?;
    Ok(session.index.pages.len() as i32)
}

fn load_page_to_file(handle: ComicHandle, page_index: usize, output_path: &Path) -> Result<()> {
    if output_path.is_file() && output_path.metadata()?.len() > 0 {
        return Ok(());
    }
    let bytes = {
        let sessions = SESSIONS
            .lock()
            .map_err(|_| anyhow!("native session table lock poisoned"))?;
        let session = sessions
            .get(&handle)
            .ok_or_else(|| ComicCoreError::InvalidZip("native handle not found".to_string()))?;
        session.index.extract_page(&session.reader, page_index)?
    };
    fs::write(output_path, bytes)?;
    Ok(())
}

fn update_viewport(
    handle: ComicHandle,
    page_index: usize,
    network_class: NetworkClass,
) -> Result<()> {
    let mut sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    let session = sessions
        .get_mut(&handle)
        .ok_or_else(|| ComicCoreError::InvalidZip("native handle not found".to_string()))?;
    if page_index >= session.index.pages.len() {
        return Err(ComicCoreError::InvalidZip("page index out of bounds".to_string()).into());
    }

    let plan = plan_prefetch(session.index.pages.len(), page_index, network_class);
    let ranges = plan
        .tasks
        .iter()
        .filter_map(|task| session.index.pages.get(task.page_index))
        .filter_map(|page| {
            let end = page
                .local_header_offset
                .checked_add(page.compressed_size)?
                .checked_add(4096)?;
            Some(ByteRange::new(page.local_header_offset, end))
        })
        .collect();
    let planned = plan_ranges(ranges);
    session.diagnostics = SessionDiagnostics {
        viewport_page: Some(page_index),
        planned_request_count: planned.request_count,
    };
    Ok(())
}

fn session_diagnostics(handle: ComicHandle) -> Result<String> {
    let sessions = SESSIONS
        .lock()
        .map_err(|_| anyhow!("native session table lock poisoned"))?;
    let session = sessions
        .get(&handle)
        .ok_or_else(|| ComicCoreError::InvalidZip("native handle not found".to_string()))?;
    Ok(format!(
        "viewport_page={};planned_request_count={}",
        session
            .diagnostics
            .viewport_page
            .map(|page| page.to_string())
            .unwrap_or_else(|| "none".to_string()),
        session.diagnostics.planned_request_count,
    ))
}

fn network_class_from_i32(value: jint) -> NetworkClass {
    match value {
        1 => NetworkClass::Mobile,
        2 => NetworkClass::Wifi,
        _ => NetworkClass::Unknown,
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
    if let Ok(mut message) = LAST_ERROR.lock() {
        *message = CString::new(sanitized).unwrap_or_default();
    }
}

fn last_error_message_string() -> String {
    LAST_ERROR
        .lock()
        .ok()
        .and_then(|message| message.to_str().ok().map(ToOwned::to_owned))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::{comic_close, comic_open_local, comic_page_count};
    use std::ffi::CString;
    use std::fs::File;
    use std::io::Write;
    use tempfile::NamedTempFile;
    use zip::write::SimpleFileOptions;
    use zip::{CompressionMethod, ZipWriter};

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
