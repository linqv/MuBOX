use anyhow::Result;
use jni::objects::{JClass, JLongArray, JObject, JString};
use jni::strings::JNIString;
use jni::sys::{JNI_ERR, JNI_VERSION_1_6, jint, jlong, jlongArray, jstring};
use jni::{JNIEnv, JavaVM, NativeMethod};
use std::os::raw::c_void;
use std::path::{Path, PathBuf};

use super::prefetch_wire::{
    MAX_PREFETCH_WIRE_WORDS, ReconciledPrefetchPlanWire, decode_prefetch_ranges,
    encode_reconciled_prefetch_plan,
};
use super::range_io_wire::{MAX_PROTECTED_RANGE_WIRE_WORDS, decode_protected_ranges};
use super::{
    DiagnosticsWire, PlannedRangesWire, encode_diagnostics, encode_planned_ranges,
    last_error_message_string, set_last_error,
};
use crate::error::ComicCoreError;
use crate::remote::jni_range_transport::JniRangeTransport;
use crate::remote::range_session::RangeSessionError;
use crate::session_registry::{
    ComicHandle, archive_format_from_name, cancel_remote_io, close_session,
    forward_prefetch_window_from_i32, load_page_to_file, network_class_from_i32, open_local_fd,
    open_local_path, open_remote_range_session, page_count, planned_ranges_for_viewport,
    prefetch_remote_range, reconcile_prefetch_plan_for_viewport, session_diagnostics,
    update_viewport,
};

