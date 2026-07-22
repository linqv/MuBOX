use anyhow::Result;
use crc32fast::Hasher;
use flate2::read::DeflateDecoder;
use std::io::Read;

use crate::cbz::index::CbzIndex;
use crate::error::ComicCoreError;
use crate::zip::RangeReader;
use crate::zip::local_header::{
    LOCAL_HEADER_MIN_SIZE, MAX_LOCAL_HEADER_EXTRA_LEN, relative_data_offset,
};

const LOCAL_HEADER_OPTIMISTIC_EXTRA_LEN: u64 = 4 * 1024;
const MAX_PAGE_UNCOMPRESSED_SIZE: u64 = 128 * 1024 * 1024;
const MAX_PAGE_COMPRESSED_SIZE: u64 = MAX_PAGE_UNCOMPRESSED_SIZE + 1024 * 1024;
const DECOMPRESSION_BUFFER_SIZE: usize = 16 * 1024;
const INITIAL_OUTPUT_CAPACITY: usize = 64 * 1024;

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
        validate_declared_page_sizes(compressed_size, uncompressed_size)?;
        let mut data_offset_updated = false;
        let compressed = match page.data_offset {
            Some(offset) => read_compressed_page(reader, offset, compressed_size)?,
            None => {
                let (compressed, data_offset) = read_page_with_local_header(reader, page)?;
                page.data_offset = Some(data_offset);
                data_offset_updated = true;
                compressed
            }
        };

        let (bytes, actual_crc) = match compression_method {
            0 => {
                let bytes = validate_stored_page(compressed, uncompressed_size)?;
                let actual_crc = crc32fast::hash(&bytes);
                (bytes, actual_crc)
            }
            8 => decompress_deflated_page(&compressed, uncompressed_size)?,
            method => return Err(ComicCoreError::UnsupportedCompression(method).into()),
        };
        validate_page_crc(actual_crc, page.crc32)?;
        Ok(ExtractedPage {
            bytes,
            data_offset_updated,
        })
    }
}

#[derive(Debug)]
pub struct ExtractedPage {
    pub bytes: Vec<u8>,
    pub data_offset_updated: bool,
}

fn validate_declared_page_sizes(compressed_size: u64, uncompressed_size: u64) -> Result<()> {
    if compressed_size > MAX_PAGE_COMPRESSED_SIZE {
        return Err(ComicCoreError::InvalidZip(format!(
            "page compressed size {compressed_size} exceeds limit {MAX_PAGE_COMPRESSED_SIZE}"
        ))
        .into());
    }
    if uncompressed_size > MAX_PAGE_UNCOMPRESSED_SIZE {
        return Err(ComicCoreError::InvalidZip(format!(
            "page uncompressed size {uncompressed_size} exceeds limit {MAX_PAGE_UNCOMPRESSED_SIZE}"
        ))
        .into());
    }
    Ok(())
}

fn read_compressed_page(
    reader: &impl RangeReader,
    data_offset: u64,
    compressed_size: u64,
) -> Result<Vec<u8>> {
    if compressed_size == 0 {
        return Ok(Vec::new());
    }
    let end = data_offset
        .checked_add(compressed_size)
        .and_then(|value| value.checked_sub(1))
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    let compressed = match reader.read_cached_range(data_offset, end)? {
        Some(cached) => cached,
        None => reader.read_range(data_offset, end)?,
    };
    validate_compressed_size(&compressed, compressed_size)?;
    Ok(compressed)
}

fn validate_compressed_size(compressed: &[u8], expected_size: u64) -> Result<()> {
    let actual_size = compressed.len() as u64;
    if actual_size != expected_size {
        return Err(ComicCoreError::InvalidZip(format!(
            "page compressed size mismatch: expected {expected_size}, got {actual_size}"
        ))
        .into());
    }
    Ok(())
}

fn validate_stored_page(compressed: Vec<u8>, expected_size: u64) -> Result<Vec<u8>> {
    validate_actual_page_size(compressed.len() as u64, expected_size)?;
    Ok(compressed)
}

