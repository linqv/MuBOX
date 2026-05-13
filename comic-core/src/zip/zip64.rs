use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::{read_u32_le, read_u64_le, RangeReader};

const ZIP64_EOCD_SIGNATURE: u32 = 0x0606_4b50;
const ZIP64_LOCATOR_SIGNATURE: u32 = 0x0706_4b50;
const ZIP64_LOCATOR_SIZE: u64 = 20;
const ZIP64_EOCD_MIN_SIZE: u64 = 56;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Zip64Eocd {
    pub total_entries: u64,
    pub central_directory_size: u64,
    pub central_directory_offset: u64,
}

pub fn unsupported_zip64_error() -> ComicCoreError {
    ComicCoreError::InvalidZip("zip64 metadata missing".to_string())
}

pub fn needs_zip64(
    total_entries: u64,
    central_directory_size: u64,
    central_directory_offset: u64,
) -> bool {
    total_entries == u16::MAX as u64
        || central_directory_size == u32::MAX as u64
        || central_directory_offset == u32::MAX as u64
}

pub fn find_zip64_eocd(reader: &impl RangeReader, eocd_offset: u64) -> Result<Option<Zip64Eocd>> {
    if eocd_offset < ZIP64_LOCATOR_SIZE {
        return Ok(None);
    }
    let locator_start = eocd_offset - ZIP64_LOCATOR_SIZE;
    let locator = reader.read_range(locator_start, eocd_offset - 1)?;
    if read_u32_le(&locator, 0)? != ZIP64_LOCATOR_SIGNATURE {
        return Ok(None);
    }

    let record_offset = read_u64_le(&locator, 8)?;
    let record_header =
        reader.read_range(record_offset, record_offset + ZIP64_EOCD_MIN_SIZE - 1)?;
    if read_u32_le(&record_header, 0)? != ZIP64_EOCD_SIGNATURE {
        return Err(ComicCoreError::InvalidZip("invalid zip64 EOCD signature".to_string()).into());
    }
    Ok(Some(Zip64Eocd {
        total_entries: read_u64_le(&record_header, 32)?,
        central_directory_size: read_u64_le(&record_header, 40)?,
        central_directory_offset: read_u64_le(&record_header, 48)?,
    }))
}

#[cfg(test)]
mod tests {
    use super::unsupported_zip64_error;

    #[test]
    fn zip64_without_metadata_is_explicitly_invalid() {
        assert_eq!(
            "invalid zip: zip64 metadata missing",
            unsupported_zip64_error().to_string()
        );
    }
}
