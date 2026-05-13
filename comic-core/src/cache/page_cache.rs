use anyhow::Result;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub fn page_cache_file(
    cache_dir: &Path,
    comic_key: &str,
    page_index: usize,
    extension: &str,
) -> Result<PathBuf> {
    let safe_extension = extension.trim_start_matches('.');
    let dir = cache_dir.join(safe_path_segment(comic_key)).join("pages");
    fs::create_dir_all(&dir)?;
    Ok(dir.join(format!("page-{page_index}.{safe_extension}")))
}

pub fn enforce_lru_capacity(cache_dir: &Path, capacity_bytes: u64) -> Result<usize> {
    let mut files = Vec::new();
    collect_files(cache_dir, &mut files)?;

    let mut total: u64 = files.iter().map(|file| file.size).sum();
    files.sort_by_key(|file| file.modified);

    let mut removed = 0usize;
    for file in files {
        if total <= capacity_bytes {
            break;
        }
        fs::remove_file(&file.path)?;
        total = total.saturating_sub(file.size);
        removed += 1;
    }
    Ok(removed)
}

fn collect_files(dir: &Path, files: &mut Vec<CachedFile>) -> Result<()> {
    if !dir.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let metadata = entry.metadata()?;
        if metadata.is_dir() {
            collect_files(&path, files)?;
        } else if metadata.is_file() {
            files.push(CachedFile {
                path,
                size: metadata.len(),
                modified: metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH),
            });
        }
    }
    Ok(())
}

#[derive(Debug)]
struct CachedFile {
    path: PathBuf,
    size: u64,
    modified: SystemTime,
}

fn safe_path_segment(value: &str) -> String {
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
                ch
            } else {
                '_'
            }
        })
        .collect()
}
