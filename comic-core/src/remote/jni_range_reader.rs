use anyhow::{anyhow, Result};
use jni::objects::{JByteArray, JValue};
use jni::sys::jlong;
use jni::JavaVM;

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
        let mut env = self.vm.attach_current_thread()?;
        let class = env.find_class("com/example/comicdav/nativebridge/RangeProviderRegistry")?;
        let result = env.call_static_method(
            class,
            "readRange",
            "(JJJ)[B",
            &[
                JValue::Long(self.file_id as jlong),
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
                        let msg_jobj = env.call_method(&throwable, "getMessage", "()Ljava/lang/String;", &[])?.l()?;
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
                "range callback failed for file {} bytes {}-{}: {}",
                self.file_id,
                start,
                end_inclusive,
                detail
            ));
        }
        let bytes = result?.l()?;
        let bytes = JByteArray::from(bytes);
        Ok(env.convert_byte_array(&bytes)?)
    }
}
