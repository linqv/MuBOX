use crate::error::ProxyError;
use crate::NATIVE_CHUNK_BYTES;
use jni::objects::{GlobalRef, JLongArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FetchMode {
    Range = 0,
    Full = 1,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FetchMetadata {
    pub status: i32,
    pub content_length: Option<u64>,
    pub content_range_start: Option<u64>,
    pub content_range_end: Option<u64>,
    pub total_size: Option<u64>,
}

pub trait NetworkBridge: Send + Sync + 'static {
    fn head(&self) -> Result<Option<u64>, ProxyError>;

    fn open_fetch(
        &self,
        request_id: u64,
        start: Option<u64>,
        end_inclusive: Option<u64>,
        mode: FetchMode,
    ) -> Result<FetchMetadata, ProxyError>;

    /// Returns `Ok(None)` at EOF. Implementations must never write more than `target.len()` bytes.
    fn read_fetch_into(
        &self,
        request_id: u64,
        target: &mut [u8],
    ) -> Result<Option<usize>, ProxyError>;

    fn cancel_fetch(&self, request_id: u64);
    fn close_fetch(&self, request_id: u64);
}

pub struct JniNetworkBridge {
    vm: JavaVM,
    bridge: GlobalRef,
}

impl JniNetworkBridge {
    pub fn new(vm: JavaVM, bridge: GlobalRef) -> Self {
        Self { vm, bridge }
    }

    fn call_void(&self, method: &str, request_id: u64) {
        let Ok(request_id) = i64::try_from(request_id) else {
            return;
        };
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let result = env.call_method(
            self.bridge.as_obj(),
            method,
            "(J)V",
            &[JValue::Long(request_id)],
        );
        if result.is_err() || matches!(env.exception_check(), Ok(true)) {
            let _ = env.exception_clear();
        }
    }
}

impl NetworkBridge for JniNetworkBridge {
    fn head(&self) -> Result<Option<u64>, ProxyError> {
        let mut env = self.vm.attach_current_thread()?;
        let object = call_object_checked(&mut env, self.bridge.as_obj(), "headV1", "()[J", &[])?;
        let array = JLongArray::from(object);
        let values = read_long_array(&mut env, &array, 2, "headV1")?;
        optional_u64(values[0], "head size")
    }

    fn open_fetch(
        &self,
        request_id: u64,
        start: Option<u64>,
        end_inclusive: Option<u64>,
        mode: FetchMode,
    ) -> Result<FetchMetadata, ProxyError> {
        let request_id = to_jlong(request_id, "request id")?;
        let start = optional_jlong(start, "range start")?;
        let end_inclusive = optional_jlong(end_inclusive, "range end")?;
        let mut env = self.vm.attach_current_thread()?;
        let values = [
            JValue::Long(request_id),
            JValue::Long(start),
            JValue::Long(end_inclusive),
            JValue::Int(mode as i32),
        ];
        let object = call_object_checked(
            &mut env,
            self.bridge.as_obj(),
            "openFetchV1",
            "(JJJI)[J",
            &values,
        )?;
        let array = JLongArray::from(object);
        let values = read_long_array(&mut env, &array, 5, "openFetchV1")?;
        let status = i32::try_from(values[0]).map_err(|_| {
            ProxyError::Transport("openFetchV1 status is outside the Int domain".to_string())
        })?;
        Ok(FetchMetadata {
            status,
            content_length: optional_u64(values[1], "content length")?,
            content_range_start: optional_u64(values[2], "content range start")?,
            content_range_end: optional_u64(values[3], "content range end")?,
            total_size: optional_u64(values[4], "total size")?,
        })
    }

    fn read_fetch_into(
        &self,
        request_id: u64,
        target: &mut [u8],
    ) -> Result<Option<usize>, ProxyError> {
        if target.is_empty() || target.len() > NATIVE_CHUNK_BYTES {
            return Err(ProxyError::InvalidArgument(format!(
                "native read chunk must be between 1 and {NATIVE_CHUNK_BYTES} bytes"
            )));
        }
        let request_id = to_jlong(request_id, "request id")?;
        let mut env = self.vm.attach_current_thread()?;
        // SAFETY: `target` is live, uniquely borrowed, non-empty, and stable for the complete
        // synchronous Java callback. The bridge contract forbids retaining the ByteBuffer.
        let buffer = unsafe { env.new_direct_byte_buffer(target.as_mut_ptr(), target.len())? };
        let buffer = JObject::from(buffer);
        let result = env.call_method(
            self.bridge.as_obj(),
            "readFetchIntoV1",
            "(JLjava/nio/ByteBuffer;)I",
            &[JValue::Long(request_id), JValue::Object(&buffer)],
        );
        if let Some(exception) = take_pending_exception(&mut env) {
            return Err(ProxyError::Transport(format!(
                "readFetchIntoV1 threw {exception}"
            )));
        }
        let written = result?.i()?;
        if written == -1 {
            return Ok(None);
        }
        let written = usize::try_from(written).map_err(|_| {
            ProxyError::Transport(format!(
                "readFetchIntoV1 returned negative byte count {written}"
            ))
        })?;
        if written > target.len() {
            return Err(ProxyError::Transport(format!(
                "readFetchIntoV1 returned {written} bytes for {} byte target",
                target.len()
            )));
        }
        Ok(Some(written))
    }

    fn cancel_fetch(&self, request_id: u64) {
        self.call_void("cancelFetchV1", request_id);
    }

    fn close_fetch(&self, request_id: u64) {
        self.call_void("closeFetchV1", request_id);
    }
}

fn call_object_checked<'local, 'object_ref, 'value>(
    env: &mut JNIEnv<'local>,
    object: &JObject<'object_ref>,
    method: &str,
    signature: &str,
    arguments: &[JValue<'local, 'value>],
) -> Result<JObject<'local>, ProxyError> {
    let result = env.call_method(object, method, signature, arguments);
    if let Some(exception) = take_pending_exception(env) {
        return Err(ProxyError::Transport(format!("{method} threw {exception}")));
    }
    let object = result?.l()?;
    if object.is_null() {
        return Err(ProxyError::Transport(format!("{method} returned null")));
    }
    Ok(object)
}

