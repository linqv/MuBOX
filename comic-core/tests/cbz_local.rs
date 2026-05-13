use comic_core::cbz::{open_cbz, CbzPageEntry};
use comic_core::zip::{FileRangeReader, RangeReader};
use std::cell::{Cell, RefCell};
use std::fs::File;
use std::io::Write;
use tempfile::NamedTempFile;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

#[test]
fn opens_local_cbz_and_naturally_sorts_images() {
    let archive = make_zip(&[
        ("10.jpg", b"ten".as_slice(), CompressionMethod::Stored),
        ("1.jpg", b"one".as_slice(), CompressionMethod::Stored),
        ("notes.txt", b"skip".as_slice(), CompressionMethod::Stored),
        ("nested/2.png", b"two".as_slice(), CompressionMethod::Stored),
        ("中文.webp", b"cn".as_slice(), CompressionMethod::Stored),
    ]);
    let reader = FileRangeReader::open(archive.path()).unwrap();

    let index = open_cbz(&reader).unwrap();

    assert_eq!(
        vec!["1.jpg", "nested/2.png", "10.jpg", "中文.webp"],
        page_names(&index.pages)
    );
}

#[test]
fn extracts_store_and_deflate_pages() {
    let archive = make_zip(&[
        ("1.jpg", b"stored".as_slice(), CompressionMethod::Stored),
        ("2.jpg", b"deflated".as_slice(), CompressionMethod::Deflated),
    ]);
    let reader = FileRangeReader::open(archive.path()).unwrap();
    let index = open_cbz(&reader).unwrap();

    assert_eq!(b"stored".to_vec(), index.extract_page(&reader, 0).unwrap());
    assert_eq!(
        b"deflated".to_vec(),
        index.extract_page(&reader, 1).unwrap()
    );
}

#[test]
fn rejects_archives_without_images() {
    let archive = make_zip(&[("notes.txt", b"skip".as_slice(), CompressionMethod::Stored)]);
    let reader = FileRangeReader::open(archive.path()).unwrap();

    assert!(open_cbz(&reader).is_err());
}

#[test]
fn open_cbz_defers_local_header_reads_until_page_extraction() {
    let archive = make_zip(&[
        ("1.jpg", b"one".as_slice(), CompressionMethod::Stored),
        ("2.jpg", b"two".as_slice(), CompressionMethod::Stored),
        ("3.jpg", b"three".as_slice(), CompressionMethod::Stored),
    ]);
    let reader = CountingRangeReader::new(FileRangeReader::open(archive.path()).unwrap());

    let index = open_cbz(&reader).unwrap();

    assert_eq!(3, index.pages.len());
    assert_eq!(2, reader.read_count());

    assert_eq!(b"one".to_vec(), index.extract_page(&reader, 0).unwrap());
    assert_eq!(3, reader.read_count());
}

#[test]
fn open_cbz_uses_eocd_fast_path_and_reads_central_directory_with_tail() {
    let archive = make_zip(&[
        ("1.jpg", b"one".as_slice(), CompressionMethod::Stored),
        ("2.jpg", b"two".as_slice(), CompressionMethod::Stored),
    ]);
    let reader = RecordingFileRangeReader::new(FileRangeReader::open(archive.path()).unwrap());

    let index = open_cbz(&reader).unwrap();

    assert_eq!(2, index.pages.len());
    let ranges = reader.ranges.borrow();
    assert_eq!(2, ranges.len(), "unexpected ranges: {ranges:?}");
    let size = reader.size().unwrap();
    assert_eq!((size - 22, size - 1), ranges[0]);
    assert_eq!(size - 1, ranges[1].1);
}

#[test]
fn open_cbz_reuses_comment_tail_window_for_central_directory() {
    let archive = make_zip_with_comment(
        &[
            ("1.jpg", b"one".as_slice(), CompressionMethod::Stored),
            ("2.jpg", b"two".as_slice(), CompressionMethod::Stored),
        ],
        "comment",
    );
    let reader = RecordingFileRangeReader::new(FileRangeReader::open(archive.path()).unwrap());

    let index = open_cbz(&reader).unwrap();

    assert_eq!(2, index.pages.len());
    let ranges = reader.ranges.borrow();
    assert_eq!(2, ranges.len(), "unexpected ranges: {ranges:?}");
    let size = reader.size().unwrap();
    assert_eq!((size - 22, size - 1), ranges[0]);
    assert_eq!(size - 1, ranges[1].1);
}

