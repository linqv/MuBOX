use anyhow::Result;
use flate2::read::DeflateDecoder;
use std::io::Read;

use crate::cbz::index::CbzIndex;
use crate::error::ComicCoreError;
use crate::zip::local_header::{relative_data_offset, LOCAL_HEADER_MIN_SIZE, MAX_LOCAL_HEADER_EXTRA_LEN};
use crate::zip::RangeReader;

impl CbzIndex {
    pub fn extract_page(&self, reader: &impl RangeReader, page_index: usize) -> Result<Vec<u8>> {
        let page = self
            .pages
            .get(page_index)
            .ok_or_else(|| ComicCoreError::InvalidZip("page index out of bounds".to_string()))?;
        let compressed = match page.data_offset {
            Some(offset) => {
                let end = offset + page.compressed_size - 1;
                reader.read_range(offset, end)?
            }
            None => read_page_with_local_header(reader, page)?,
        };

        match page.compression_method {
            0 => Ok(compressed),
            8 => {
                let mut decoder = DeflateDecoder::new(compressed.as_slice());
                let mut output = Vec::with_capacity(page.uncompressed_size as usize);
                decoder.read_to_end(&mut output)?;
                Ok(output)
            }
            method => Err(ComicCoreError::UnsupportedCompression(method).into()),
        }
    }
}

fn read_page_with_local_header(
    reader: &impl RangeReader,
    page: &crate::cbz::index::CbzPageEntry,
) -> Result<Vec<u8>> {
    let max_header_len =
        LOCAL_HEADER_MIN_SIZE + page.filename_len as u64 + MAX_LOCAL_HEADER_EXTRA_LEN;
    let range_len = max_header_len + page.compressed_size;
    let file_size = reader.size()?;
    let end = page
        .local_header_offset
        .checked_add(range_len)
        .and_then(|value| value.checked_sub(1))
        .map(|value| value.min(file_size.saturating_sub(1)))
        .ok_or_else(|| ComicCoreError::InvalidZip("page range overflow".to_string()))?;
    let bytes = reader.read_range(page.local_header_offset, end)?;
    let data_start = relative_data_offset(&bytes)? as usize;
    let data_end = data_start
        .checked_add(page.compressed_size as usize)
        .ok_or_else(|| ComicCoreError::InvalidZip("page data range overflow".to_string()))?;
    let compressed = bytes
        .get(data_start..data_end)
        .ok_or_else(|| ComicCoreError::InvalidZip("page data out of bounds".to_string()))?;
    Ok(compressed.to_vec())
}