/// Called by the JVM when the dynamic library is loaded.
///
/// # Safety
///
/// `vm` must be the valid `JavaVM` pointer provided by the JVM for this library load.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    // SAFETY: the JVM invokes this function with its live JavaVM pointer.
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
    let class = env.find_class("org/mubox/reader/nativebridge/ComicNative")?;
    let methods = [
        native_method(
            "openLocal",
            "(Ljava/lang/String;)J",
            native_open_local as *const () as *mut c_void,
        ),
        native_method(
            "openRemoteCachedV1",
            "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)J",
            native_open_remote_cached_v1 as *const () as *mut c_void,
        ),
        native_method(
            "openLocalFd",
            "(IJLjava/lang/String;)J",
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
        native_method(
            "reconcilePrefetchPlanV1",
            "(JIIIJ[J[J)[J",
            native_reconcile_prefetch_plan_v1 as *const () as *mut c_void,
        ),
        native_method(
            "prefetchRemoteRangeV1",
            "(JJJI[J)I",
            native_prefetch_remote_range_v1 as *const () as *mut c_void,
        ),
        native_method(
            "cancelRemoteIoV1",
            "(J)V",
            native_cancel_remote_io_v1 as *const () as *mut c_void,
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

extern "system" fn native_open_local_fd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    fd: jint,
    size: jlong,
    format: JString<'_>,
) -> jlong {
    let size_hint = if size > 0 { Some(size as u64) } else { None };
    match jstring_to_string(&mut env, &format).and_then(|format| {
        let format = archive_format_from_name(&format)?;
        open_local_fd(fd, size_hint, format)
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
    close_session(handle as ComicHandle);
}

extern "system" fn native_diagnostics(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let message = match session_diagnostics(handle as ComicHandle) {
        Ok(payload) => encode_diagnostics(DiagnosticsWire::Success(&payload)),
        Err(error) => {
            set_last_error(error);
            encode_diagnostics(DiagnosticsWire::Error)
        }
    };
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
        encode_planned_ranges(PlannedRangesWire::Error)
    } else {
        match planned_ranges_for_viewport(
            handle as ComicHandle,
            page_index as usize,
            network_class_from_i32(network_class),
            forward_prefetch_window_from_i32(forward_prefetch_page_count),
        ) {
            Ok(ranges) => encode_planned_ranges(PlannedRangesWire::Success(&ranges)),
            Err(error) => {
                set_last_error(error);
                encode_planned_ranges(PlannedRangesWire::Error)
            }
        }
    };
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

extern "system" fn native_open_remote_cached_v1(
    mut env: JNIEnv<'_>,
    _instance: JObject<'_>,
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

    let result = (|| -> Result<ComicHandle> {
        let cache_dir = jstring_to_string(&mut env, &cache_dir)?;
        let comic_key = jstring_to_string(&mut env, &comic_key)?;
        let validator = jstring_to_string(&mut env, &validator)?;
        let vm = env.get_java_vm()?;
        let registry_class =
            env.find_class("org/mubox/reader/nativebridge/RangeProviderRegistry")?;
        let registry_class = env.new_global_ref(registry_class)?;
        let transport = JniRangeTransport::new(vm, file_id as u64, registry_class);
        open_remote_range_session(
            transport,
            PathBuf::from(cache_dir),
            comic_key,
            size as u64,
            validator,
        )
    })();
    match result {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_prefetch_remote_range_v1(
    mut env: JNIEnv<'_>,
    _instance: JObject<'_>,
    handle: jlong,
    start: jlong,
    end_inclusive: jlong,
    priority: jint,
    protected_ranges: JLongArray<'_>,
) -> jint {
    let result = (|| -> Result<jint> {
        if handle <= 0 {
            return Err(ComicCoreError::InvalidZip("native handle not found".to_string()).into());
        }
        if start < 0 || end_inclusive < start {
            return Err(
                ComicCoreError::InvalidZip("remote prefetch range is invalid".to_string()).into(),
            );
        }
        if !(0..=u8::MAX as jint).contains(&priority) {
            return Err(ComicCoreError::InvalidZip(
                "remote prefetch priority is outside the supported range".to_string(),
            )
            .into());
        }
        let protected_values = jlong_array_to_vec_with_limit(
            &mut env,
            &protected_ranges,
            MAX_PROTECTED_RANGE_WIRE_WORDS,
            "protected range payload",
        )?;
        let protected_ranges = decode_protected_ranges(&protected_values)?;
        prefetch_result_status(prefetch_remote_range(
            handle as ComicHandle,
            crate::scheduler::range_planner::ByteRange::new(start as u64, end_inclusive as u64),
            priority as u8,
            &protected_ranges,
        ))
    })();

    match result {
        Ok(status) => status,
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

extern "system" fn native_cancel_remote_io_v1(
    _env: JNIEnv<'_>,
    _instance: JObject<'_>,
    handle: jlong,
) {
    let result = if handle <= 0 {
        Err(ComicCoreError::InvalidZip("native handle not found".to_string()).into())
    } else {
        cancel_remote_io(handle as ComicHandle)
    };
    if let Err(error) = result
        && !is_nonfatal_remote_shutdown(&error)
    {
        set_last_error(error);
    }
}

extern "system" fn native_reconcile_prefetch_plan_v1(
    mut env: JNIEnv<'_>,
    _instance: JObject<'_>,
    handle: jlong,
    page_index: jint,
    network_class: jint,
    forward_prefetch_page_count: jint,
    byte_budget: jlong,
    active_ranges: JLongArray<'_>,
    completed_ranges: JLongArray<'_>,
) -> jlongArray {
    let result = (|| -> Result<Vec<i64>> {
        if handle <= 0 {
            return Err(ComicCoreError::InvalidZip("native handle not found".to_string()).into());
        }
        if page_index < 0 {
            return Err(ComicCoreError::InvalidZip("page index out of bounds".to_string()).into());
        }
        if byte_budget < 0 {
            return Err(ComicCoreError::InvalidZip(
                "prefetch byte budget must be non-negative".to_string(),
            )
            .into());
        }
        let active_values = jlong_array_to_vec(&mut env, &active_ranges)?;
        let completed_values = jlong_array_to_vec(&mut env, &completed_ranges)?;
        let active_ranges = decode_prefetch_ranges(&active_values)?;
        let completed_ranges = decode_prefetch_ranges(&completed_values)?;
        let plan = reconcile_prefetch_plan_for_viewport(
            handle as ComicHandle,
            page_index as usize,
            network_class_from_i32(network_class),
            forward_prefetch_window_from_i32(forward_prefetch_page_count),
            active_ranges,
            completed_ranges,
            byte_budget as u64,
        )?;
        encode_reconciled_prefetch_plan(ReconciledPrefetchPlanWire::Success(&plan))
    })();

    let values = match result {
        Ok(values) => values,
        Err(error) => {
            set_last_error(error);
            match encode_reconciled_prefetch_plan(ReconciledPrefetchPlanWire::Error) {
                Ok(values) => values,
                Err(error) => {
                    set_last_error(error);
                    return std::ptr::null_mut();
                }
            }
        }
    };
    match vec_to_jlong_array(&mut env, &values) {
        Ok(array) => array,
        Err(error) => {
            set_last_error(error);
            std::ptr::null_mut()
        }
    }
}

extern "system" fn native_last_error_message(env: JNIEnv<'_>, _class: JClass<'_>) -> jstring {
    let message = last_error_message_string();
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn jstring_to_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String> {
    Ok(env.get_string(value)?.into())
}

fn jlong_array_to_vec(env: &mut JNIEnv<'_>, values: &JLongArray<'_>) -> Result<Vec<i64>> {
    jlong_array_to_vec_with_limit(
        env,
        values,
        MAX_PREFETCH_WIRE_WORDS,
        "prefetch range payload",
    )
}

fn jlong_array_to_vec_with_limit(
    env: &mut JNIEnv<'_>,
    values: &JLongArray<'_>,
    maximum_words: usize,
    label: &str,
) -> Result<Vec<i64>> {
    if values.is_null() {
        return Err(ComicCoreError::InvalidZip(format!("{label} must not be null")).into());
    }
    let length = env.get_array_length(values)? as usize;
    if length > maximum_words {
        return Err(ComicCoreError::InvalidZip(format!("{label} is too large")).into());
    }
    let mut result = vec![0i64; length];
    env.get_long_array_region(values, 0, &mut result)?;
    Ok(result)
}

fn vec_to_jlong_array(env: &mut JNIEnv<'_>, values: &[i64]) -> Result<jlongArray> {
    let length = i32::try_from(values.len())?;
    let result = env.new_long_array(length)?;
    env.set_long_array_region(&result, 0, values)?;
    Ok(result.into_raw())
}

fn prefetch_result_status(result: Result<bool>) -> Result<jint> {
    match result {
        Ok(true) => Ok(1),
        Ok(false) => Ok(0),
        Err(error) if is_nonfatal_remote_shutdown(&error) => Ok(0),
        Err(error) => Err(error),
    }
}

fn is_nonfatal_remote_shutdown(error: &anyhow::Error) -> bool {
    matches!(
        error.downcast_ref::<RangeSessionError>(),
        Some(RangeSessionError::Cancelled | RangeSessionError::Closed),
    )
}

#[cfg(test)]
mod tests {
    use super::prefetch_result_status;
    use crate::remote::range_session::RangeSessionError;

    #[test]
    fn remote_prefetch_status_treats_shutdown_as_a_nonfatal_miss() {
        assert_eq!(0, prefetch_result_status(Ok(false)).unwrap());
        assert_eq!(1, prefetch_result_status(Ok(true)).unwrap());
        assert_eq!(
            0,
            prefetch_result_status(Err(RangeSessionError::Cancelled.into())).unwrap(),
        );
        assert_eq!(
            0,
            prefetch_result_status(Err(RangeSessionError::Closed.into())).unwrap(),
        );
    }

    #[test]
    fn remote_prefetch_status_preserves_real_failures() {
        assert!(
            prefetch_result_status(Err(RangeSessionError::Transport("boom".to_string()).into()))
                .is_err(),
        );
    }
}
