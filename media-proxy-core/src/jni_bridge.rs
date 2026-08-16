use crate::engine::{MediaStream, ProxyConfig, ProxyEngine};
use crate::error::ProxyError;
use crate::transport::JniNetworkBridge;
use jni::objects::{JObject, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong, jstring, JNI_ERR, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM, NativeMethod};
use std::cell::RefCell;
use std::collections::HashMap;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

const NATIVE_CLASS: &str = "org/mubox/reader/video/proxy/MediaProxyNative";
static NEXT_PROXY_HANDLE: AtomicU64 = AtomicU64::new(1);
static PROXIES: OnceLock<Mutex<HashMap<u64, Arc<ProxyEngine>>>> = OnceLock::new();
static STREAMS: OnceLock<Mutex<HashMap<u64, Arc<MediaStream>>>> = OnceLock::new();

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

/// Registers the versioned JNI ABI when the standalone library is loaded.
///
/// # Safety
///
/// `vm` must be the live JVM pointer supplied by the VM for this library load.
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    // SAFETY: required by this function's contract; the JVM owns and validates the pointer.
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

fn register_natives(env: &mut JNIEnv<'_>) -> Result<(), ProxyError> {
    let class = env.find_class(NATIVE_CLASS)?;
    let methods = [
        native_method(
            "proxyCreateV1",
            "(JIIIIII)J",
            native_proxy_create_v1 as *const () as *mut c_void,
        ),
        native_method(
            "proxyStartV1",
            "(J)I",
            native_proxy_start_v1 as *const () as *mut c_void,
        ),
        native_method(
            "proxyCloseV1",
            "(J)V",
            native_proxy_close_v1 as *const () as *mut c_void,
        ),
        native_method(
            "streamCreateV1",
            "(JLorg/mubox/reader/video/proxy/MediaProxyNetworkBridge;Ljava/lang/String;JLjava/lang/String;ZI)J",
            native_stream_create_v1 as *const () as *mut c_void,
        ),
        native_method(
            "streamCloseV1",
            "(J)Z",
            native_stream_close_v1 as *const () as *mut c_void,
        ),
        native_method(
            "streamStatsV1",
            "(J)Ljava/lang/String;",
            native_stream_stats_v1 as *const () as *mut c_void,
        ),
        native_method(
            "lastErrorMessageV1",
            "()Ljava/lang/String;",
            native_last_error_message_v1 as *const () as *mut c_void,
        ),
    ];
    env.register_native_methods(class, &methods)?;
    Ok(())
}

fn native_method(name: &str, signature: &str, pointer: *mut c_void) -> NativeMethod {
    NativeMethod {
        name: JNIString::from(name),
        sig: JNIString::from(signature),
        fn_ptr: pointer,
    }
}

