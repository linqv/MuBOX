use anyhow::{Result, anyhow};
use sevenz_rust2::{ArchiveReader, Password};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use tempfile::{Builder as TempDirBuilder, NamedTempFile, TempDir};

#[cfg(unix)]
use std::os::fd::FromRawFd;

use crate::cbz::{CbzIndex, open_cbz_with_options};
use crate::error::ComicCoreError;
pub use crate::image::{ImageFormatOptions, is_supported_image};
use crate::sort::natural;
use crate::zip::{FileRangeReader, RangeReader};

const MAX_SEVEN_Z_PAGE_SIZE: u64 = 128 * 1024 * 1024;
const MAX_SOLID_SEVEN_Z_CACHE_SIZE: u64 = 1024 * 1024 * 1024;
const MAX_SEVEN_Z_PAGE_COUNT: usize = 100_000;

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
        reader: Box<ArchiveReader<File>>,
        pages: Vec<SevenZPageEntry>,
        solid_cache: Option<SolidSevenZCache>,
    },
    Tar {
        reader: FileRangeReader,
        index: TarIndex,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SevenZPageEntry {
    pub name: String,
    pub size: u64,
}

#[doc(hidden)]
pub struct SolidSevenZCache {
    directory: Option<TempDir>,
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

impl SolidSevenZCache {
    fn new() -> Self {
        Self { directory: None }
    }

    fn read_page(
        &mut self,
        reader: &mut ArchiveReader<File>,
        pages: &[SevenZPageEntry],
        page_index: usize,
        cache_parent: Option<&Path>,
    ) -> Result<Vec<u8>> {
        self.ensure_extracted(reader, pages, page_index, cache_parent)?;
        let page = pages.get(page_index).ok_or_else(page_index_out_of_bounds)?;
        let path = self.cached_page_path(page_index)?;
        let bytes = fs::read(&path)?;
        validate_extracted_seven_z_page(&bytes, page)?;
        Ok(bytes)
    }

    fn materialize_page(
        &mut self,
        reader: &mut ArchiveReader<File>,
        pages: &[SevenZPageEntry],
        page_index: usize,
        cache_parent: Option<&Path>,
        output_path: &Path,
    ) -> Result<()> {
        self.ensure_extracted(reader, pages, page_index, cache_parent)?;
        let cached_path = self.cached_page_path(page_index)?;
        let output_parent = output_path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        fs::create_dir_all(output_parent)?;
        let existing_output_is_valid = output_path
            .metadata()
            .is_ok_and(|metadata| metadata.is_file() && metadata.len() > 0);
        if fs::hard_link(&cached_path, output_path).is_ok() || existing_output_is_valid {
            return Ok(());
        }

        let mut input = File::open(&cached_path)?;
        let mut tmp_file = NamedTempFile::new_in(output_parent)?;
        io::copy(&mut input, tmp_file.as_file_mut())?;
        tmp_file.as_file_mut().flush()?;
        tmp_file.persist(output_path).map_err(|error| error.error)?;
        Ok(())
    }

    fn ensure_extracted(
        &mut self,
        reader: &mut ArchiveReader<File>,
        pages: &[SevenZPageEntry],
        requested_page_index: usize,
        cache_parent: Option<&Path>,
    ) -> Result<()> {
        pages
            .get(requested_page_index)
            .ok_or_else(page_index_out_of_bounds)?;
        if self.directory.is_some() {
            return Ok(());
        }

        let directory = create_solid_cache_directory(cache_parent)?;
        let pages_by_name: HashMap<&str, (usize, u64)> = pages
            .iter()
            .enumerate()
            .map(|(index, page)| (page.name.as_str(), (index, page.size)))
            .collect();
        let mut extracted = vec![false; pages.len()];
        let mut remaining_pages = pages.len();

        reader.for_each_entries(|entry, entry_reader| {
            if remaining_pages == 0 {
                return Ok(false);
            }
            let Some(&(page_index, expected_size)) = pages_by_name.get(entry.name()) else {
                io::copy(entry_reader, &mut io::sink())?;
                return Ok(true);
            };
            if !entry.has_stream() || entry.is_directory() {
                io::copy(entry_reader, &mut io::sink())?;
                return Ok(true);
            }

            let tmp_path = directory.path().join(format!("page-{page_index}.tmp"));
            let final_path = directory.path().join(format!("page-{page_index}.bin"));
            let mut output = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&tmp_path)?;
            let copied = io::copy(
                &mut entry_reader.take(expected_size.saturating_add(1)),
                &mut output,
            )?;
            output.flush()?;
            drop(output);
            if copied != expected_size {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!(
                        "7z page size mismatch for {}: expected {expected_size}, got {copied}",
                        entry.name()
                    ),
                )
                .into());
            }
            fs::rename(tmp_path, final_path)?;
            if !extracted[page_index] {
                extracted[page_index] = true;
                remaining_pages -= 1;
            }
            Ok(remaining_pages > 0)
        })?;

        if let Some((missing_index, _)) = extracted.iter().enumerate().find(|(_, found)| !**found) {
            return Err(anyhow!(
                "solid 7z extraction did not produce page {}",
                pages[missing_index].name
            ));
        }
        self.directory = Some(directory);
        Ok(())
    }

    fn cached_page_path(&self, page_index: usize) -> Result<PathBuf> {
        let directory = self
            .directory
            .as_ref()
            .ok_or_else(|| anyhow!("solid 7z cache is not initialized"))?;
        let path = directory.path().join(format!("page-{page_index}.bin"));
        if !path.is_file() {
            return Err(anyhow!("solid 7z cached page is missing"));
        }
        Ok(path)
    }
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
            LocalArchiveSession::Zip { reader, index } => index
                .extract_page(reader, page_index)
                .map(|page| page.bytes),
            LocalArchiveSession::SevenZ {
                reader,
                pages,
                solid_cache,
            } => {
                let page = pages.get(page_index).ok_or_else(page_index_out_of_bounds)?;
                if let Some(cache) = solid_cache {
                    return cache.read_page(reader, pages, page_index, None);
                }
                let bytes = reader.read_file(&page.name)?;
                validate_extracted_seven_z_page(&bytes, page)?;
                Ok(bytes)
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

    pub(crate) fn materialize_solid_page_to_file(
        &mut self,
        page_index: usize,
        output_path: &Path,
    ) -> Result<bool> {
        let LocalArchiveSession::SevenZ {
            reader,
            pages,
            solid_cache: Some(cache),
        } = self
        else {
            return Ok(false);
        };
        let cache_parent = solid_cache_parent_for_output(output_path);
        cache.materialize_page(reader, pages, page_index, cache_parent, output_path)?;
        Ok(true)
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
            let mut reader = ArchiveReader::new(file, Password::empty())?;
            // A page-cache warmup is sequential I/O. Keeping the decoder single-threaded avoids
            // multiplying its dictionary/work buffers on memory-constrained Android devices.
            reader.set_thread_count(1);
            let is_solid = reader.archive().is_solid;
            let mut pages: Vec<SevenZPageEntry> = reader
                .archive()
                .files
                .iter()
                .filter(|entry| entry.has_stream() && !entry.is_directory())
                .filter(|entry| is_supported_image(entry.name(), options))
                .map(|entry| SevenZPageEntry {
                    name: entry.name().to_string(),
                    size: entry.size(),
                })
                .collect();
            sort_pages_by_name(&mut pages, |page| &page.name);
            if pages.is_empty() {
                return Err(ComicCoreError::NoImages.into());
            }
            validate_seven_z_pages(&pages, is_solid)?;
            Ok(LocalArchiveSession::SevenZ {
                reader: Box::new(reader),
                pages,
                solid_cache: is_solid.then(SolidSevenZCache::new),
            })
        }
        ArchiveFormat::Tar => {
            let reader = FileRangeReader::from_file(file, size_hint)?;
            let index = open_tar_with_options(&reader, options)?;
            Ok(LocalArchiveSession::Tar { reader, index })
        }
    }
}

