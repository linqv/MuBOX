use comic_core::zip::{FileRangeReader, RangeReader};
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
