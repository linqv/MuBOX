use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::zip64::{find_zip64_eocd, needs_zip64, unsupported_zip64_error};
use crate::zip::{RangeReader, read_u16_le, read_u32_le};

const EOCD_SIGNATURE: u32 = 0x0605_4b50;
const EOCD_MIN_SIZE: usize = 22;
const EOCD_SEARCH_WINDOW: u64 = EOCD_MIN_SIZE as u64 + u16::MAX as u64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Eocd {
    pub total_entries: u64,
    pub central_directory_size: u64,
    pub central_directory_offset: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EocdSearch {
    pub eocd: Eocd,
    pub record_offset: u64,
    pub window_start: u64,
    pub window: Vec<u8>,
}

pub fn find_eocd(reader: &impl RangeReader) -> Result<Eocd> {
    Ok(find_eocd_search(reader)?.eocd)
}

pub fn find_eocd_search(reader: &impl RangeReader) -> Result<EocdSearch> {
    let size = reader.size()?;
    if size < EOCD_MIN_SIZE as u64 {
        return Err(ComicCoreError::InvalidZip("file too small for EOCD".to_string()).into());
    }

    let quick_start = size - EOCD_MIN_SIZE as u64;
    let quick = reader.read_range(quick_start, size - 1)?;
    if read_u32_le(&quick, 0)? == EOCD_SIGNATURE && read_u16_le(&quick, 20)? == 0 {
        let eocd = parse_eocd_at(&quick, 0)?;
        let cd_end_exclusive = eocd
            .central_directory_offset
            .checked_add(eocd.central_directory_size);
        if cd_end_exclusive == Some(quick_start) {
            return Ok(EocdSearch {
                eocd,
                record_offset: quick_start,
                window_start: quick_start,
                window: quick,
            });
        }
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
            let eocd = parse_eocd_at(&bytes, index)?;
            let eocd = resolve_zip64(reader, &eocd, start + index as u64)?;
            return Ok(EocdSearch {
                eocd,
                record_offset: start + index as u64,
                window_start: start,
                window: bytes,
            });
        }
    }

    Err(ComicCoreError::InvalidZip("EOCD not found".to_string()).into())
}

fn parse_eocd_at(bytes: &[u8], index: usize) -> Result<Eocd> {
    Ok(Eocd {
        total_entries: read_u16_le(bytes, index + 10)? as u64,
        central_directory_size: read_u32_le(bytes, index + 12)? as u64,
        central_directory_offset: read_u32_le(bytes, index + 16)? as u64,
    })
}

fn resolve_zip64(reader: &impl RangeReader, eocd: &Eocd, eocd_offset: u64) -> Result<Eocd> {
    if !needs_zip64(
        eocd.total_entries,
        eocd.central_directory_size,
        eocd.central_directory_offset,
    ) {
        return Ok(eocd.clone());
    }
    let zip64 = find_zip64_eocd(reader, eocd_offset)?.ok_or_else(unsupported_zip64_error)?;
    Ok(Eocd {
        total_entries: zip64.total_entries,
        central_directory_size: zip64.central_directory_size,
        central_directory_offset: zip64.central_directory_offset,
    })
}