fn validate_seven_z_pages(pages: &[SevenZPageEntry], is_solid: bool) -> Result<()> {
    if pages.len() > MAX_SEVEN_Z_PAGE_COUNT {
        return Err(anyhow!(
            "7z page count {} exceeds limit {}",
            pages.len(),
            MAX_SEVEN_Z_PAGE_COUNT
        ));
    }
    let mut names = HashSet::with_capacity(pages.len());
    let mut total_size = 0u64;
    for page in pages {
        if page.size > MAX_SEVEN_Z_PAGE_SIZE {
            return Err(anyhow!(
                "7z page {} size {} exceeds limit {}",
                page.name,
                page.size,
                MAX_SEVEN_Z_PAGE_SIZE
            ));
        }
        if !names.insert(page.name.as_str()) {
            return Err(anyhow!("7z contains duplicate image entry {}", page.name));
        }
        total_size = total_size
            .checked_add(page.size)
            .ok_or_else(|| anyhow!("7z page size total overflow"))?;
    }
    if is_solid && total_size > MAX_SOLID_SEVEN_Z_CACHE_SIZE {
        return Err(anyhow!(
            "solid 7z page cache size {total_size} exceeds limit {MAX_SOLID_SEVEN_Z_CACHE_SIZE}"
        ));
    }
    Ok(())
}