#[test]
fn extracting_page_does_not_overread_full_local_header_extra_limit() {
    let reader = RecordingBytesReader::new(make_single_page_bytes());
    let index = comic_core::cbz::CbzIndex {
        pages: vec![CbzPageEntry {
            name: "1.jpg".to_string(),
            filename_len: 5,
            local_header_offset: 0,
            data_offset: None,
            compressed_size: 3,
            uncompressed_size: 3,
            compression_method: 0,
            crc32: 0,
        }],
    };

    assert_eq!(b"one".to_vec(), index.extract_page(&reader, 0).unwrap());

    let ranges = reader.ranges.borrow();
    let total_bytes_read: u64 = ranges.iter().map(|(start, end)| end - start + 1).sum();
    assert!(
        total_bytes_read <= 4 * 1024 + 64,
        "unexpected page range reads: {ranges:?}",
    );
}

fn make_zip(entries: &[(&str, &[u8], CompressionMethod)]) -> NamedTempFile {
    let file = NamedTempFile::new().unwrap();
    {
        let writer = File::create(file.path()).unwrap();
        let mut zip = ZipWriter::new(writer);
        for (name, bytes, method) in entries {
            let options = SimpleFileOptions::default().compression_method(*method);
            zip.start_file(*name, options).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }
    file
}

fn make_zip_with_comment(
    entries: &[(&str, &[u8], CompressionMethod)],
    comment: &str,
) -> NamedTempFile {
    let file = NamedTempFile::new().unwrap();
    {
        let writer = File::create(file.path()).unwrap();
        let mut zip = ZipWriter::new(writer);
        zip.set_comment(comment);
        for (name, bytes, method) in entries {
            let options = SimpleFileOptions::default().compression_method(*method);
            zip.start_file(*name, options).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }
    file
}

fn page_names(pages: &[CbzPageEntry]) -> Vec<String> {
    pages.iter().map(|page| page.name.clone()).collect()
}

struct CountingRangeReader {
    inner: FileRangeReader,
    reads: Cell<usize>,
}

impl CountingRangeReader {
    fn new(inner: FileRangeReader) -> Self {
        Self {
            inner,
            reads: Cell::new(0),
        }
    }

    fn read_count(&self) -> usize {
        self.reads.get()
    }
}

impl RangeReader for CountingRangeReader {
    fn size(&self) -> anyhow::Result<u64> {
        self.inner.size()
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        self.reads.set(self.reads.get() + 1);
        self.inner.read_range(start, end_inclusive)
    }
}

struct RecordingFileRangeReader {
    inner: FileRangeReader,
    ranges: RefCell<Vec<(u64, u64)>>,
}

impl RecordingFileRangeReader {
    fn new(inner: FileRangeReader) -> Self {
        Self {
            inner,
            ranges: RefCell::new(Vec::new()),
        }
    }
}

impl RangeReader for RecordingFileRangeReader {
    fn size(&self) -> anyhow::Result<u64> {
        self.inner.size()
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        self.ranges.borrow_mut().push((start, end_inclusive));
        self.inner.read_range(start, end_inclusive)
    }
}

fn make_single_page_bytes() -> Vec<u8> {
    let mut bytes = vec![0; 128 * 1024];
    bytes[0..4].copy_from_slice(&0x0403_4b50u32.to_le_bytes());
    bytes[26..28].copy_from_slice(&5u16.to_le_bytes());
    bytes[28..30].copy_from_slice(&0u16.to_le_bytes());
    bytes[30..35].copy_from_slice(b"1.jpg");
    bytes[35..38].copy_from_slice(b"one");
    bytes
}

struct RecordingBytesReader {
    bytes: Vec<u8>,
    ranges: RefCell<Vec<(u64, u64)>>,
}

impl RecordingBytesReader {
    fn new(bytes: Vec<u8>) -> Self {
        Self {
            bytes,
            ranges: RefCell::new(Vec::new()),
        }
    }
}

impl RangeReader for RecordingBytesReader {
    fn size(&self) -> anyhow::Result<u64> {
        Ok(self.bytes.len() as u64)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        self.ranges.borrow_mut().push((start, end_inclusive));
        Ok(self.bytes[start as usize..=end_inclusive as usize].to_vec())
    }
}