fn decompress_deflated_page(compressed: &[u8], expected_size: u64) -> Result<(Vec<u8>, u32)> {
    let expected_size = usize::try_from(expected_size).map_err(|_| {
        ComicCoreError::InvalidZip("page uncompressed size does not fit in memory".to_string())
    })?;
    let output_limit = expected_size
        .checked_add(1)
        .ok_or_else(|| ComicCoreError::InvalidZip("page uncompressed size overflow".to_string()))?;
    let mut decoder = DeflateDecoder::new(compressed);
    let mut output = Vec::with_capacity(expected_size.min(INITIAL_OUTPUT_CAPACITY));
    let mut buffer = [0u8; DECOMPRESSION_BUFFER_SIZE];
    let mut hasher = Hasher::new();

    while output.len() < output_limit {
        let remaining = output_limit - output.len();
        let read_len = remaining.min(buffer.len());
        let count = decoder.read(&mut buffer[..read_len])?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
        output.extend_from_slice(&buffer[..count]);
    }

    validate_actual_page_size(output.len() as u64, expected_size as u64)?;
    Ok((output, hasher.finalize()))
}

fn validate_actual_page_size(actual_size: u64, expected_size: u64) -> Result<()> {
    if actual_size != expected_size {
        return Err(ComicCoreError::InvalidZip(format!(
            "page uncompressed size mismatch: expected {expected_size}, got {actual_size}"
        ))
        .into());
    }
    Ok(())
}

fn validate_page_crc(actual_crc: u32, expected_crc: u32) -> Result<()> {
    if actual_crc != expected_crc {
        return Err(ComicCoreError::InvalidZip(format!(
            "page CRC mismatch: expected {expected_crc:08x}, got {actual_crc:08x}"
        ))
        .into());
    }
    Ok(())
}