extern "system" fn native_proxy_create_v1(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    cache_bytes: jlong,
    port_start: jint,
    port_end: jint,
    header_timeout: jint,
    max_header_bytes: jint,
    max_requests: jint,
    max_connections: jint,
) -> jlong {
    let result = (|| -> Result<u64, ProxyError> {
        let cache_bytes = non_negative_u64(cache_bytes, "cacheBytes")?;
        let port_start = port_value(port_start, "portStart")?;
        let port_end = port_value(port_end, "portEnd")?;
        let header_timeout = positive_usize(header_timeout, "headerTimeout")?;
        let max_header_bytes = positive_usize(max_header_bytes, "maxHeaderBytes")?;
        let max_requests = positive_usize(max_requests, "maxRequests")?;
        let max_connections = positive_usize(max_connections, "maxConnections")?;
        let engine = ProxyEngine::new(ProxyConfig {
            cache_bytes,
            port_start,
            port_end,
            header_timeout: Duration::from_millis(header_timeout as u64),
            max_header_bytes,
            max_requests_per_connection: max_requests,
            max_connections,
        })?;
        let handle = next_proxy_handle()?;
        proxies()
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .insert(handle, engine);
        Ok(handle)
    })();
    match result {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_proxy_start_v1(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
) -> jint {
    match proxy_for_handle(handle).and_then(|proxy| proxy.start()) {
        Ok(port) => i32::from(port),
        Err(error) => {
            set_last_error(error);
            -1
        }
    }
}

extern "system" fn native_proxy_close_v1(_env: JNIEnv<'_>, _receiver: JObject<'_>, handle: jlong) {
    let Ok(handle) = positive_handle(handle, "proxy handle") else {
        return;
    };
    let proxy = proxies()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .remove(&handle);
    if let Some(proxy) = proxy {
        let removed_streams = {
            let mut streams = streams()
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            let handles = streams
                .iter()
                .filter(|(_, stream)| stream.belongs_to(&proxy))
                .map(|(handle, _)| *handle)
                .collect::<Vec<_>>();
            handles
                .into_iter()
                .filter_map(|handle| streams.remove(&handle))
                .collect::<Vec<_>>()
        };
        proxy.close();
        drop(removed_streams);
    }
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_stream_create_v1(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    proxy_handle: jlong,
    bridge: JObject<'_>,
    route_token: JString<'_>,
    size: jlong,
    mime: JString<'_>,
    seek_enabled: jboolean,
    forward_prefetch_chunks: jint,
) -> jlong {
    let result = (|| -> Result<u64, ProxyError> {
        if bridge.is_null() {
            return Err(ProxyError::InvalidArgument(
                "network bridge must not be null".to_string(),
            ));
        }
        let proxy = proxy_for_handle(proxy_handle)?;
        let route_token = jstring_to_string(&mut env, &route_token, "routeToken")?;
        let mime = jstring_to_string(&mut env, &mime, "mime")?;
        let size = optional_size(size)?;
        if forward_prefetch_chunks < 0 {
            return Err(ProxyError::InvalidArgument(
                "forwardPrefetchChunks must not be negative".to_string(),
            ));
        }
        let vm = env.get_java_vm()?;
        let bridge = env.new_global_ref(bridge)?;
        let bridge = Arc::new(JniNetworkBridge::new(vm, bridge));
        let stream = proxy.register_stream(
            bridge,
            route_token,
            size,
            mime,
            seek_enabled != JNI_FALSE,
            forward_prefetch_chunks as usize,
        )?;
        let handle = stream.id;
        streams()
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .insert(handle, stream);
        Ok(handle)
    })();
    match result {
        Ok(handle) => handle as jlong,
        Err(error) => {
            set_last_error(error);
            0
        }
    }
}

extern "system" fn native_stream_close_v1(
    _env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
) -> jboolean {
    let Ok(handle) = positive_handle(handle, "stream handle") else {
        return JNI_FALSE;
    };
    let stream = streams()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .remove(&handle);
    match stream {
        Some(stream) if stream.close() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

extern "system" fn native_stream_stats_v1(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    handle: jlong,
) -> jstring {
    let result = positive_handle(handle, "stream handle").and_then(|handle| {
        streams()
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&handle)
            .cloned()
            .ok_or(ProxyError::NotFound)
    });
    match result.and_then(|stream| {
        env.new_string(stream.stats_json())
            .map(|value| value.into_raw())
            .map_err(ProxyError::from)
    }) {
        Ok(value) => value,
        Err(error) => {
            set_last_error(error);
            std::ptr::null_mut()
        }
    }
}

extern "system" fn native_last_error_message_v1(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    let message = LAST_ERROR.with(|last_error| last_error.borrow().clone());
    env.new_string(message)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn proxies() -> &'static Mutex<HashMap<u64, Arc<ProxyEngine>>> {
    PROXIES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn streams() -> &'static Mutex<HashMap<u64, Arc<MediaStream>>> {
    STREAMS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn proxy_for_handle(handle: jlong) -> Result<Arc<ProxyEngine>, ProxyError> {
    let handle = positive_handle(handle, "proxy handle")?;
    proxies()
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .get(&handle)
        .cloned()
        .ok_or(ProxyError::NotFound)
}

fn next_proxy_handle() -> Result<u64, ProxyError> {
    let handle = NEXT_PROXY_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle == 0 || handle > i64::MAX as u64 {
        Err(ProxyError::InvalidArgument(
            "proxy handle space exhausted".to_string(),
        ))
    } else {
        Ok(handle)
    }
}

fn positive_handle(value: jlong, label: &str) -> Result<u64, ProxyError> {
    if value <= 0 {
        Err(ProxyError::InvalidArgument(format!(
            "{label} must be positive"
        )))
    } else {
        Ok(value as u64)
    }
}

fn non_negative_u64(value: jlong, label: &str) -> Result<u64, ProxyError> {
    if value < 0 {
        Err(ProxyError::InvalidArgument(format!(
            "{label} must not be negative"
        )))
    } else {
        Ok(value as u64)
    }
}

fn optional_size(size: jlong) -> Result<Option<u64>, ProxyError> {
    match size {
        -1 => Ok(None),
        size if size >= 0 => Ok(Some(size as u64)),
        _ => Err(ProxyError::InvalidArgument(
            "size must be -1 or non-negative".to_string(),
        )),
    }
}

fn port_value(value: jint, label: &str) -> Result<u16, ProxyError> {
    u16::try_from(value)
        .map_err(|_| ProxyError::InvalidArgument(format!("{label} must be between 0 and 65535")))
}

fn positive_usize(value: jint, label: &str) -> Result<usize, ProxyError> {
    if value <= 0 {
        return Err(ProxyError::InvalidArgument(format!(
            "{label} must be positive"
        )));
    }
    Ok(value as usize)
}

fn jstring_to_string(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    label: &str,
) -> Result<String, ProxyError> {
    if value.is_null() {
        return Err(ProxyError::InvalidArgument(format!(
            "{label} must not be null"
        )));
    }
    Ok(env.get_string(value)?.into())
}

fn set_last_error(error: impl std::fmt::Display) {
    let message = error.to_string().replace('\0', "\\0");
    LAST_ERROR.with(|last_error| *last_error.borrow_mut() = message);
}

#[cfg(test)]
mod tests {
    use super::{optional_size, port_value, positive_handle};

    #[test]
    fn validates_handle_and_size_domains() {
        assert!(positive_handle(0, "handle").is_err());
        assert_eq!(positive_handle(7, "handle").unwrap(), 7);
        assert_eq!(optional_size(-1).unwrap(), None);
        assert_eq!(optional_size(0).unwrap(), Some(0));
        assert!(optional_size(-2).is_err());
        assert_eq!(port_value(65535, "port").unwrap(), 65535);
        assert!(port_value(65536, "port").is_err());
    }
}
