use anyhow::Result;

use crate::error::ComicCoreError;
use crate::sort::natural;
use crate::zip::central_directory::parse_central_directory;
use crate::zip::eocd::find_eocd;
use crate::zip::RangeReader;

#[derive(Debug, Clone, PartialEq, Eq)]
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CbzIndex {
    pub pages: Vec<CbzPageEntry>,
}

pub fn open_cbz(reader: &impl RangeReader) -> Result<CbzIndex> {
    let eocd = find_eocd(reader)?;
    let cd_start = eocd.central_directory_offset;
    let cd_end = cd_start + eocd.central_directory_size - 1;
    let central_directory = reader.read_range(cd_start, cd_end)?;
    let mut pages: Vec<CbzPageEntry> =
        parse_central_directory(&central_directory, eocd.total_entries)?
            .into_iter()
            .filter(|entry| is_supported_image(&entry.name))
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

fn page_file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn is_supported_image(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.ends_with(".jpg")
        || lower.ends_with(".jpeg")
        || lower.ends_with(".png")
        || lower.ends_with(".webp")
}
