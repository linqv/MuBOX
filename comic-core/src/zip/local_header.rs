use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::{RangeReader, read_u16_le, read_u32_le};

const LOCAL_HEADER_SIGNATURE: u32 = 0x0403_4b50;
pub const LOCAL_HEADER_MIN_SIZE: u64 = 30;
pub const MAX_LOCAL_HEADER_EXTRA_LEN: u64 = u16::MAX as u64;

pub fn data_offset(reader: &impl RangeReader, local_header_offset: u64) -> Result<u64> {
    let header = reader.read_range(
        local_header_offset,
        local_header_offset + LOCAL_HEADER_MIN_SIZE - 1,
    )?;
    Ok(local_header_offset + relative_data_offset(&header)?)
}

pub fn relative_data_offset(header: &[u8]) -> Result<u64> {
    if read_u32_le(header, 0)? != LOCAL_HEADER_SIGNATURE {
        return Err(
            ComicCoreError::InvalidZip("invalid local header signature".to_string()).into(),
        );
    }
    let filename_len = read_u16_le(header, 26)? as u64;
    let extra_len = read_u16_le(header, 28)? as u64;
    Ok(LOCAL_HEADER_MIN_SIZE + filename_len + extra_len)
}
