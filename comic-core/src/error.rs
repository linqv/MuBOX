use thiserror::Error;

#[derive(Debug, Error)]
pub enum ComicCoreError {
    #[error("invalid zip: {0}")]
    InvalidZip(String),
    #[error("unsupported compression method: {0}")]
    UnsupportedCompression(u16),
    #[error("archive has no supported image entries")]
    NoImages,
    #[error("range out of bounds: {start}-{end_inclusive} for size {size}")]
    RangeOutOfBounds {
        start: u64,
        end_inclusive: u64,
        size: u64,
    },
}
