use anyhow::Result;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};

use crate::cbz::{open_cbz, CbzIndex};
use crate::zip::RangeReader;

pub const INDEX_CACHE_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexCacheKey {
    pub comic_key: String,
    pub file_size: u64,
    pub validator: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct CachedIndex {
    version: u32,
    comic_key: String,
    file_size: u64,
    validator: String,
    index: CbzIndex,
}

pub fn load_index_cache(cache_dir: &Path, key: &IndexCacheKey) -> Result<Option<CbzIndex>> {
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
    {
        Ok(Some(cached.index))
    } else {
        Ok(None)
    }
}

pub fn store_index_cache(cache_dir: &Path, key: &IndexCacheKey, index: &CbzIndex) -> Result<()> {
    let path = index_cache_file(cache_dir, &key.comic_key);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let cached = CachedIndex {
        version: INDEX_CACHE_VERSION,
        comic_key: key.comic_key.clone(),
        file_size: key.file_size,
        validator: key.validator.clone(),
        index: index.clone(),
    };
    fs::write(path, serde_json::to_vec(&cached)?)?;
    Ok(())
}

pub fn open_cbz_with_index_cache(
    reader: &impl RangeReader,
    cache_dir: &Path,
    key: &IndexCacheKey,
) -> Result<CbzIndex> {
    if let Some(index) = load_index_cache(cache_dir, key)? {
        return Ok(index);
    }
    let index = open_cbz(reader)?;
    store_index_cache(cache_dir, key, &index)?;
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
