use anyhow::{anyhow, Result};
use sevenz_rust2::{ArchiveReader, Password};
use std::fs::File;
use std::path::Path;

#[cfg(unix)]
use std::os::fd::FromRawFd;

use crate::cbz::{open_cbz_with_options, CbzIndex};
use crate::error::ComicCoreError;
pub use crate::image::{is_supported_image, ImageFormatOptions};
use crate::sort::natural;
use crate::zip::{FileRangeReader, RangeReader};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveFormat {
    Zip,
    SevenZ,
    Tar,
}

pub enum LocalArchiveSession {
    Zip {
        reader: FileRangeReader,
        index: CbzIndex,
    },
    SevenZ {
        reader: ArchiveReader<File>,
        pages: Vec<SevenZPageEntry>,
    },
    Tar {
        reader: FileRangeReader,
        index: TarIndex,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SevenZPageEntry {
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TarPageEntry {
    pub name: String,
    pub data_offset: u64,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TarIndex {
    pub pages: Vec<TarPageEntry>,
}

impl LocalArchiveSession {
    pub fn page_count(&self) -> usize {
        match self {
            LocalArchiveSession::Zip { index, .. } => index.pages.len(),
            LocalArchiveSession::SevenZ { pages, .. } => pages.len(),
            LocalArchiveSession::Tar { index, .. } => index.pages.len(),
        }
    }

    pub fn page_names(&self) -> Vec<String> {
        match self {
            LocalArchiveSession::Zip { index, .. } => {
                index.pages.iter().map(|page| page.name.clone()).collect()
            }
            LocalArchiveSession::SevenZ { pages, .. } => {
                pages.iter().map(|page| page.name.clone()).collect()
            }
            LocalArchiveSession::Tar { index, .. } => {
                index.pages.iter().map(|page| page.name.clone()).collect()
            }
        }
    }

    pub fn extract_page(&mut self, page_index: usize) -> Result<Vec<u8>> {
        match self {
            LocalArchiveSession::Zip { reader, index } => index.extract_page(reader, page_index),
            LocalArchiveSession::SevenZ { reader, pages } => {
                let name = pages
                    .get(page_index)
                    .ok_or_else(page_index_out_of_bounds)?
                    .name
                    .clone();
                Ok(reader.read_file(&name)?)
            }
            LocalArchiveSession::Tar { reader, index } => {
                let page = index
                    .pages
                    .get(page_index)
                    .ok_or_else(page_index_out_of_bounds)?;
                if page.size == 0 {
                    return Ok(Vec::new());
                }
                let end = page
                    .data_offset
                    .checked_add(page.size)
                    .and_then(|value| value.checked_sub(1))
                    .ok_or_else(|| anyhow!("tar page range overflow"))?;
                reader.read_range(page.data_offset, end)
            }
        }
    }
}

pub fn open_local_archive(path: &Path, format: ArchiveFormat) -> Result<LocalArchiveSession> {
    let file = File::open(path)?;
    open_local_archive_file(file, None, format, ImageFormatOptions::default())
}

pub fn open_local_archive_with_options(
    path: &Path,
    format: ArchiveFormat,
    options: ImageFormatOptions,
) -> Result<LocalArchiveSession> {
    let file = File::open(path)?;
    open_local_archive_file(file, None, format, options)
}

#[cfg(unix)]
pub fn open_local_archive_fd(
    fd: i32,
    size_hint: Option<u64>,
    format: ArchiveFormat,
) -> Result<LocalArchiveSession> {
    open_local_archive_fd_with_options(fd, size_hint, format, ImageFormatOptions::default())
}

#[cfg(unix)]
pub fn open_local_archive_fd_with_options(
    fd: i32,
    size_hint: Option<u64>,
    format: ArchiveFormat,
    options: ImageFormatOptions,
) -> Result<LocalArchiveSession> {
    if fd < 0 {
        return Err(anyhow!("invalid local archive file descriptor"));
    }
    let file = unsafe { File::from_raw_fd(fd) };
    open_local_archive_file(file, size_hint, format, options)
}

pub fn open_local_archive_file(
    file: File,
    size_hint: Option<u64>,
    format: ArchiveFormat,
    options: ImageFormatOptions,
) -> Result<LocalArchiveSession> {
    match format {
        ArchiveFormat::Zip => {
            let reader = FileRangeReader::from_file(file, size_hint)?;
            let index = open_cbz_with_options(&reader, options)?;
            Ok(LocalArchiveSession::Zip { reader, index })
        }
        ArchiveFormat::SevenZ => {
            let reader = ArchiveReader::new(file, Password::empty())?;
            let mut pages: Vec<SevenZPageEntry> = reader
                .archive()
                .files
                .iter()
                .filter(|entry| entry.has_stream() && !entry.is_directory())
                .filter(|entry| is_supported_image(entry.name(), options))
                .map(|entry| SevenZPageEntry {
                    name: entry.name().to_string(),
                })
                .collect();
            sort_pages_by_name(&mut pages, |page| &page.name);
            if pages.is_empty() {
                return Err(ComicCoreError::NoImages.into());
            }
            Ok(LocalArchiveSession::SevenZ { reader, pages })
        }
        ArchiveFormat::Tar => {
            let reader = FileRangeReader::from_file(file, size_hint)?;
            let index = open_tar_with_options(&reader, options)?;
            Ok(LocalArchiveSession::Tar { reader, index })
        }
    }
}

pub fn open_tar(reader: &impl RangeReader) -> Result<TarIndex> {
    open_tar_with_options(reader, ImageFormatOptions::default())
}

pub fn open_tar_with_options(
    reader: &impl RangeReader,
    options: ImageFormatOptions,
) -> Result<TarIndex> {
    let mut pages = Vec::new();
    let mut offset = 0u64;
    let size = reader.size()?;
    let mut pending_long_name: Option<String> = None;

    while offset.checked_add(512).is_some_and(|end| end <= size) {
        let header = reader.read_range(offset, offset + 511)?;
        if header.iter().all(|byte| *byte == 0) {
            break;
        }

        let entry_size = parse_tar_octal(&header[124..136])?;
        let data_offset = offset + 512;
        let typeflag = header[156];
        if typeflag == b'L' {
            pending_long_name = Some(read_tar_string(reader, data_offset, entry_size)?);
            offset = next_tar_offset(data_offset, entry_size)?;
            continue;
        }

        let name = pending_long_name
            .take()
            .unwrap_or_else(|| tar_entry_name(&header));
        if (typeflag == 0 || typeflag == b'0') && is_supported_image(&name, options) {
            pages.push(TarPageEntry {
                name,
                data_offset,
                size: entry_size,
            });
        }
        offset = next_tar_offset(data_offset, entry_size)?;
    }

    sort_pages_by_name(&mut pages, |page| &page.name);
    if pages.is_empty() {
        return Err(ComicCoreError::NoImages.into());
    }
    Ok(TarIndex { pages })
}

fn sort_pages_by_name<T>(pages: &mut [T], name: impl Fn(&T) -> &str) {
    pages.sort_by(|left, right| {
        natural::compare(page_file_name(name(left)), page_file_name(name(right)))
            .then_with(|| natural::compare(name(left), name(right)))
    });
}

fn page_file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn page_index_out_of_bounds() -> ComicCoreError {
    ComicCoreError::InvalidZip("page index out of bounds".to_string())
}

fn next_tar_offset(data_offset: u64, size: u64) -> Result<u64> {
    let padded_size = size
        .checked_add(511)
        .map(|value| value / 512 * 512)
        .ok_or_else(|| anyhow!("tar entry size overflow"))?;
    data_offset
        .checked_add(padded_size)
        .ok_or_else(|| anyhow!("tar entry offset overflow"))
}

fn parse_tar_octal(bytes: &[u8]) -> Result<u64> {
    let text = bytes
        .iter()
        .copied()
        .take_while(|byte| *byte != 0)
        .filter(|byte| *byte != b' ')
        .collect::<Vec<_>>();
    if text.is_empty() {
        return Ok(0);
    }
    let text = std::str::from_utf8(&text)?;
    Ok(u64::from_str_radix(text, 8)?)
}

#[cfg(test)]
mod tests {
    use super::{is_supported_image, ImageFormatOptions};

    #[test]
    fn supported_image_extensions_include_modern_reader_formats() {
        let options = ImageFormatOptions { avif: true };

        for name in [
            "page.jpg",
            "page.jpeg",
            "page.png",
            "page.webp",
            "page.gif",
            "page.bmp",
            "page.heif",
            "page.heic",
            "page.avif",
        ] {
            assert!(
                is_supported_image(name, options),
                "{name} should be supported"
            );
        }
    }

    #[test]
    fn avif_extension_is_controlled_by_reader_option() {
        assert!(!is_supported_image(
            "page.avif",
            ImageFormatOptions { avif: false },
        ));
        assert!(is_supported_image(
            "page.avif",
            ImageFormatOptions { avif: true },
        ));
    }
}

fn tar_entry_name(header: &[u8]) -> String {
    let name = tar_string_field(&header[0..100]);
    let prefix = tar_string_field(&header[345..500]);
    if prefix.is_empty() {
        name
    } else {
        format!("{prefix}/{name}")
    }
}

fn read_tar_string(reader: &impl RangeReader, offset: u64, size: u64) -> Result<String> {
    if size == 0 {
        return Ok(String::new());
    }
    let bytes = reader.read_range(offset, offset + size - 1)?;
    Ok(tar_string_field(&bytes))
}

fn tar_string_field(bytes: &[u8]) -> String {
    let end = bytes
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).trim().to_string()
}
