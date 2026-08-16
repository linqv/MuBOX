use anyhow::{Result, anyhow, bail};
use jni::objects::{GlobalRef, JObject, JValue};
use jni::sys::jlong;
use jni::{JNIEnv, JavaVM};

use super::range_session::RangeTransport;
use crate::scheduler::range_planner::ByteRange;

const RANGE_PROVIDER_FETCH_METHOD: &str = "fetchRangeIntoV1";
const RANGE_PROVIDER_FETCH_SIGNATURE: &str = "(JJJJLjava/nio/ByteBuffer;)I";
const RANGE_PROVIDER_CANCEL_METHOD: &str = "cancelRangeFetchV1";
const RANGE_PROVIDER_CANCEL_SIGNATURE: &str = "(JJ)V";

/// JNI transport for one registered Kotlin `RangeProvider`.
///
/// The native vector owns the direct buffer's storage. Kotlin's callback is
/// synchronous and must not retain the `ByteBuffer` after it returns.
pub(crate) struct JniRangeTransport {
    vm: JavaVM,
    file_id: u64,
    registry_class: GlobalRef,
}

impl JniRangeTransport {
    pub(crate) fn new(vm: JavaVM, file_id: u64, registry_class: GlobalRef) -> Self {
        Self {
            vm,
            file_id,
            registry_class,
        }
    }
}

impl RangeTransport for JniRangeTransport {
    type Error = anyhow::Error;

    fn fetch(&self, request_id: u64, range: ByteRange) -> Result<Vec<u8>> {
        let file_id = to_jlong(self.file_id, "range provider id")?;
        let request_id = to_jlong(request_id, "range request id")?;
        let start = to_jlong(range.start, "range start")?;
        let end_inclusive = to_jlong(range.end_inclusive, "range end")?;
        let byte_count = range
            .end_inclusive
            .checked_sub(range.start)
            .and_then(|difference| difference.checked_add(1))
            .ok_or_else(|| {
                anyhow!(
                    "invalid or overflowing range {}-{}",
                    range.start,
                    range.end_inclusive
                )
            })?;
        if byte_count > i32::MAX as u64 {
            bail!("range is too large for a Java ByteBuffer: {byte_count} bytes");
        }
        let byte_count = usize::try_from(byte_count)
            .map_err(|_| anyhow!("range length cannot be represented on this platform"))?;
        let mut bytes = Vec::new();
        bytes
            .try_reserve_exact(byte_count)
            .map_err(|_| anyhow!("could not allocate {byte_count} bytes for range response"))?;
        bytes.resize(byte_count, 0);

        let mut env = self.vm.attach_current_thread()?;
        let callback = env.with_local_frame(16, |env| -> Result<i32> {
            // SAFETY: `bytes` has non-zero, stable storage for this synchronous
            // callback. The Kotlin transport contract forbids retaining `target`.
            let target = unsafe {
                env.new_direct_byte_buffer(bytes.as_mut_ptr(), bytes.len())?
            };
            let target = JObject::from(target);
            let result = env.call_static_method(
                &self.registry_class,
                RANGE_PROVIDER_FETCH_METHOD,
                RANGE_PROVIDER_FETCH_SIGNATURE,
                &[
                    JValue::Long(file_id),
                    JValue::Long(request_id),
                    JValue::Long(start),
                    JValue::Long(end_inclusive),
                    JValue::Object(&target),
                ],
            );
            if let Some(detail) = take_pending_exception(env) {
                bail!(
                    "range callback {RANGE_PROVIDER_FETCH_METHOD} failed for file {} request {} bytes {}-{}: {detail}",
                    self.file_id,
                    request_id,
                    range.start,
                    range.end_inclusive,
                );
            }
            Ok(result?.i()?)
        });

        let written = match callback {
            Ok(written) => written,
            Err(error) => {
                if let Some(detail) = take_pending_exception(&mut env) {
                    return Err(anyhow!(
                        "range callback {RANGE_PROVIDER_FETCH_METHOD} failed for file {} request {} bytes {}-{}: {detail}",
                        self.file_id,
                        request_id,
                        range.start,
                        range.end_inclusive,
                    ));
                }
                return Err(error);
            }
        };
        let written = usize::try_from(written)
            .map_err(|_| anyhow!("range callback returned a negative byte count: {written}"))?;
        if written != byte_count {
            bail!(
                "range callback returned {written} bytes for {}-{}, expected {byte_count}",
                range.start,
                range.end_inclusive,
            );
        }
        Ok(bytes)
    }

    fn cancel(&self, request_id: u64) {
        let (Ok(file_id), Ok(request_id)) = (
            to_jlong(self.file_id, "range provider id"),
            to_jlong(request_id, "range request id"),
        ) else {
            return;
        };
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let result = env.with_local_frame(8, |env| -> Result<()> {
            let result = env.call_static_method(
                &self.registry_class,
                RANGE_PROVIDER_CANCEL_METHOD,
                RANGE_PROVIDER_CANCEL_SIGNATURE,
                &[JValue::Long(file_id), JValue::Long(request_id)],
            );
            if let Some(detail) = take_pending_exception(env) {
                bail!("range cancellation callback failed: {detail}");
            }
            result?.v()?;
            Ok(())
        });
        if result.is_err() {
            // Cancellation is best effort, but a Java exception must never leak
            // out of this callback and poison the caller's JNI frame.
            let _ = take_pending_exception(&mut env);
        }
    }
}

fn to_jlong(value: u64, label: &str) -> Result<jlong> {
    i64::try_from(value).map_err(|_| anyhow!("{label} is outside the JNI Long domain"))
}

/// Describes and clears a pending Java exception. Inspection failures are also
/// cleared so native callers never return with a surprise pending exception.
fn take_pending_exception(env: &mut JNIEnv<'_>) -> Option<String> {
    match env.exception_check() {
        Ok(true) => {}
        Ok(false) => return None,
        Err(error) => {
            let _ = env.exception_clear();
            return Some(format!("failed to check for a Java exception: {error}"));
        }
    }

    let throwable = env.exception_occurred();
    let _ = env.exception_clear();
    let detail = match throwable {
        Ok(throwable) => (|| -> Result<String> {
            let class = env.get_object_class(&throwable)?;
            let class_name = env.call_method(&class, "getName", "()Ljava/lang/String;", &[])?;
            let class_name: String = env.get_string(&class_name.l()?.into())?.into();
            let message = env
                .call_method(&throwable, "getMessage", "()Ljava/lang/String;", &[])?
                .l()?;
            let message = if message.is_null() {
                "<null>".to_string()
            } else {
                env.get_string(&message.into())?.into()
            };
            Ok(format!("{class_name}: {message}"))
        })()
        .unwrap_or_else(|error| format!("Java exception (detail unavailable: {error})")),
        Err(error) => format!("Java exception (throwable unavailable: {error})"),
    };
    match env.exception_check() {
        Ok(true) | Err(_) => {
            let _ = env.exception_clear();
        }
        Ok(false) => {}
    }
    Some(detail)
}
