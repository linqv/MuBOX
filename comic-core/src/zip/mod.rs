use anyhow::Result;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use crate::error::ComicCoreError;

pub mod central_directory;
pub mod eocd;
pub mod local_header;
pub mod zip64;

pub trait RangeReader {
    fn size(&self) -> Result<u64>;
    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>>;
}

fn read_u16_le(bytes: &[u8], offset: usize) -> Result<u16> {
    let slice = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| ComicCoreError::InvalidZip("unexpected end of data".to_string()))?;
    Ok(u16::from_le_bytes([slice[0], slice[1]]))
}

fn read_u32_le(bytes: &[u8], offset: usize) -> Result<u32> {
    let slice = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| ComicCoreError::InvalidZip("unexpected end of data".to_string()))?;
    Ok(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

pub struct FileRangeReader {
    file: File,
    size: u64,
}

impl FileRangeReader {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let file = File::open(path)?;
        let size = file.metadata()?.len();
        Ok(Self { file, size })
    }
}

impl RangeReader for FileRangeReader {
    fn size(&self) -> Result<u64> {
        Ok(self.size)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        if start > end_inclusive || end_inclusive >= self.size {
            return Err(ComicCoreError::RangeOutOfBounds {
                start,
                end_inclusive,
                size: self.size,
            }
            .into());
        }

        let mut file = self.file.try_clone()?;
        file.seek(SeekFrom::Start(start))?;
        let len = (end_inclusive - start + 1) as usize;
        let mut bytes = vec![0; len];
        file.read_exact(&mut bytes)?;
        Ok(bytes)
    }
}
