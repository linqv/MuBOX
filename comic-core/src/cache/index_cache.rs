use anyhow::{Result, anyhow};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use tempfile::NamedTempFile;

use crate::cbz::{CbzIndex, open_cbz_with_options};
use crate::image::ImageFormatOptions;
use crate::zip::RangeReader;

pub const INDEX_CACHE_VERSION: u32 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexCacheKey {
    pub comic_key: String,
    pub file_size: u64,
    pub validator: String,
}

#[derive(Debug, Deserialize)]
struct CachedIndex {
    version: u32,
    comic_key: String,
    file_size: u64,
    validator: String,
    #[serde(default)]
    avif: bool,
    index: CbzIndex,
}

#[derive(Debug, Serialize)]
struct CachedIndexRef<'a> {
    version: u32,
    comic_key: &'a str,
    file_size: u64,
    validator: &'a str,
    avif: bool,
    index: &'a CbzIndex,
}

pub fn load_index_cache(cache_dir: &Path, key: &IndexCacheKey) -> Result<Option<CbzIndex>> {
    load_index_cache_with_options(cache_dir, key, ImageFormatOptions::default())
}

pub fn load_index_cache_with_options(
    cache_dir: &Path,
    key: &IndexCacheKey,
    options: ImageFormatOptions,
) -> Result<Option<CbzIndex>> {
    let path = index_cache_file(cache_dir, &key.comic_key);
    if !path.is_file() {
        return Ok(None);
    }

    let bytes = fs::read(path)?;
    let cached: CachedIndex = serde_json::from_slice(&bytes)?;
    if cached.version == INDEX_CACHE_VERSION
        && cached.comic_key == key.comic_key
        && cached.file_size == key.file_size
        && cached.validator == key.validator
        && cached.avif == options.avif
    {
        Ok(Some(cached.index))
    } else {
        Ok(None)
    }
}

pub fn store_index_cache(cache_dir: &Path, key: &IndexCacheKey, index: &CbzIndex) -> Result<()> {
    store_index_cache_with_options(cache_dir, key, ImageFormatOptions::default(), index)
}

pub fn store_index_cache_with_options(
    cache_dir: &Path,
    key: &IndexCacheKey,
    options: ImageFormatOptions,
    index: &CbzIndex,
) -> Result<()> {
    let path = index_cache_file(cache_dir, &key.comic_key);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let cached = CachedIndexRef {
        version: INDEX_CACHE_VERSION,
        comic_key: &key.comic_key,
        file_size: key.file_size,
        validator: &key.validator,
        avif: options.avif,
        index,
    };
    atomic_write(&path, &serde_json::to_vec(&cached)?)?;
    Ok(())
}

pub fn open_cbz_with_index_cache(
    reader: &impl RangeReader,
    cache_dir: &Path,
    key: &IndexCacheKey,
) -> Result<CbzIndex> {
    open_cbz_with_index_cache_options(reader, cache_dir, key, ImageFormatOptions::default())
}

pub fn open_cbz_with_index_cache_options(
    reader: &impl RangeReader,
    cache_dir: &Path,
    key: &IndexCacheKey,
    options: ImageFormatOptions,
) -> Result<CbzIndex> {
    if let Some(index) = load_index_cache_with_options(cache_dir, key, options)? {
        return Ok(index);
    }
    let index = open_cbz_with_options(reader, options)?;
    store_index_cache_with_options(cache_dir, key, options, &index)?;
    Ok(index)
}

fn index_cache_file(cache_dir: &Path, comic_key: &str) -> PathBuf {
    cache_dir
        .join("index")
        .join(format!("{}.json", stable_hash(comic_key)))
}

fn stable_hash(value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow!("index cache path has no parent"))?;
    let mut file = NamedTempFile::new_in(parent)?;
    file.write_all(bytes)?;
    file.as_file().sync_all()?;
    file.persist(path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{IndexCacheKey, index_cache_file, load_index_cache, store_index_cache};
    use crate::cbz::{CbzIndex, CbzPageEntry};
    use std::fs;
    use std::sync::{Arc, Barrier};
    use std::thread;
    use tempfile::TempDir;

    #[test]
    fn old_index_cache_without_avif_field_is_ignored() {
        let temp = TempDir::new().unwrap();
        let key = IndexCacheKey {
            comic_key: "comic-a".to_string(),
            file_size: 123,
            validator: "etag-1".to_string(),
        };
        store_index_cache(temp.path(), &key, &sample_index()).unwrap();
        let path = index_cache_file(temp.path(), &key.comic_key);
        let old_json = fs::read_to_string(&path)
            .unwrap()
            .replace("\"version\":2", "\"version\":1")
            .replace(",\"avif\":false", "");
        fs::write(path, old_json).unwrap();

        let loaded = load_index_cache(temp.path(), &key).unwrap();

        assert_eq!(None, loaded);
    }

    #[test]
    fn concurrent_stores_publish_only_complete_index_files() {
        let temp = TempDir::new().unwrap();
        let key = IndexCacheKey {
            comic_key: "comic-a".to_string(),
            file_size: 123,
            validator: "etag-1".to_string(),
        };
        let first_index = sample_index();
        let mut second_index = sample_index();
        second_index.pages[0].name = "2.jpg".to_string();
        let barrier = Arc::new(Barrier::new(3));

        let writers = [first_index.clone(), second_index.clone()].map(|index| {
            let cache_dir = temp.path().to_path_buf();
            let key = key.clone();
            let barrier = Arc::clone(&barrier);
            thread::spawn(move || {
                barrier.wait();
                for _ in 0..20 {
                    store_index_cache(&cache_dir, &key, &index).unwrap();
                }
            })
        });
        barrier.wait();
        for writer in writers {
            writer.join().unwrap();
        }

        let loaded = load_index_cache(temp.path(), &key).unwrap().unwrap();
        assert!(loaded == first_index || loaded == second_index);
        let index_dir = index_cache_file(temp.path(), &key.comic_key)
            .parent()
            .unwrap()
            .to_path_buf();
        let entries = fs::read_dir(index_dir)
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(1, entries.len(), "temporary files must be cleaned up");
    }

    fn sample_index() -> CbzIndex {
        CbzIndex {
            pages: vec![CbzPageEntry {
                name: "1.jpg".to_string(),
                filename_len: 5,
                local_header_offset: 42,
                data_offset: Some(72),
                compressed_size: 10,
                uncompressed_size: 10,
                compression_method: 0,
                crc32: 7,
            }],
        }
    }
}
