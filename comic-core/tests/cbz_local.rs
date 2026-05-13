use comic_core::cbz::{open_cbz, CbzPageEntry};
use comic_core::zip::{FileRangeReader, RangeReader};
use std::cell::Cell;
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
    assert!(reader.read_count() > 2);
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