fn read_page_with_local_header(
    reader: &impl RangeReader,
    page: &crate::cbz::index::CbzPageEntry,
) -> Result<(Vec<u8>, u64)> {
    let optimistic_header_len =
        LOCAL_HEADER_MIN_SIZE + page.filename_len as u64 + LOCAL_HEADER_OPTIMISTIC_EXTRA_LEN;
    let range_len = optimistic_header_len
        .checked_add(page.compressed_size)
        .ok_or_else(|| ComicCoreError::InvalidZip("page range overflow".to_string()))?;
    let file_size = reader.size()?;
    let end = page
        .local_header_offset
        .checked_add(range_len)
        .and_then(|value| value.checked_sub(1))
        .map(|value| value.min(file_size.saturating_sub(1)))
        .ok_or_else(|| ComicCoreError::InvalidZip("page range overflow".to_string()))?;
    let bytes = reader.read_range(page.local_header_offset, end)?;
    let data_start = relative_data_offset(&bytes)? as usize;
    let data_offset = page
        .local_header_offset
        .checked_add(data_start as u64)
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    let compressed_size = usize::try_from(page.compressed_size)
        .map_err(|_| ComicCoreError::InvalidZip("page data size overflow".to_string()))?;
    let data_end = data_start
        .checked_add(compressed_size)
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    if let Some(compressed) = bytes.get(data_start..data_end) {
        let compressed = compressed.to_vec();
        validate_compressed_size(&compressed, page.compressed_size)?;
        return Ok((compressed, data_offset));
    }

    if data_start as u64
        > LOCAL_HEADER_MIN_SIZE + page.filename_len as u64 + MAX_LOCAL_HEADER_EXTRA_LEN
    {
        return Err(ComicCoreError::InvalidZip("page data out of bounds".to_string()).into());
    }
    let compressed = read_compressed_page(reader, data_offset, page.compressed_size)?;
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
                .any(|(cached_start, cached_end)| {
                    start >= *cached_start && end_inclusive <= *cached_end
                });
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

    #[test]
    fn extract_page_rejects_declared_sizes_above_limit_before_reading_data() {
        let reader = LoggingReader::new(vec![0]);
        let mut index = CbzIndex {
            pages: vec![crate::cbz::CbzPageEntry {
                name: "001.jpg".to_string(),
                filename_len: 7,
                local_header_offset: 0,
                data_offset: Some(0),
                compressed_size: 1,
                uncompressed_size: MAX_PAGE_UNCOMPRESSED_SIZE + 1,
                compression_method: 0,
                crc32: crc32fast::hash(&[0]),
            }],
        };

        let error = index.extract_page(&reader, 0).unwrap_err().to_string();

        assert!(error.contains("exceeds limit"), "unexpected error: {error}");
        assert_eq!(empty_ranges(), reader.reads());
    }

    #[test]
    fn extract_page_rejects_oversized_compressed_data_before_reading_it() {
        let reader = LoggingReader::new(vec![0]);
        let mut index = CbzIndex {
            pages: vec![crate::cbz::CbzPageEntry {
                name: "001.jpg".to_string(),
                filename_len: 7,
                local_header_offset: 0,
                data_offset: Some(0),
                compressed_size: MAX_PAGE_COMPRESSED_SIZE + 1,
                uncompressed_size: 1,
                compression_method: 8,
                crc32: crc32fast::hash(&[0]),
            }],
        };

        let error = index.extract_page(&reader, 0).unwrap_err().to_string();

        assert!(error.contains("exceeds limit"), "unexpected error: {error}");
        assert_eq!(empty_ranges(), reader.reads());
    }

    #[test]
    fn extract_page_stops_when_deflate_output_exceeds_declared_size() {
        let payload = vec![7; 2 * 1024 * 1024];
        let reader = LoggingReader::new(make_zip_with_bytes(CompressionMethod::Deflated, &payload));
        let mut index = open_cbz(&reader).unwrap();
        index.pages[0].uncompressed_size = 1;

        let error = index.extract_page(&reader, 0).unwrap_err().to_string();

        assert!(
            error.contains("uncompressed size mismatch: expected 1, got 2"),
            "unexpected error: {error}"
        );
    }

    #[test]
    fn extract_page_rejects_short_deflate_output() {
        let reader = LoggingReader::new(make_zip(CompressionMethod::Deflated));
        let mut index = open_cbz(&reader).unwrap();
        index.pages[0].uncompressed_size += 1;

        let error = index.extract_page(&reader, 0).unwrap_err().to_string();

        assert!(
            error.contains("uncompressed size mismatch: expected 12, got 11"),
            "unexpected error: {error}"
        );
    }

    #[test]
    fn extract_page_rejects_stored_size_mismatch() {
        let reader = LoggingReader::new(make_zip(CompressionMethod::Stored));
        let mut index = open_cbz(&reader).unwrap();
        index.pages[0].uncompressed_size += 1;

        let error = index.extract_page(&reader, 0).unwrap_err().to_string();

        assert!(
            error.contains("uncompressed size mismatch: expected 12, got 11"),
            "unexpected error: {error}"
        );
    }

    #[test]
    fn extract_page_rejects_crc_mismatch() {
        for compression in [CompressionMethod::Stored, CompressionMethod::Deflated] {
            let reader = LoggingReader::new(make_zip(compression));
            let mut index = open_cbz(&reader).unwrap();
            index.pages[0].crc32 ^= 1;

            let error = index.extract_page(&reader, 0).unwrap_err().to_string();

            assert!(error.contains("CRC mismatch"), "unexpected error: {error}");
        }
    }

    fn empty_ranges() -> Vec<(u64, u64)> {
        Vec::new()
    }

    fn make_zip(compression: CompressionMethod) -> Vec<u8> {
        make_zip_with_bytes(compression, b"image-bytes")
    }

    fn make_zip_with_bytes(compression: CompressionMethod, bytes: &[u8]) -> Vec<u8> {
        let cursor = Cursor::new(Vec::new());
        let mut zip = ZipWriter::new(cursor);
        let options = SimpleFileOptions::default().compression_method(compression);
        zip.start_file("001.jpg", options).unwrap();
        zip.write_all(bytes).unwrap();
        zip.finish().unwrap().into_inner()
    }
}