fn validate_extracted_seven_z_page(bytes: &[u8], page: &SevenZPageEntry) -> Result<()> {
    if bytes.len() as u64 != page.size {
        return Err(anyhow!(
            "7z page size mismatch for {}: expected {}, got {}",
            page.name,
            page.size,
            bytes.len()
        ));
    }
    Ok(())
}

fn create_solid_cache_directory(cache_parent: Option<&Path>) -> Result<TempDir> {
    let mut builder = TempDirBuilder::new();
    builder.prefix(".mubox-solid7z-");
    match cache_parent.filter(|path| !path.as_os_str().is_empty()) {
        Some(parent) => {
            fs::create_dir_all(parent)?;
            Ok(builder.tempdir_in(parent)?)
        }
        None => Ok(builder.tempdir()?),
    }
}

fn solid_cache_parent_for_output(output_path: &Path) -> Option<&Path> {
    output_path
        .ancestors()
        .find(|path| {
            path.file_name()
                .is_some_and(|name| name == "comicdav-pages" || name == "comicdav-pages-transient")
        })
        .and_then(Path::parent)
        .or_else(|| output_path.parent())
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use tempfile::{NamedTempFile, TempDir};

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

    #[test]
    fn solid_page_cache_rejects_oversized_page_and_total() {
        let oversized_page = vec![SevenZPageEntry {
            name: "page.jpg".to_string(),
            size: MAX_SEVEN_Z_PAGE_SIZE + 1,
        }];
        let error = validate_seven_z_pages(&oversized_page, true)
            .unwrap_err()
            .to_string();
        assert!(error.contains("exceeds limit"), "unexpected error: {error}");

        let oversized_total: Vec<_> = (0..9)
            .map(|index| SevenZPageEntry {
                name: format!("page-{index}.jpg"),
                size: MAX_SEVEN_Z_PAGE_SIZE,
            })
            .collect();
        let error = validate_seven_z_pages(&oversized_total, true)
            .unwrap_err()
            .to_string();
        assert!(
            error.contains("cache size") && error.contains("exceeds limit"),
            "unexpected error: {error}"
        );
        assert!(validate_seven_z_pages(&oversized_total, false).is_ok());
    }

    #[test]
    fn materialized_solid_cache_is_owned_and_cleaned_by_session() {
        let archive = make_solid_7z(&[("1.jpg", b"one"), ("2.jpg", b"two")]);
        let output_root = TempDir::new().unwrap();
        let output_path = output_root
            .path()
            .join("comicdav-pages/test-key/page-0.img");
        let mut session = open_local_archive(archive.path(), ArchiveFormat::SevenZ).unwrap();

        assert!(
            session
                .materialize_solid_page_to_file(0, &output_path)
                .unwrap()
        );
        assert_eq!(b"one", fs::read(&output_path).unwrap().as_slice());
        let cache_path = match &session {
            LocalArchiveSession::SevenZ {
                solid_cache: Some(cache),
                ..
            } => cache.directory.as_ref().unwrap().path().to_path_buf(),
            _ => panic!("expected a solid 7z cache"),
        };
        assert!(cache_path.is_dir());
        assert_eq!(cache_path.parent(), Some(output_root.path()));
        assert!(!cache_path.starts_with(output_root.path().join("comicdav-pages")));

        drop(session);

        assert!(!cache_path.exists());
        assert_eq!(b"one", fs::read(output_path).unwrap().as_slice());
    }

    fn make_solid_7z(entries: &[(&str, &[u8])]) -> NamedTempFile {
        let archive = NamedTempFile::new().unwrap();
        let mut writer = sevenz_rust2::ArchiveWriter::create(archive.path()).unwrap();
        let archive_entries = entries
            .iter()
            .map(|(name, _)| sevenz_rust2::ArchiveEntry::new_file(name))
            .collect();
        let readers = entries
            .iter()
            .map(|(_, bytes)| sevenz_rust2::SourceReader::new(Cursor::new(bytes.to_vec())))
            .collect();
        writer
            .push_archive_entries(archive_entries, readers)
            .unwrap();
        writer.finish().unwrap();
        archive
    }
}
