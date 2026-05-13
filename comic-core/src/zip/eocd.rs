use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::{read_u16_le, read_u32_le, RangeReader};

const EOCD_SIGNATURE: u32 = 0x0605_4b50;
const EOCD_MIN_SIZE: usize = 22;
const EOCD_SEARCH_WINDOW: u64 = 256 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Eocd {
    pub total_entries: u64,
    pub central_directory_size: u64,
    pub central_directory_offset: u64,
}

pub fn find_eocd(reader: &impl RangeReader) -> Result<Eocd> {
    let size = reader.size()?;
    if size < EOCD_MIN_SIZE as u64 {
        return Err(ComicCoreError::InvalidZip("file too small for EOCD".to_string()).into());
    }

    let window_len = size.min(EOCD_SEARCH_WINDOW);
    let start = size - window_len;
    let bytes = reader.read_range(start, size - 1)?;
    if bytes.len() < EOCD_MIN_SIZE {
        return Err(ComicCoreError::InvalidZip("EOCD search window too small".to_string()).into());
    }

    for index in (0..=bytes.len() - EOCD_MIN_SIZE).rev() {
        if read_u32_le(&bytes, index)? == EOCD_SIGNATURE {
            let comment_len = read_u16_le(&bytes, index + 20)? as usize;
            if index + EOCD_MIN_SIZE + comment_len != bytes.len() {
                continue;
            }
            let total_entries = read_u16_le(&bytes, index + 10)? as u64;
            let central_directory_size = read_u32_le(&bytes, index + 12)? as u64;
            let central_directory_offset = read_u32_le(&bytes, index + 16)? as u64;
            return Ok(Eocd {
                total_entries,
                central_directory_size,
                central_directory_offset,
            });
        }
    }

    Err(ComicCoreError::InvalidZip("EOCD not found".to_string()).into())
}
