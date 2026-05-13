use anyhow::Result;
use flate2::read::DeflateDecoder;
use std::io::Read;

use crate::cbz::index::CbzIndex;
use crate::error::ComicCoreError;
use crate::zip::local_header;
use crate::zip::RangeReader;

impl CbzIndex {
    pub fn extract_page(&self, reader: &impl RangeReader, page_index: usize) -> Result<Vec<u8>> {
        let page = self
            .pages
            .get(page_index)
            .ok_or_else(|| ComicCoreError::InvalidZip("page index out of bounds".to_string()))?;
        let start = match page.data_offset {
            Some(offset) => offset,
            None => local_header::data_offset(reader, page.local_header_offset)?,
        };
        let end = start + page.compressed_size - 1;
        let compressed = reader.read_range(start, end)?;

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
