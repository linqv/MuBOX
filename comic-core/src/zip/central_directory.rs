use anyhow::Result;
use encoding_rs::GBK;

use crate::error::ComicCoreError;
use crate::zip::{read_u16_le, read_u32_le, read_u64_le};

const CENTRAL_DIRECTORY_SIGNATURE: u32 = 0x0201_4b50;
const ZIP64_EXTRA_ID: u16 = 0x0001;
const FLAG_ENCRYPTED: u16 = 0x0001;
const FLAG_UTF8_NAMES: u16 = 0x0800;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CentralDirectoryEntry {
    pub name: String,
    pub filename_len: u16,
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
        if flags & FLAG_ENCRYPTED != 0 {
            return Err(ComicCoreError::EncryptedZip.into());
        }
        let compression_method = read_u16_le(bytes, offset + 10)?;
        let crc32 = read_u32_le(bytes, offset + 16)?;
        let mut compressed_size = read_u32_le(bytes, offset + 20)? as u64;
        let mut uncompressed_size = read_u32_le(bytes, offset + 24)? as u64;
        let filename_len = read_u16_le(bytes, offset + 28)? as usize;
        let extra_len = read_u16_le(bytes, offset + 30)? as usize;
        let comment_len = read_u16_le(bytes, offset + 32)? as usize;
        let disk_start = read_u16_le(bytes, offset + 34)?;
        if disk_start != 0 {
            return Err(ComicCoreError::SplitZip.into());
        }
        let mut local_header_offset = read_u32_le(bytes, offset + 42)? as u64;
        let name_start = offset + 46;
        let name_end = name_start + filename_len;
        let name_bytes = bytes.get(name_start..name_end).ok_or_else(|| {
            ComicCoreError::InvalidZip("central directory filename out of bounds".to_string())
        })?;
        let extra_start = name_end;
        let extra_end = extra_start + extra_len;
        let extra = bytes.get(extra_start..extra_end).ok_or_else(|| {
            ComicCoreError::InvalidZip("central directory extra out of bounds".to_string())
        })?;
        apply_zip64_extra(
            extra,
            &mut uncompressed_size,
            &mut compressed_size,
            &mut local_header_offset,
        )?;
        let name = decode_filename(name_bytes, flags)?;

        entries.push(CentralDirectoryEntry {
            name,
            filename_len: filename_len as u16,
            flags,
            compression_method,
            crc32,
            compressed_size,
            uncompressed_size,
            local_header_offset,
        });
        offset = extra_end + comment_len;
    }

    if entries.len() as u64 != expected_entries {
        return Err(ComicCoreError::InvalidZip(
            "central directory entry count mismatch".to_string(),
        )
        .into());
    }
    Ok(entries)
}

fn apply_zip64_extra(
    extra: &[u8],
    uncompressed_size: &mut u64,
    compressed_size: &mut u64,
    local_header_offset: &mut u64,
) -> Result<()> {
    let needs_uncompressed = *uncompressed_size == u32::MAX as u64;
    let needs_compressed = *compressed_size == u32::MAX as u64;
    let needs_offset = *local_header_offset == u32::MAX as u64;
    if !needs_uncompressed && !needs_compressed && !needs_offset {
        return Ok(());
    }

    let mut offset = 0usize;
    while offset + 4 <= extra.len() {
        let header_id = read_u16_le(extra, offset)?;
        let data_size = read_u16_le(extra, offset + 2)? as usize;
        let data_start = offset + 4;
        let data_end = data_start + data_size;
        let data = extra
            .get(data_start..data_end)
            .ok_or_else(|| ComicCoreError::InvalidZip("zip64 extra out of bounds".to_string()))?;
        if header_id == ZIP64_EXTRA_ID {
            let mut cursor = 0usize;
            if needs_uncompressed {
                *uncompressed_size = read_u64_le(data, cursor)?;
                cursor += 8;
            }
            if needs_compressed {
                *compressed_size = read_u64_le(data, cursor)?;
                cursor += 8;
            }
            if needs_offset {
                *local_header_offset = read_u64_le(data, cursor)?;
            }
            return Ok(());
        }
        offset = data_end;
    }

    Err(ComicCoreError::InvalidZip("zip64 metadata missing".to_string()).into())
}

fn decode_filename(bytes: &[u8], flags: u16) -> Result<String> {
    if flags & FLAG_UTF8_NAMES != 0 {
        return std::str::from_utf8(bytes)
            .map(|value| value.to_string())
            .map_err(|_| ComicCoreError::InvalidUtf8Filename.into());
    }

    if let Ok(value) = std::str::from_utf8(bytes) {
        return Ok(value.to_string());
    }

    let (decoded, _, had_errors) = GBK.decode(bytes);
    if had_errors {
        Err(ComicCoreError::InvalidUtf8Filename.into())
    } else {
        Ok(decoded.into_owned())
    }
}
