use anyhow::Result;
use flate2::read::DeflateDecoder;
use std::io::Read;

use crate::cbz::index::CbzIndex;
use crate::error::ComicCoreError;
use crate::zip::local_header::{
    relative_data_offset, LOCAL_HEADER_MIN_SIZE, MAX_LOCAL_HEADER_EXTRA_LEN,
};
use crate::zip::RangeReader;

const LOCAL_HEADER_OPTIMISTIC_EXTRA_LEN: u64 = 4 * 1024;

impl CbzIndex {
    pub fn extract_page(
        &mut self,
        reader: &impl RangeReader,
        page_index: usize,
    ) -> Result<ExtractedPage> {
        let page = self
            .pages
            .get_mut(page_index)
            .ok_or_else(|| ComicCoreError::InvalidZip("page index out of bounds".to_string()))?;
        let compressed_size = page.compressed_size;
        let uncompressed_size = page.uncompressed_size;
        let compression_method = page.compression_method;
        let mut data_offset_updated = false;
        let compressed = match page.data_offset {
            Some(offset) => {
                let end = offset + compressed_size - 1;
                match reader.read_cached_range(offset, end)? {
                    Some(cached) => cached,
                    None => reader.read_range(offset, end)?,
                }
            }
            None => {
                let (compressed, data_offset) = read_page_with_local_header(reader, page)?;
                page.data_offset = Some(data_offset);
                data_offset_updated = true;
                compressed
            }
        };

        let bytes = match compression_method {
            0 => compressed,
            8 => {
                let mut decoder = DeflateDecoder::new(compressed.as_slice());
                let mut output = Vec::with_capacity(uncompressed_size as usize);
                decoder.read_to_end(&mut output)?;
                output
            }
            method => return Err(ComicCoreError::UnsupportedCompression(method).into()),
        };
        Ok(ExtractedPage {
            bytes,
            data_offset_updated,
        })
    }
}

pub struct ExtractedPage {
    pub bytes: Vec<u8>,
    pub data_offset_updated: bool,
}

