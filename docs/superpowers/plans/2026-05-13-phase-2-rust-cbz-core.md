# Phase 2 Rust CBZ ZIP Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse local `.cbz` and `.zip` files in Rust, naturally sort image entries, and extract Store and Deflate pages.

**Architecture:** All archive parsing is Rust-only and works through a `RangeReader` trait. Tests create small ZIP fixtures at runtime, so no binary fixtures are required for ordinary ZIP paths. ZIP64 is represented by a synthetic central-directory fixture before remote ZIP64 is enabled.

**Tech Stack:** Rust, anyhow, thiserror, flate2, tempfile, crc32fast.

---

## Files

- Modify: `/home/lin/webcomic/comic-core/Cargo.toml`
- Modify: `/home/lin/webcomic/comic-core/src/lib.rs`
- Create: `/home/lin/webcomic/comic-core/src/error.rs`
- Create: `/home/lin/webcomic/comic-core/src/zip/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/zip/eocd.rs`
- Create: `/home/lin/webcomic/comic-core/src/zip/central_directory.rs`
- Create: `/home/lin/webcomic/comic-core/src/zip/local_header.rs`
- Create: `/home/lin/webcomic/comic-core/src/zip/zip64.rs`
- Create: `/home/lin/webcomic/comic-core/src/cbz/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/cbz/index.rs`
- Create: `/home/lin/webcomic/comic-core/src/cbz/page.rs`
- Create: `/home/lin/webcomic/comic-core/src/sort/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/sort/natural.rs`
- Create: `/home/lin/webcomic/comic-core/tests/cbz_local.rs`

## Task 1: Define Core Types and Reader

- [ ] Add dependencies in `Cargo.toml`: `anyhow = "1"`, `thiserror = "2"`, `flate2 = "1"`, `tempfile = "3"`, `crc32fast = "1"`, `zip = { version = "2", default-features = false, features = ["deflate"] }`.
- [ ] Create `error.rs` with `ComicCoreError::{InvalidZip, UnsupportedCompression, NoImages, RangeOutOfBounds}`.
- [ ] Create `zip/mod.rs` with trait `RangeReader { fn size(&self) -> anyhow::Result<u64>; fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>>; }`.
- [ ] Implement `FileRangeReader` using `std::fs::File`, `Seek`, and `Read`.
- [ ] Add a unit test where reading range `1..=3` from bytes `0,1,2,3,4` returns `1,2,3`.

## Task 2: EOCD and Central Directory

- [ ] Write failing tests for EOCD lookup within the final 256 KiB and for missing EOCD.
- [ ] Implement `eocd.rs` scanning backwards for signature `0x06054b50`.
- [ ] Write central directory parser tests for one file entry with UTF-8 filename and expected compressed size.
- [ ] Implement `central_directory.rs` parsing signature `0x02014b50`, flags, method, crc32, compressed size, uncompressed size, filename, and local header offset.
- [ ] Run `cargo test eocd central_directory`.

## Task 3: Natural Sort and Image Filtering

- [ ] Write tests proving order `1.jpg`, `2.jpg`, `10.jpg`, `chapter/11.png`.
- [ ] Implement `sort/natural.rs` by tokenizing digit runs as numbers and non-digit runs case-insensitively.
- [ ] Implement image extension filter for `jpg`, `jpeg`, `png`, and `webp`.
- [ ] Run `cargo test natural`.

## Task 4: Local Header and Page Extraction

- [ ] Write tests that create Store and Deflate ZIP files with the `zip` test crate.
- [ ] Implement `local_header.rs` to compute `data_offset = local_header_offset + 30 + filename_len + extra_len`.
- [ ] Implement `cbz/index.rs` with `CbzIndex { pages: Vec<CbzPageEntry> }` and `open_cbz(reader)`.
- [ ] Implement `cbz/page.rs` to return original bytes for Store and inflate bytes for Deflate.
- [ ] Add tests for nested directories, Chinese filenames, empty archives, and archives with no images.
- [ ] Run `cargo test`.

## Task 5: ZIP64 Fixture Gate

- [ ] Add `zip64.rs` with parser functions for ZIP64 EOCD locator and record fields.
- [ ] Add a synthetic fixture test that maps `u32::MAX` central-directory size fields to ZIP64 values.
- [ ] Keep remote ZIP64 opening behind explicit parser success; unsupported ZIP64 must return `InvalidZip("zip64 metadata missing")`.
- [ ] Run `cargo test zip64`.

## Verification

- [ ] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [ ] Confirm `open_cbz` returns page count and naturally sorted image names.
- [ ] Commit: `feat: add local cbz zip core`.
