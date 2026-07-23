use anyhow::Result;
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use jni::sys::{JNI_ERR, JNI_VERSION_1_6, jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM, NativeMethod};
use std::os::raw::c_void;
use std::path::{Path, PathBuf};

use super::{
    DiagnosticsWire, PlannedRangesWire, encode_diagnostics, encode_planned_ranges,
    last_error_message_string, set_last_error,
};
use crate::error::ComicCoreError;
use crate::image::ImageFormatOptions;
use crate::remote::jni_range_reader::JniRangeReader;
use crate::session_registry::{
    ComicHandle, archive_format_from_name, close_session, forward_prefetch_window_from_i32,
    load_page_to_file, network_class_from_i32, open_local_fd, open_local_path, open_remote_reader,
    page_count, planned_ranges_for_viewport, session_diagnostics, update_viewport,
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
        open_local_fd(fd, size_hint, format, options)
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

fn jstring_to_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String> {
    Ok(env.get_string(value)?.into())
}
