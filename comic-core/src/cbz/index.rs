use anyhow::Result;

use crate::error::ComicCoreError;
use crate::image::{is_supported_image, ImageFormatOptions};
use crate::sort::natural;
use crate::zip::central_directory::parse_central_directory;
use crate::zip::eocd::{find_eocd_search, EocdSearch};
use crate::zip::RangeReader;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CbzPageEntry {
    pub name: String,
    pub filename_len: u16,
    pub local_header_offset: u64,
    pub data_offset: Option<u64>,
    pub compressed_size: u64,
    pub uncompressed_size: u64,
    pub compression_method: u16,
    pub crc32: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CbzIndex {
    pub pages: Vec<CbzPageEntry>,
}

pub fn open_cbz(reader: &impl RangeReader) -> Result<CbzIndex> {
    open_cbz_with_options(reader, ImageFormatOptions::default())
}

pub fn open_cbz_with_options(
    reader: &impl RangeReader,
    options: ImageFormatOptions,
) -> Result<CbzIndex> {
    let eocd_search = find_eocd_search(reader)?;
    let eocd = &eocd_search.eocd;
    let central_directory = read_central_directory(reader, &eocd_search)?;
    let mut pages: Vec<CbzPageEntry> =
        parse_central_directory(&central_directory, eocd.total_entries)?
            .into_iter()
            .filter(|entry| is_supported_image(&entry.name, options))
            .map(|entry| CbzPageEntry {
                name: entry.name,
                filename_len: entry.filename_len,
                local_header_offset: entry.local_header_offset,
                data_offset: None,
                compressed_size: entry.compressed_size,
                uncompressed_size: entry.uncompressed_size,
                compression_method: entry.compression_method,
                crc32: entry.crc32,
            })
            .collect();

    if pages.is_empty() {
        return Err(ComicCoreError::NoImages.into());
    }
    pages.sort_by(|left, right| {
        natural::compare(page_file_name(&left.name), page_file_name(&right.name))
            .then_with(|| natural::compare(&left.name, &right.name))
    });
    Ok(CbzIndex { pages })
}

fn read_central_directory(reader: &impl RangeReader, search: &EocdSearch) -> Result<Vec<u8>> {
    let eocd = &search.eocd;
    if eocd.central_directory_size == 0 {
        return Ok(Vec::new());
    }

    let cd_start = eocd.central_directory_offset;
    let cd_end = cd_start
        .checked_add(eocd.central_directory_size)
        .and_then(|value| value.checked_sub(1))
        .ok_or_else(|| {
            ComicCoreError::InvalidZip("central directory range overflow".to_string())
        })?;
    let window_end_exclusive = search.window_start + search.window.len() as u64;
    if cd_start >= search.window_start && cd_end < window_end_exclusive {
        let start = (cd_start - search.window_start) as usize;
        let end = start + eocd.central_directory_size as usize;
        return Ok(search.window[start..end].to_vec());
    }

    let file_end = reader.size()?.saturating_sub(1);
    let read_end = if cd_end.checked_add(1) == Some(search.record_offset) {
        file_end
    } else {
        cd_end
    };
    let cd_len = eocd.central_directory_size as usize;
    let bytes = reader.read_range(cd_start, read_end)?;
    let central_directory = bytes.get(..cd_len).ok_or_else(|| {
        ComicCoreError::InvalidZip("central directory data out of bounds".to_string())
    })?;
    Ok(central_directory.to_vec())
}

fn page_file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}
