use comic_core::cbz::{CbzPageEntry, open_cbz};
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
    let mut index = open_cbz(&reader).unwrap();

    assert_eq!(
        b"stored".to_vec(),
        index.extract_page(&reader, 0).unwrap().bytes
    );
    assert_eq!(
        b"deflated".to_vec(),
        index.extract_page(&reader, 1).unwrap().bytes
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

    let mut index = open_cbz(&reader).unwrap();

    assert_eq!(3, index.pages.len());
    assert_eq!(2, reader.read_count());

    assert_eq!(
        b"one".to_vec(),
        index.extract_page(&reader, 0).unwrap().bytes
    );
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
    let mut index = comic_core::cbz::CbzIndex {
        pages: vec![CbzPageEntry {
            name: "1.jpg".to_string(),
            filename_len: 5,
            local_header_offset: 0,
            data_offset: None,
            compressed_size: 3,
            uncompressed_size: 3,
            compression_method: 0,
            crc32: crc32fast::hash(b"one"),
        }],
    };

    assert_eq!(
        b"one".to_vec(),
        index.extract_page(&reader, 0).unwrap().bytes
    );

    let ranges = reader.ranges.borrow();
    let total_bytes_read: u64 = ranges.iter().map(|(start, end)| end - start + 1).sum();
    assert!(
        total_bytes_read <= 4 * 1024 + 64,
        "unexpected page range reads: {ranges:?}",
    );
}

#[test]
fn opens_zip64_archive_with_locator_record_and_entry_extra() {
    let reader = RecordingBytesReader::new(make_zip64_single_page_bytes());

    let mut index = open_cbz(&reader).unwrap();

    assert_eq!(vec!["1.jpg"], page_names(&index.pages));
    assert_eq!(
        b"one".to_vec(),
        index.extract_page(&reader, 0).unwrap().bytes
    );
}

#[test]
fn extracts_page_when_local_header_uses_data_descriptor() {
    let reader = RecordingBytesReader::new(make_data_descriptor_zip_bytes());

    let mut index = open_cbz(&reader).unwrap();

    assert_eq!(
        b"one".to_vec(),
        index.extract_page(&reader, 0).unwrap().bytes
    );
}

#[test]
fn decodes_gbk_filenames_when_utf8_flag_is_not_set() {
    let reader = RecordingBytesReader::new(make_gbk_filename_zip_bytes());

    let index = open_cbz(&reader).unwrap();

    assert_eq!(vec!["中文.jpg"], page_names(&index.pages));
}

#[test]
fn encrypted_zip_returns_unsupported_error() {
    let reader = RecordingBytesReader::new(make_encrypted_zip_bytes());

    let error = open_cbz(&reader).unwrap_err().to_string();

    assert_eq!("unsupported encrypted zip", error);
}

#[test]
fn split_zip_returns_unsupported_error() {
    let reader = RecordingBytesReader::new(make_split_zip_bytes());

    let error = open_cbz(&reader).unwrap_err().to_string();

    assert_eq!("unsupported split zip", error);
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

fn make_data_descriptor_zip_bytes() -> Vec<u8> {
    let name = b"1.jpg";
    let data = b"one";
    let mut bytes = Vec::new();
    write_local_header(
        &mut bytes,
        0x0008,
        0,
        name,
        data.len() as u32,
        data.len() as u32,
        0,
    );
    bytes.extend_from_slice(data);
    write_u32(&mut bytes, 0x0807_4b50);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, data.len() as u32);
    write_u32(&mut bytes, data.len() as u32);
    let cd_start = bytes.len() as u32;
    write_central_header(
        &mut bytes,
        CentralHeader {
            flags: 0x0008,
            method: 0,
            name,
            extra: &[],
            crc32: crc32fast::hash(data),
            compressed_size: data.len() as u32,
            uncompressed_size: data.len() as u32,
            local_header_offset: 0,
            disk_start: 0,
        },
    );
    let cd_size = bytes.len() as u32 - cd_start;
    write_eocd(&mut bytes, 1, cd_size, cd_start);
    bytes
}

fn make_gbk_filename_zip_bytes() -> Vec<u8> {
    let name = &[0xd6, 0xd0, 0xce, 0xc4, b'.', b'j', b'p', b'g'];
    let data = b"one";
    let mut bytes = Vec::new();
    write_local_header(
        &mut bytes,
        0,
        0,
        name,
        data.len() as u32,
        data.len() as u32,
        0,
    );
    bytes.extend_from_slice(data);
    let cd_start = bytes.len() as u32;
    write_central_header(
        &mut bytes,
        CentralHeader {
            flags: 0,
            method: 0,
            name,
            extra: &[],
            crc32: crc32fast::hash(data),
            compressed_size: data.len() as u32,
            uncompressed_size: data.len() as u32,
            local_header_offset: 0,
            disk_start: 0,
        },
    );
    let cd_size = bytes.len() as u32 - cd_start;
    write_eocd(&mut bytes, 1, cd_size, cd_start);
    bytes
}

