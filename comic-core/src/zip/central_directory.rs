use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::{read_u16_le, read_u32_le};

const CENTRAL_DIRECTORY_SIGNATURE: u32 = 0x0201_4b50;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CentralDirectoryEntry {
    pub name: String,
    pub flags: u16,
    pub compression_method: u16,
    pub crc32: u32,
    pub compressed_size: u64,
    pub uncompressed_size: u64,
    pub local_header_offset: u64,
}

pub fn parse_central_directory(
    bytes: &[u8],
    expected_entries: u64,
) -> Result<Vec<CentralDirectoryEntry>> {
    let mut entries = Vec::new();
    let mut offset = 0usize;

    while offset < bytes.len() {
        if read_u32_le(bytes, offset)? != CENTRAL_DIRECTORY_SIGNATURE {
            return Err(ComicCoreError::InvalidZip(
                "invalid central directory signature".to_string(),
            )
            .into());
        }
        let flags = read_u16_le(bytes, offset + 8)?;
        let compression_method = read_u16_le(bytes, offset + 10)?;
        let crc32 = read_u32_le(bytes, offset + 16)?;
        let compressed_size = read_u32_le(bytes, offset + 20)? as u64;
        let uncompressed_size = read_u32_le(bytes, offset + 24)? as u64;
        let filename_len = read_u16_le(bytes, offset + 28)? as usize;
        let extra_len = read_u16_le(bytes, offset + 30)? as usize;
        let comment_len = read_u16_le(bytes, offset + 32)? as usize;
        let local_header_offset = read_u32_le(bytes, offset + 42)? as u64;
        let name_start = offset + 46;
        let name_end = name_start + filename_len;
        let name_bytes = bytes.get(name_start..name_end).ok_or_else(|| {
            ComicCoreError::InvalidZip("central directory filename out of bounds".to_string())
        })?;
        let name = String::from_utf8_lossy(name_bytes).to_string();

        entries.push(CentralDirectoryEntry {
            name,
            flags,
            compression_method,
            crc32,
            compressed_size,
            uncompressed_size,
            local_header_offset,
        });
        offset = name_end + extra_len + comment_len;
    }

    if entries.len() as u64 != expected_entries {
        return Err(ComicCoreError::InvalidZip(
            "central directory entry count mismatch".to_string(),
        )
        .into());
    }
    Ok(entries)
}
