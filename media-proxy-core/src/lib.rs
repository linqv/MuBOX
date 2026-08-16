#![deny(unsafe_op_in_unsafe_fn)]

pub mod cache;
pub mod engine;
pub mod error;
pub mod http;
pub mod inflight;
pub mod jni_bridge;
pub mod transport;

pub const SEGMENT_BYTES: u64 = 2 * 1024 * 1024;
pub const SMALL_RANGE_DIRECT_BYTES: u64 = 256 * 1024;
pub const OPEN_ENDED_BYTES: u64 = 8 * 1024 * 1024;
pub const NATIVE_CHUNK_BYTES: usize = 256 * 1024;
pub const MAX_FORWARD_PREFETCH_CHUNKS: usize = 64;