fn make_zip64_single_page_bytes() -> Vec<u8> {
    let name = b"1.jpg";
    let data = b"one";
    let mut bytes = Vec::new();
    write_local_header(
        &mut bytes,
        0,
        0,
        name,
        data.len() as u32,
        data.len() as u32,
        0,
    );
    bytes.extend_from_slice(data);
    let cd_start = bytes.len() as u64;
    let mut zip64_extra = Vec::new();
    write_u16(&mut zip64_extra, 0x0001);
    write_u16(&mut zip64_extra, 24);
    write_u64(&mut zip64_extra, data.len() as u64);
    write_u64(&mut zip64_extra, data.len() as u64);
    write_u64(&mut zip64_extra, 0);
    write_central_header(
        &mut bytes,
        CentralHeader {
            flags: 0,
            method: 0,
            name,
            extra: &zip64_extra,
            crc32: crc32fast::hash(data),
            compressed_size: u32::MAX,
            uncompressed_size: u32::MAX,
            local_header_offset: u32::MAX,
            disk_start: 0,
        },
    );
    let cd_size = bytes.len() as u64 - cd_start;
    let zip64_eocd_offset = bytes.len() as u64;
    write_zip64_eocd(&mut bytes, 1, cd_size, cd_start);
    write_zip64_locator(&mut bytes, zip64_eocd_offset);
    write_zip32_eocd_with_zip64_markers(&mut bytes);
    bytes
}

fn make_encrypted_zip_bytes() -> Vec<u8> {
    make_single_entry_zip_with_central_flags(0x0001, 0)
}

fn make_split_zip_bytes() -> Vec<u8> {
    make_single_entry_zip_with_central_flags(0, 1)
}

fn make_single_entry_zip_with_central_flags(flags: u16, disk_start: u16) -> Vec<u8> {
    let name = b"1.jpg";
    let data = b"one";
    let mut bytes = Vec::new();
    write_local_header(
        &mut bytes,
        flags,
        0,
        name,
        data.len() as u32,
        data.len() as u32,
        0,
    );
    bytes.extend_from_slice(data);
    let cd_start = bytes.len() as u32;
    write_central_header(
        &mut bytes,
        CentralHeader {
            flags,
            method: 0,
            name,
            extra: &[],
            crc32: crc32fast::hash(data),
            compressed_size: data.len() as u32,
            uncompressed_size: data.len() as u32,
            local_header_offset: 0,
            disk_start,
        },
    );
    let cd_size = bytes.len() as u32 - cd_start;
    write_eocd(&mut bytes, 1, cd_size, cd_start);
    bytes
}

fn write_local_header(
    bytes: &mut Vec<u8>,
    flags: u16,
    method: u16,
    name: &[u8],
    compressed_size: u32,
    uncompressed_size: u32,
    crc32: u32,
) {
    write_u32(bytes, 0x0403_4b50);
    write_u16(bytes, 20);
    write_u16(bytes, flags);
    write_u16(bytes, method);
    write_u16(bytes, 0);
    write_u16(bytes, 0);
    write_u32(bytes, crc32);
    write_u32(bytes, compressed_size);
    write_u32(bytes, uncompressed_size);
    write_u16(bytes, name.len() as u16);
    write_u16(bytes, 0);
    bytes.extend_from_slice(name);
}

struct CentralHeader<'a> {
    flags: u16,
    method: u16,
    name: &'a [u8],
    extra: &'a [u8],
    crc32: u32,
    compressed_size: u32,
    uncompressed_size: u32,
    local_header_offset: u32,
    disk_start: u16,
}

fn write_central_header(bytes: &mut Vec<u8>, header: CentralHeader<'_>) {
    write_u32(bytes, 0x0201_4b50);
    write_u16(bytes, 45);
    write_u16(bytes, 20);
    write_u16(bytes, header.flags);
    write_u16(bytes, header.method);
    write_u16(bytes, 0);
    write_u16(bytes, 0);
    write_u32(bytes, header.crc32);
    write_u32(bytes, header.compressed_size);
    write_u32(bytes, header.uncompressed_size);
    write_u16(bytes, header.name.len() as u16);
    write_u16(bytes, header.extra.len() as u16);
    write_u16(bytes, 0);
    write_u16(bytes, header.disk_start);
    write_u16(bytes, 0);
    write_u32(bytes, 0);
    write_u32(bytes, header.local_header_offset);
    bytes.extend_from_slice(header.name);
    bytes.extend_from_slice(header.extra);
}

fn write_zip64_eocd(bytes: &mut Vec<u8>, entries: u64, cd_size: u64, cd_offset: u64) {
    write_u32(bytes, 0x0606_4b50);
    write_u64(bytes, 44);
    write_u16(bytes, 45);
    write_u16(bytes, 45);
    write_u32(bytes, 0);
    write_u32(bytes, 0);
    write_u64(bytes, entries);
    write_u64(bytes, entries);
    write_u64(bytes, cd_size);
    write_u64(bytes, cd_offset);
}

fn write_zip64_locator(bytes: &mut Vec<u8>, zip64_eocd_offset: u64) {
    write_u32(bytes, 0x0706_4b50);
    write_u32(bytes, 0);
    write_u64(bytes, zip64_eocd_offset);
    write_u32(bytes, 1);
}

fn write_eocd(bytes: &mut Vec<u8>, entries: u16, cd_size: u32, cd_offset: u32) {
    write_u32(bytes, 0x0605_4b50);
    write_u16(bytes, 0);
    write_u16(bytes, 0);
    write_u16(bytes, entries);
    write_u16(bytes, entries);
    write_u32(bytes, cd_size);
    write_u32(bytes, cd_offset);
    write_u16(bytes, 0);
}

fn write_zip32_eocd_with_zip64_markers(bytes: &mut Vec<u8>) {
    write_u32(bytes, 0x0605_4b50);
    write_u16(bytes, 0);
    write_u16(bytes, 0);
    write_u16(bytes, u16::MAX);
    write_u16(bytes, u16::MAX);
    write_u32(bytes, u32::MAX);
    write_u32(bytes, u32::MAX);
    write_u16(bytes, 0);
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
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