fn read_long_array(
    env: &mut JNIEnv<'_>,
    array: &JLongArray<'_>,
    expected: usize,
    method: &str,
) -> Result<Vec<i64>, ProxyError> {
    let length = usize::try_from(env.get_array_length(array)?)
        .map_err(|_| ProxyError::Transport(format!("{method} returned an invalid array length")))?;
    if length != expected {
        return Err(ProxyError::Transport(format!(
            "{method} returned {length} longs, expected {expected}"
        )));
    }
    let mut values = vec![0; length];
    env.get_long_array_region(array, 0, &mut values)?;
    Ok(values)
}

fn optional_u64(value: i64, label: &str) -> Result<Option<u64>, ProxyError> {
    match value {
        -1 => Ok(None),
        value if value >= 0 => Ok(Some(value as u64)),
        _ => Err(ProxyError::Transport(format!(
            "{label} must be -1 or non-negative, got {value}"
        ))),
    }
}

fn optional_jlong(value: Option<u64>, label: &str) -> Result<i64, ProxyError> {
    value.map_or(Ok(-1), |value| to_jlong(value, label))
}

fn to_jlong(value: u64, label: &str) -> Result<i64, ProxyError> {
    i64::try_from(value).map_err(|_| {
        ProxyError::InvalidArgument(format!("{label} is outside the Java Long domain"))
    })
}

fn take_pending_exception(env: &mut JNIEnv<'_>) -> Option<String> {
    match env.exception_check() {
        Ok(false) => return None,
        Ok(true) => {}
        Err(error) => {
            let _ = env.exception_clear();
            return Some(format!("exception check failed: {error}"));
        }
    }
    let throwable = env.exception_occurred();
    let _ = env.exception_clear();
    let detail = throwable
        .and_then(|throwable| {
            let value = env.call_method(throwable, "toString", "()Ljava/lang/String;", &[])?;
            let value = value.l()?;
            Ok(if value.is_null() {
                "Java exception".to_string()
            } else {
                env.get_string(&value.into())?.into()
            })
        })
        .unwrap_or_else(|error| format!("Java exception ({error})"));
    if matches!(env.exception_check(), Ok(true)) {
        let _ = env.exception_clear();
    }
    Some(detail)
}
