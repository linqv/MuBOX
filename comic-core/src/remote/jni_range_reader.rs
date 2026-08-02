use anyhow::{Result, anyhow};
use jni::JavaVM;
use jni::objects::{JByteArray, JValue};
use jni::sys::jlong;

use crate::zip::RangeReader;

pub struct JniRangeReader {
    vm: JavaVM,
    file_id: u64,
    size: u64,
}

impl JniRangeReader {
    pub fn new(vm: JavaVM, file_id: u64, size: u64) -> Self {
        Self { vm, file_id, size }
    }
}

impl RangeReader for JniRangeReader {
    fn size(&self) -> Result<u64> {
        Ok(self.size)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        let bytes = call_static_byte_array_method(
            &self.vm,
            self.file_id,
            "readRange",
            start,
            end_inclusive,
        )?;
        bytes.ok_or_else(|| {
            anyhow!(
                "range callback returned null for file {} bytes {}-{}",
                self.file_id,
                start,
                end_inclusive
            )
        })
    }

    fn read_cached_range(&self, start: u64, end_inclusive: u64) -> Result<Option<Vec<u8>>> {
        call_static_byte_array_method(
            &self.vm,
            self.file_id,
            "readCachedRange",
            start,
            end_inclusive,
        )
    }
}

fn call_static_byte_array_method(
    vm: &JavaVM,
    file_id: u64,
    method_name: &str,
    start: u64,
    end_inclusive: u64,
) -> Result<Option<Vec<u8>>> {
    let mut env = vm.attach_current_thread()?;
    let class = env.find_class("org/mubox/reader/nativebridge/RangeProviderRegistry")?;
    let result = env.call_static_method(
        class,
        method_name,
        "(JJJ)[B",
        &[
            JValue::Long(file_id as jlong),
            JValue::Long(start as jlong),
            JValue::Long(end_inclusive as jlong),
        ],
    );
    if env.exception_check()? {
        let detail = match env.exception_occurred() {
            Ok(throwable) => {
                env.exception_clear()?;
                match (|| -> Result<String> {
                    let cls = env.get_object_class(&throwable)?;
                    let cls_obj = env.call_method(&cls, "getName", "()Ljava/lang/String;", &[])?;
                    let cls_name: String = env.get_string(&cls_obj.l()?.into())?.into();
                    let msg_jobj = env
                        .call_method(&throwable, "getMessage", "()Ljava/lang/String;", &[])?
                        .l()?;
                    let msg: String = if msg_jobj.is_null() {
                        "<null>".to_string()
                    } else {
                        env.get_string(&msg_jobj.into())?.into()
                    };
                    Ok(format!("{}: {}", cls_name, msg))
                })() {
                    Ok(info) => info,
                    Err(e) => format!("Java exception; failed to inspect Java exception: {}", e),
                }
            }
            Err(e) => {
                let _ = env.exception_clear();
                format!("Java exception; failed to inspect Java exception: {}", e)
            }
        };
        return Err(anyhow!(
            "range callback {} failed for file {} bytes {}-{}: {}",
            method_name,
            file_id,
            start,
            end_inclusive,
            detail
        ));
    }
    let bytes = result?.l()?;
    if bytes.is_null() {
        return Ok(None);
    }
    let bytes = JByteArray::from(bytes);
    Ok(Some(env.convert_byte_array(&bytes)?))
}
