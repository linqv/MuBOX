use anyhow::Result;

use crate::error::ComicCoreError;
use crate::zip::{read_u16_le, read_u32_le, RangeReader};

const LOCAL_HEADER_SIGNATURE: u32 = 0x0403_4b50;

pub fn data_offset(reader: &impl RangeReader, local_header_offset: u64) -> Result<u64> {
    let header = reader.read_range(local_header_offset, local_header_offset + 29)?;
    if read_u32_le(&header, 0)? != LOCAL_HEADER_SIGNATURE {
        return Err(ComicCoreError::InvalidZip("invalid local header signature".to_string()).into());
    }
    let filename_len = read_u16_le(&header, 26)? as u64;
    let extra_len = read_u16_le(&header, 28)? as u64;
    Ok(local_header_offset + 30 + filename_len + extra_len)
}
