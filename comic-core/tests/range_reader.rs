use comic_core::zip::eocd::find_eocd;
use comic_core::zip::{FileRangeReader, RangeReader};
use std::cell::RefCell;
use std::io::Write;
use tempfile::NamedTempFile;

#[test]
fn file_range_reader_reads_inclusive_byte_range() {
    let mut file = NamedTempFile::new().unwrap();
    file.write_all(&[0, 1, 2, 3, 4]).unwrap();

    let reader = FileRangeReader::open(file.path()).unwrap();

    assert_eq!(5, reader.size().unwrap());
    assert_eq!(vec![1, 2, 3], reader.read_range(1, 3).unwrap());
}

#[test]
fn file_range_reader_rejects_ranges_past_end() {
    let mut file = NamedTempFile::new().unwrap();
    file.write_all(&[0, 1, 2]).unwrap();

    let reader = FileRangeReader::open(file.path()).unwrap();

    assert!(reader.read_range(1, 3).is_err());
}

#[test]
fn eocd_search_reads_only_zip_comment_window() {
    let reader = RecordingRangeReader::new(make_bytes_with_eocd(1024 * 1024));

    find_eocd(&reader).unwrap();

    let ranges = reader.ranges.borrow();
    let (start, end) = ranges.single();
    assert_eq!(*end, reader.size().unwrap() - 1);
    assert!(*end - *start < 65_557);
}

fn make_bytes_with_eocd(size: usize) -> Vec<u8> {
    let mut bytes = vec![0; size];
    let eocd_start = size - 22;
    bytes[eocd_start..eocd_start + 4].copy_from_slice(&0x0605_4b50u32.to_le_bytes());
    bytes[eocd_start + 10..eocd_start + 12].copy_from_slice(&0u16.to_le_bytes());
    bytes[eocd_start + 12..eocd_start + 16].copy_from_slice(&0u32.to_le_bytes());
    bytes[eocd_start + 16..eocd_start + 20].copy_from_slice(&(eocd_start as u32).to_le_bytes());
    bytes[eocd_start + 20..eocd_start + 22].copy_from_slice(&0u16.to_le_bytes());
    bytes
}

struct RecordingRangeReader {
    bytes: Vec<u8>,
    ranges: RefCell<Vec<(u64, u64)>>,
}

impl RecordingRangeReader {
    fn new(bytes: Vec<u8>) -> Self {
        Self {
            bytes,
            ranges: RefCell::new(Vec::new()),
        }
    }
}

impl RangeReader for RecordingRangeReader {
    fn size(&self) -> anyhow::Result<u64> {
        Ok(self.bytes.len() as u64)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        self.ranges.borrow_mut().push((start, end_inclusive));
        Ok(self.bytes[start as usize..=end_inclusive as usize].to_vec())
    }
}

trait SingleRange {
    fn single(&self) -> &(u64, u64);
}

impl SingleRange for [(u64, u64)] {
    fn single(&self) -> &(u64, u64) {
        assert_eq!(1, self.len());
        &self[0]
    }
}
