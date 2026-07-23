use anyhow::{Result, anyhow};
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::Path;

use super::{last_error_message_ptr, set_last_error};
use crate::image::ImageFormatOptions;
use crate::session_registry::{
    ComicHandle, close_session, load_page_to_file, open_local_path, page_count,
};

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
    close_session(handle);
}

/// Returns a pointer to the current thread's last error CString.
/// The pointer is only valid on the same thread and only until the next native error is set.
#[unsafe(no_mangle)]
pub extern "C" fn comic_last_error_message() -> *const c_char {
    last_error_message_ptr()
}

fn read_c_string(value: *const c_char) -> Result<String> {
    if value.is_null() {
        return Err(anyhow!("null string pointer"));
    }
    // SAFETY: the C API requires a non-null, NUL-terminated string for this parameter.
    let value = unsafe { CStr::from_ptr(value) };
    Ok(value.to_string_lossy().into_owned())
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