fn read_page_with_local_header(
    reader: &impl RangeReader,
    page: &crate::cbz::index::CbzPageEntry,
) -> Result<(Vec<u8>, u64)> {
    let optimistic_header_len =
        LOCAL_HEADER_MIN_SIZE + page.filename_len as u64 + LOCAL_HEADER_OPTIMISTIC_EXTRA_LEN;
    let range_len = optimistic_header_len + page.compressed_size;
    let file_size = reader.size()?;
    let end = page
        .local_header_offset
        .checked_add(range_len)
        .and_then(|value| value.checked_sub(1))
        .map(|value| value.min(file_size.saturating_sub(1)))
        .ok_or_else(|| ComicCoreError::InvalidZip("page range overflow".to_string()))?;
    let bytes = reader.read_range(page.local_header_offset, end)?;
    let data_start = relative_data_offset(&bytes)? as usize;
    let data_offset = page.local_header_offset + data_start as u64;
    let data_end = data_start
        .checked_add(page.compressed_size as usize)
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    if let Some(compressed) = bytes.get(data_start..data_end) {
        return Ok((compressed.to_vec(), data_offset));
    }

    if data_start as u64
        > LOCAL_HEADER_MIN_SIZE + page.filename_len as u64 + MAX_LOCAL_HEADER_EXTRA_LEN
    {
        return Err(ComicCoreError::InvalidZip("page data out of bounds".to_string()).into());
    }
    let data_end = data_offset
        .checked_add(page.compressed_size)
        .and_then(|value| value.checked_sub(1))
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    let compressed = match reader.read_cached_range(data_offset, data_end)? {
        Some(cached) => cached,
        None => reader.read_range(data_offset, data_end)?,
    };
    Ok((compressed, data_offset))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cbz::open_cbz;
    use std::cell::RefCell;
    use std::io::{Cursor, Write};
    use zip::write::SimpleFileOptions;
    use zip::{CompressionMethod, ZipWriter};

    struct LoggingReader {
        bytes: Vec<u8>,
        reads: RefCell<Vec<(u64, u64)>>,
    }

    impl LoggingReader {
        fn new(bytes: Vec<u8>) -> Self {
            Self {
                bytes,
                reads: RefCell::new(Vec::new()),
            }
        }

        fn clear_reads(&self) {
            self.reads.borrow_mut().clear();
        }

        fn reads(&self) -> Vec<(u64, u64)> {
            self.reads.borrow().clone()
        }
    }

    impl RangeReader for LoggingReader {
        fn size(&self) -> Result<u64> {
            Ok(self.bytes.len() as u64)
        }

        fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
            self.reads.borrow_mut().push((start, end_inclusive));
            Ok(self.bytes[start as usize..=end_inclusive as usize].to_vec())
        }
    }

    struct CacheFirstReader {
        bytes: Vec<u8>,
        cached_ranges: RefCell<Vec<(u64, u64)>>,
        cache_reads: RefCell<Vec<(u64, u64)>>,
        network_reads: RefCell<Vec<(u64, u64)>>,
    }

    impl CacheFirstReader {
        fn new(bytes: Vec<u8>, cached_ranges: Vec<(u64, u64)>) -> Self {
            Self {
                bytes,
                cached_ranges: RefCell::new(cached_ranges),
                cache_reads: RefCell::new(Vec::new()),
                network_reads: RefCell::new(Vec::new()),
            }
        }

        fn add_cached_range(&self, start: u64, end_inclusive: u64) {
            self.cached_ranges.borrow_mut().push((start, end_inclusive));
        }

        fn clear_reads(&self) {
            self.cache_reads.borrow_mut().clear();
            self.network_reads.borrow_mut().clear();
        }

        fn cache_reads(&self) -> Vec<(u64, u64)> {
            self.cache_reads.borrow().clone()
        }

        fn network_reads(&self) -> Vec<(u64, u64)> {
            self.network_reads.borrow().clone()
        }

        fn slice(&self, start: u64, end_inclusive: u64) -> Vec<u8> {
            self.bytes[start as usize..=end_inclusive as usize].to_vec()
        }
    }

    impl RangeReader for CacheFirstReader {
        fn size(&self) -> Result<u64> {
            Ok(self.bytes.len() as u64)
        }

        fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
            self.network_reads.borrow_mut().push((start, end_inclusive));
            Ok(self.slice(start, end_inclusive))
        }

        fn read_cached_range(&self, start: u64, end_inclusive: u64) -> Result<Option<Vec<u8>>> {
            self.cache_reads.borrow_mut().push((start, end_inclusive));
            let is_cached = self
                .cached_ranges
                .borrow()
                .iter()
                .any(|(cached_start, cached_end)| start >= *cached_start && end_inclusive <= *cached_end);
            Ok(is_cached.then(|| self.slice(start, end_inclusive)))
        }
    }

    #[test]
    fn extract_page_caches_data_offset_after_local_header_parse() {
        for compression in [CompressionMethod::Stored, CompressionMethod::Deflated] {
            let reader = LoggingReader::new(make_zip(compression));
            let mut index = open_cbz(&reader).unwrap();
            assert_eq!(None, index.pages[0].data_offset);

            reader.clear_reads();
            let first = index.extract_page(&reader, 0).unwrap();
            let data_offset = index.pages[0]
                .data_offset
                .expect("first extraction should cache data_offset");
            assert_eq!(b"image-bytes".to_vec(), first.bytes);
            assert!(first.data_offset_updated);
            assert!(
                reader
                    .reads()
                    .iter()
                    .any(|(start, _)| *start == index.pages[0].local_header_offset),
                "first extraction should read through the local header"
            );

            reader.clear_reads();
            let second = index.extract_page(&reader, 0).unwrap();
            assert_eq!(b"image-bytes".to_vec(), second.bytes);
            assert!(!second.data_offset_updated);
            assert_eq!(
                vec![(
                    data_offset,
                    data_offset + index.pages[0].compressed_size - 1
                )],
                reader.reads(),
                "second extraction should read compressed page bytes from cached data_offset"
            );
        }
    }

    #[test]
    fn extract_page_reads_cached_compressed_bytes_before_network() {
        let reader = CacheFirstReader::new(make_zip(CompressionMethod::Stored), empty_ranges());
        let mut index = open_cbz(&reader).unwrap();

        let first = index.extract_page(&reader, 0).unwrap();
        let data_offset = index.pages[0].data_offset.unwrap();
        let data_end = data_offset + index.pages[0].compressed_size - 1;
        assert_eq!(b"image-bytes".to_vec(), first.bytes);

        reader.add_cached_range(data_offset, data_end);
        reader.clear_reads();

        let second = index.extract_page(&reader, 0).unwrap();

        assert_eq!(b"image-bytes".to_vec(), second.bytes);
        assert!(!second.data_offset_updated);
        assert_eq!(vec![(data_offset, data_end)], reader.cache_reads());
        assert_eq!(empty_ranges(), reader.network_reads());
    }

    fn empty_ranges() -> Vec<(u64, u64)> {
        Vec::new()
    }

    fn make_zip(compression: CompressionMethod) -> Vec<u8> {
        let cursor = Cursor::new(Vec::new());
        let mut zip = ZipWriter::new(cursor);
        let options = SimpleFileOptions::default().compression_method(compression);
        zip.start_file("001.jpg", options).unwrap();
        zip.write_all(b"image-bytes").unwrap();
        zip.finish().unwrap().into_inner()
    }
}
