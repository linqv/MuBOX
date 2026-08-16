use std::sync::Mutex;

use thiserror::Error;

use crate::scheduler::range_planner::ByteRange;

pub(crate) const DEFAULT_MAX_CACHE_BYTES: u64 = 64 * 1024 * 1024;
pub(crate) const DEFAULT_SEGMENT_BYTES: usize = 4 * 1024 * 1024;

/// A thread-safe, byte-bounded cache of non-overlapping inclusive byte ranges.
///
/// Entries are kept in bounded segments so LRU eviction stays granular. Adjacent
/// segments are composed during lookup instead of being recopied when inserted.
/// `missing_ranges` is a point-in-time cache snapshot; an upper-level in-flight
/// coordinator must serialize its cache check and fetch registration when it needs
/// an atomic join-or-fetch decision.
pub(crate) struct RangeWindowCache {
    max_bytes: u64,
    segment_bytes: usize,
    state: Mutex<CacheState>,
}

impl Default for RangeWindowCache {
    fn default() -> Self {
        Self::new(DEFAULT_MAX_CACHE_BYTES)
    }
}

impl RangeWindowCache {
    pub(crate) fn new(max_bytes: u64) -> Self {
        Self {
            max_bytes,
            segment_bytes: DEFAULT_SEGMENT_BYTES,
            state: Mutex::new(CacheState::default()),
        }
    }

    #[cfg(test)]
    pub(crate) fn with_segment_bytes(
        max_bytes: u64,
        segment_bytes: usize,
    ) -> Result<Self, RangeCacheError> {
        if segment_bytes == 0 {
            return Err(RangeCacheError::ZeroSegmentBytes);
        }
        Ok(Self {
            max_bytes,
            segment_bytes,
            state: Mutex::new(CacheState::default()),
        })
    }

    /// Returns an owned copy of a fully covered range and refreshes every segment
    /// contributing to that range with the same LRU sequence number.
    pub(crate) fn lookup(
        &self,
        requested: ByteRange,
    ) -> Result<Option<LookupResult>, RangeCacheError> {
        let result_len = range_len_usize(requested)?;
        let mut state = self.lock_state();
        let Some(indices) = covering_segment_indices(&state.segments, requested) else {
            return Ok(None);
        };

        let mut bytes = Vec::new();
        bytes
            .try_reserve_exact(result_len)
            .map_err(|_| RangeCacheError::AllocationFailed { bytes: result_len })?;

        for &index in &indices {
            let segment = &state.segments[index];
            let copy_start = requested.start.max(segment.range.start);
            let copy_end = requested.end_inclusive.min(segment.range.end_inclusive);
            let source_start = usize_from_u64(copy_start - segment.range.start)?;
            let copy_len = range_len_usize(ByteRange::new(copy_start, copy_end))?;
            let source_end = source_start
                .checked_add(copy_len)
                .ok_or(RangeCacheError::ArithmeticOverflow)?;
            let source = segment
                .bytes
                .get(source_start..source_end)
                .ok_or(RangeCacheError::CorruptState)?;
            bytes.extend_from_slice(source);
        }
        if bytes.len() != result_len {
            return Err(RangeCacheError::CorruptState);
        }

        let last_access = state.next_sequence()?;
        for &index in &indices {
            state.segments[index].last_access = last_access;
        }
        let first = indices[0];
        let last = indices[indices.len() - 1];
        Ok(Some(LookupResult {
            bytes,
            window_start: state.segments[first].range.start,
            window_end_inclusive: state.segments[last].range.end_inclusive,
        }))
    }

    /// Checks full coverage without changing LRU order.
    pub(crate) fn is_covered(&self, requested: ByteRange) -> Result<bool, RangeCacheError> {
        validate_range(requested)?;
        let state = self.lock_state();
        Ok(covering_segment_indices(&state.segments, requested).is_some())
    }

    /// Returns sorted, non-overlapping inclusive gaps in `requested`.
    ///
    /// This does not change LRU order. Adjacent cached segments are treated as one
    /// continuous coverage window.
    #[allow(dead_code)] // Reserved for the upper-level gap-aware in-flight planner.
    pub(crate) fn missing_ranges(
        &self,
        requested: ByteRange,
    ) -> Result<Vec<ByteRange>, RangeCacheError> {
        validate_range(requested)?;
        let state = self.lock_state();
        missing_ranges_in(&state.segments, requested)
    }

    /// Atomically replaces overlapping bytes and evicts LRU segments as needed.
    ///
    /// Segments intersecting `protected_ranges` are ineligible for eviction. If
    /// they leave insufficient capacity, this returns `ProtectedCapacity` without
    /// mutating existing cache contents. Callers implementing Kotlin's high-priority
    /// fallback may retry with an empty protection slice.
    pub(crate) fn store(
        &self,
        range: ByteRange,
        bytes: &[u8],
        protected_ranges: &[ByteRange],
    ) -> Result<StoreResult, RangeCacheError> {
        let expected = range_len_usize(range)?;
        if expected != bytes.len() {
            return Err(RangeCacheError::ByteCountMismatch {
                expected,
                actual: bytes.len(),
            });
        }
        for protected in protected_ranges {
            validate_range(*protected)?;
        }

        let incoming_bytes = u64_from_usize(bytes.len())?;
        if incoming_bytes > self.max_bytes {
            return Ok(StoreResult::skipped(
                StoreSkipReason::Oversized,
                EvictionMode::None,
            ));
        }

        let mut state = self.lock_state();
        let mut pieces = retained_pieces(&state.segments, range)?;
        let retained_bytes = pieces.iter().try_fold(0_u64, |total, piece| {
            total
                .checked_add(piece.len)
                .ok_or(RangeCacheError::ArithmeticOverflow)
        })?;
        let mut projected_bytes = retained_bytes
            .checked_add(incoming_bytes)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;

        let eviction_mode = if protected_ranges.is_empty() {
            EvictionMode::Lru
        } else {
            EvictionMode::Protected
        };
        let mut candidate_indices = pieces
            .iter()
            .enumerate()
            .filter_map(|(index, piece)| (!piece.intersects_any(protected_ranges)).then_some(index))
            .collect::<Vec<_>>();
        candidate_indices.sort_by_key(|&index| {
            let piece = &pieces[index];
            (
                piece.last_access,
                piece.range.start,
                piece.range.end_inclusive,
            )
        });

        let mut evicted = Vec::new();
        for index in candidate_indices {
            if projected_bytes <= self.max_bytes {
                break;
            }
            let piece = &mut pieces[index];
            projected_bytes = projected_bytes
                .checked_sub(piece.len)
                .ok_or(RangeCacheError::ArithmeticOverflow)?;
            piece.evicted = true;
            evicted.push(piece.snapshot()?);
        }

        if projected_bytes > self.max_bytes {
            return Ok(StoreResult::skipped(
                StoreSkipReason::ProtectedCapacity,
                eviction_mode,
            ));
        }

        let last_access = state.next_sequence()?;
        let incoming_segments =
            split_into_segments(range.start, bytes, self.segment_bytes, last_access)?;
        let old_segments = std::mem::take(&mut state.segments);
        let mut old_segments = old_segments.into_iter().map(Some).collect::<Vec<_>>();
        let mut committed = Vec::with_capacity(
            pieces
                .iter()
                .filter(|piece| !piece.evicted)
                .count()
                .saturating_add(incoming_segments.len()),
        );
        for piece in pieces.into_iter().filter(|piece| !piece.evicted) {
            let original = old_segments
                .get_mut(piece.original_index)
                .and_then(Option::as_mut)
                .ok_or(RangeCacheError::CorruptState)?;
            if piece.range == original.range {
                let segment = old_segments[piece.original_index]
                    .take()
                    .ok_or(RangeCacheError::CorruptState)?;
                committed.push(segment);
            } else {
                committed.push(original.slice(piece.range)?);
            }
        }
        committed.extend(incoming_segments);
        committed.sort_by_key(|segment| segment.range.start);

        state.segments = committed;
        state.cached_bytes = projected_bytes;
        debug_assert!(state.invariants_hold(self.max_bytes));
        Ok(StoreResult {
            stored: true,
            skipped_reason: None,
            evicted,
            eviction_mode,
        })
    }

    pub(crate) fn store_unprotected(
        &self,
        range: ByteRange,
        bytes: &[u8],
    ) -> Result<StoreResult, RangeCacheError> {
        self.store(range, bytes, &[])
    }

    #[cfg(test)]
    pub(crate) fn window_count(&self) -> usize {
        self.lock_state().segments.len()
    }

    #[cfg(test)]
    pub(crate) fn total_bytes(&self) -> u64 {
        self.lock_state().cached_bytes
    }

    pub(crate) fn clear(&self) {
        let mut state = self.lock_state();
        state.segments.clear();
        state.cached_bytes = 0;
        state.sequence = 0;
    }

    fn lock_state(&self) -> std::sync::MutexGuard<'_, CacheState> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct LookupResult {
    pub(crate) bytes: Vec<u8>,
    pub(crate) window_start: u64,
    pub(crate) window_end_inclusive: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StoreSkipReason {
    Oversized,
    ProtectedCapacity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EvictionMode {
    None,
    Lru,
    Protected,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct StoreResult {
    pub(crate) stored: bool,
    pub(crate) skipped_reason: Option<StoreSkipReason>,
    pub(crate) evicted: Vec<WindowSnapshot>,
    pub(crate) eviction_mode: EvictionMode,
}

impl StoreResult {
    fn skipped(reason: StoreSkipReason, eviction_mode: EvictionMode) -> Self {
        Self {
            stored: false,
            skipped_reason: Some(reason),
            evicted: Vec::new(),
            eviction_mode,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WindowSnapshot {
    pub(crate) start: u64,
    pub(crate) end_inclusive: u64,
    pub(crate) bytes: usize,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub(crate) enum RangeCacheError {
    #[error("range end {end_inclusive} precedes start {start}")]
    InvalidRange { start: u64, end_inclusive: u64 },
    #[error("inclusive range length overflows u64")]
    RangeLengthOverflow,
    #[error("range length cannot be represented on this platform")]
    PlatformRangeTooLarge,
    #[error("range metadata expects {expected} bytes but received {actual}")]
    ByteCountMismatch { expected: usize, actual: usize },
    #[cfg(test)]
    #[error("segment bytes must be positive")]
    ZeroSegmentBytes,
    #[error("could not allocate {bytes} bytes for cache lookup")]
    AllocationFailed { bytes: usize },
    #[error("cache access sequence exhausted")]
    AccessSequenceExhausted,
    #[error("range cache arithmetic overflow")]
    ArithmeticOverflow,
    #[error("range cache invariant was violated")]
    CorruptState,
}

#[derive(Default)]
struct CacheState {
    segments: Vec<Segment>,
    cached_bytes: u64,
    sequence: u64,
}

impl CacheState {
    fn next_sequence(&mut self) -> Result<u64, RangeCacheError> {
        if let Some(next) = self.sequence.checked_add(1) {
            self.sequence = next;
            return Ok(next);
        }

        // Preserve relative LRU order and equal-age groups instead of making a
        // long-lived cache permanently unusable when its monotonic counter wraps.
        let mut ages = self
            .segments
            .iter()
            .map(|segment| segment.last_access)
            .collect::<Vec<_>>();
        ages.sort_unstable();
        ages.dedup();
        for segment in &mut self.segments {
            let rank = ages
                .binary_search(&segment.last_access)
                .map_err(|_| RangeCacheError::CorruptState)?;
            segment.last_access = u64::try_from(rank)
                .ok()
                .and_then(|rank| rank.checked_add(1))
                .ok_or(RangeCacheError::AccessSequenceExhausted)?;
        }
        self.sequence =
            u64::try_from(ages.len()).map_err(|_| RangeCacheError::AccessSequenceExhausted)?;
        self.sequence = self
            .sequence
            .checked_add(1)
            .ok_or(RangeCacheError::AccessSequenceExhausted)?;
        Ok(self.sequence)
    }

    fn invariants_hold(&self, max_bytes: u64) -> bool {
        let sorted_and_disjoint = self
            .segments
            .windows(2)
            .all(|pair| pair[0].range.end_inclusive < pair[1].range.start);
        let segment_ranges_match = self.segments.iter().all(|segment| {
            range_len_usize(segment.range)
                .map(|len| len == segment.bytes.len())
                .unwrap_or(false)
        });
        let total = self.segments.iter().try_fold(0_u64, |sum, segment| {
            u64::try_from(segment.bytes.len())
                .ok()
                .and_then(|len| sum.checked_add(len))
        });
        sorted_and_disjoint
            && segment_ranges_match
            && total == Some(self.cached_bytes)
            && self.cached_bytes <= max_bytes
    }
}

struct Segment {
    range: ByteRange,
    bytes: Vec<u8>,
    last_access: u64,
}

impl Segment {
    fn slice(&self, requested: ByteRange) -> Result<Self, RangeCacheError> {
        if requested.start < self.range.start || requested.end_inclusive > self.range.end_inclusive
        {
            return Err(RangeCacheError::CorruptState);
        }
        let from = usize_from_u64(requested.start - self.range.start)?;
        let len = range_len_usize(requested)?;
        let to = from
            .checked_add(len)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
        Ok(Self {
            range: requested,
            bytes: self.bytes[from..to].to_vec(),
            last_access: self.last_access,
        })
    }
}

struct RetainedPiece {
    original_index: usize,
    range: ByteRange,
    len: u64,
    last_access: u64,
    evicted: bool,
}

impl RetainedPiece {
    fn intersects_any(&self, ranges: &[ByteRange]) -> bool {
        ranges
            .iter()
            .any(|range| ranges_intersect(self.range, *range))
    }

    fn snapshot(&self) -> Result<WindowSnapshot, RangeCacheError> {
        Ok(WindowSnapshot {
            start: self.range.start,
            end_inclusive: self.range.end_inclusive,
            bytes: usize_from_u64(self.len)?,
        })
    }
}

fn covering_segment_indices(segments: &[Segment], requested: ByteRange) -> Option<Vec<usize>> {
    if requested.end_inclusive < requested.start {
        return None;
    }
    let mut cursor = requested.start;
    let mut covered_by = Vec::new();
    for (index, segment) in segments.iter().enumerate() {
        if segment.range.end_inclusive < cursor {
            continue;
        }
        if segment.range.start > cursor {
            return None;
        }
        covered_by.push(index);
        if segment.range.end_inclusive >= requested.end_inclusive {
            return Some(covered_by);
        }
        cursor = segment.range.end_inclusive.checked_add(1)?;
    }
    None
}

#[allow(dead_code)] // Called by the reserved public(crate) gap-planning API.
fn missing_ranges_in(
    segments: &[Segment],
    requested: ByteRange,
) -> Result<Vec<ByteRange>, RangeCacheError> {
    let mut cursor = requested.start;
    let mut missing = Vec::new();
    for segment in segments {
        if segment.range.end_inclusive < cursor {
            continue;
        }
        if segment.range.start > requested.end_inclusive {
            break;
        }
        if segment.range.start > cursor {
            let gap_end = segment
                .range
                .start
                .checked_sub(1)
                .ok_or(RangeCacheError::ArithmeticOverflow)?
                .min(requested.end_inclusive);
            if cursor <= gap_end {
                missing.push(ByteRange::new(cursor, gap_end));
            }
        }
        if segment.range.end_inclusive >= requested.end_inclusive {
            return Ok(missing);
        }
        cursor = segment
            .range
            .end_inclusive
            .checked_add(1)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
    }
    if cursor <= requested.end_inclusive {
        missing.push(ByteRange::new(cursor, requested.end_inclusive));
    }
    Ok(missing)
}

fn retained_pieces(
    segments: &[Segment],
    incoming: ByteRange,
) -> Result<Vec<RetainedPiece>, RangeCacheError> {
    let mut retained = Vec::new();
    for (original_index, segment) in segments.iter().enumerate() {
        if !ranges_intersect(segment.range, incoming) {
            retained.push(retained_piece(original_index, segment, segment.range)?);
            continue;
        }
        if segment.range.start < incoming.start {
            let end_inclusive = incoming
                .start
                .checked_sub(1)
                .ok_or(RangeCacheError::ArithmeticOverflow)?;
            retained.push(retained_piece(
                original_index,
                segment,
                ByteRange::new(segment.range.start, end_inclusive),
            )?);
        }
        if segment.range.end_inclusive > incoming.end_inclusive {
            let start = incoming
                .end_inclusive
                .checked_add(1)
                .ok_or(RangeCacheError::ArithmeticOverflow)?;
            retained.push(retained_piece(
                original_index,
                segment,
                ByteRange::new(start, segment.range.end_inclusive),
            )?);
        }
    }
    Ok(retained)
}

fn retained_piece(
    original_index: usize,
    segment: &Segment,
    range: ByteRange,
) -> Result<RetainedPiece, RangeCacheError> {
    Ok(RetainedPiece {
        original_index,
        range,
        len: range_len(range)?,
        last_access: segment.last_access,
        evicted: false,
    })
}

fn split_into_segments(
    start: u64,
    bytes: &[u8],
    segment_bytes: usize,
    last_access: u64,
) -> Result<Vec<Segment>, RangeCacheError> {
    let mut segments = Vec::with_capacity(bytes.len().div_ceil(segment_bytes));
    for (chunk_index, chunk) in bytes.chunks(segment_bytes).enumerate() {
        let offset = chunk_index
            .checked_mul(segment_bytes)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
        let segment_start = start
            .checked_add(u64_from_usize(offset)?)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
        let end_offset = u64_from_usize(chunk.len())?
            .checked_sub(1)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
        let end_inclusive = segment_start
            .checked_add(end_offset)
            .ok_or(RangeCacheError::ArithmeticOverflow)?;
        segments.push(Segment {
            range: ByteRange::new(segment_start, end_inclusive),
            bytes: chunk.to_vec(),
            last_access,
        });
    }
    Ok(segments)
}

fn ranges_intersect(first: ByteRange, second: ByteRange) -> bool {
    first.start <= second.end_inclusive && first.end_inclusive >= second.start
}

fn validate_range(range: ByteRange) -> Result<(), RangeCacheError> {
    if range.end_inclusive < range.start {
        return Err(RangeCacheError::InvalidRange {
            start: range.start,
            end_inclusive: range.end_inclusive,
        });
    }
    range_len(range).map(|_| ())
}

fn range_len(range: ByteRange) -> Result<u64, RangeCacheError> {
    if range.end_inclusive < range.start {
        return Err(RangeCacheError::InvalidRange {
            start: range.start,
            end_inclusive: range.end_inclusive,
        });
    }
    range
        .end_inclusive
        .checked_sub(range.start)
        .and_then(|difference| difference.checked_add(1))
        .ok_or(RangeCacheError::RangeLengthOverflow)
}

fn range_len_usize(range: ByteRange) -> Result<usize, RangeCacheError> {
    usize_from_u64(range_len(range)?)
}

fn usize_from_u64(value: u64) -> Result<usize, RangeCacheError> {
    usize::try_from(value).map_err(|_| RangeCacheError::PlatformRangeTooLarge)
}

fn u64_from_usize(value: usize) -> Result<u64, RangeCacheError> {
    u64::try_from(value).map_err(|_| RangeCacheError::ArithmeticOverflow)
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Barrier};
    use std::thread;

    use super::{EvictionMode, RangeCacheError, RangeWindowCache, StoreSkipReason, WindowSnapshot};
    use crate::scheduler::range_planner::ByteRange;

    fn range(start: u64, end_inclusive: u64) -> ByteRange {
        ByteRange::new(start, end_inclusive)
    }

    fn cache(max_bytes: u64, segment_bytes: usize) -> RangeWindowCache {
        RangeWindowCache::with_segment_bytes(max_bytes, segment_bytes).unwrap()
    }

    #[test]
    fn adjacent_segments_are_composed_for_the_requested_range() {
        let bytes = (0_u8..16).collect::<Vec<_>>();
        let cache = cache(64, 4);

        assert!(
            cache
                .store_unprotected(range(0, 7), &bytes[0..8])
                .unwrap()
                .stored
        );
        assert!(
            cache
                .store_unprotected(range(8, 15), &bytes[8..16])
                .unwrap()
                .stored
        );

        let lookup = cache.lookup(range(3, 12)).unwrap().unwrap();
        assert_eq!(4, cache.window_count());
        assert_eq!(0, lookup.window_start);
        assert_eq!(15, lookup.window_end_inclusive);
        assert_eq!(&bytes[3..13], lookup.bytes);
    }

    #[test]
    fn access_updates_lru_order_before_next_store() {
        let cache = cache(8, 4);
        cache.store_unprotected(range(0, 3), &[0, 1, 2, 3]).unwrap();
        cache.store_unprotected(range(4, 7), &[4, 5, 6, 7]).unwrap();

        assert_eq!(
            vec![0, 1, 2, 3],
            cache.lookup(range(0, 3)).unwrap().unwrap().bytes
        );
        let store = cache
            .store_unprotected(range(8, 11), &[8, 9, 10, 11])
            .unwrap();

        assert!(store.stored);
        assert_eq!(
            vec![WindowSnapshot {
                start: 4,
                end_inclusive: 7,
                bytes: 4,
            }],
            store.evicted
        );
        assert!(cache.is_covered(range(0, 3)).unwrap());
        assert!(!cache.is_covered(range(4, 7)).unwrap());
        assert!(cache.is_covered(range(8, 11)).unwrap());
    }

    #[test]
    fn all_segments_in_a_lookup_receive_the_same_lru_age() {
        let cache = cache(12, 4);
        cache.store_unprotected(range(0, 3), &[0; 4]).unwrap();
        cache.store_unprotected(range(4, 7), &[1; 4]).unwrap();
        cache.store_unprotected(range(8, 11), &[2; 4]).unwrap();

        cache.lookup(range(2, 5)).unwrap().unwrap();
        let result = cache.store_unprotected(range(12, 15), &[3; 4]).unwrap();

        assert_eq!(
            vec![WindowSnapshot {
                start: 8,
                end_inclusive: 11,
                bytes: 4,
            }],
            result.evicted
        );
    }

    #[test]
    fn access_sequence_rebases_without_changing_lru_order() {
        let cache = cache(8, 4);
        cache.store_unprotected(range(0, 3), &[0; 4]).unwrap();
        cache.store_unprotected(range(4, 7), &[1; 4]).unwrap();
        {
            let mut state = cache.lock_state();
            state.segments[0].last_access = 40;
            state.segments[1].last_access = 90;
            state.sequence = u64::MAX;
        }

        cache.lookup(range(4, 7)).unwrap().unwrap();
        let result = cache.store_unprotected(range(8, 11), &[2; 4]).unwrap();

        assert_eq!(
            vec![WindowSnapshot {
                start: 0,
                end_inclusive: 3,
                bytes: 4,
            }],
            result.evicted
        );
        assert!(cache.is_covered(range(4, 7)).unwrap());
        assert!(cache.is_covered(range(8, 11)).unwrap());
    }

    #[test]
    fn protected_range_is_not_selected_for_eviction() {
        let cache = cache(8, 4);
        cache.store_unprotected(range(0, 3), &[0; 4]).unwrap();
        cache.store_unprotected(range(4, 7), &[1; 4]).unwrap();

        let store = cache.store(range(8, 11), &[2; 4], &[range(0, 3)]).unwrap();

        assert!(store.stored);
        assert_eq!(EvictionMode::Protected, store.eviction_mode);
        assert_eq!(
            vec![WindowSnapshot {
                start: 4,
                end_inclusive: 7,
                bytes: 4,
            }],
            store.evicted
        );
        assert!(cache.is_covered(range(0, 3)).unwrap());
        assert!(!cache.is_covered(range(4, 7)).unwrap());
        assert!(cache.is_covered(range(8, 11)).unwrap());
    }

    #[test]
    fn protected_capacity_rejection_is_atomic() {
        let cache = cache(8, 4);
        cache.store_unprotected(range(0, 3), &[0; 4]).unwrap();
        cache.store_unprotected(range(4, 7), &[1; 4]).unwrap();

        let store = cache.store(range(8, 11), &[2; 4], &[range(0, 7)]).unwrap();

        assert!(!store.stored);
        assert_eq!(
            Some(StoreSkipReason::ProtectedCapacity),
            store.skipped_reason
        );
        assert_eq!(EvictionMode::Protected, store.eviction_mode);
        assert_eq!(8, cache.total_bytes());
        assert!(cache.is_covered(range(0, 7)).unwrap());
        assert!(!cache.is_covered(range(8, 11)).unwrap());
    }

    #[test]
    fn overlapping_store_replaces_only_requested_bytes() {
        let cache = cache(16, 4);
        let original = (0_u8..8).collect::<Vec<_>>();
        cache.store_unprotected(range(0, 7), &original).unwrap();

        cache
            .store_unprotected(range(2, 5), &[20, 21, 22, 23])
            .unwrap();

        assert_eq!(
            vec![0, 1, 20, 21, 22, 23, 6, 7],
            cache.lookup(range(0, 7)).unwrap().unwrap().bytes
        );
        assert_eq!(8, cache.total_bytes());
    }

    #[test]
    fn oversized_store_is_rejected_without_evicting_existing_data() {
        let cache = cache(4, 4);
        cache.store_unprotected(range(0, 3), &[0, 1, 2, 3]).unwrap();

        let store = cache
            .store_unprotected(range(4, 8), &[0, 0, 0, 0, 0])
            .unwrap();

        assert!(!store.stored);
        assert_eq!(Some(StoreSkipReason::Oversized), store.skipped_reason);
        assert_eq!(EvictionMode::None, store.eviction_mode);
        assert_eq!(
            vec![0, 1, 2, 3],
            cache.lookup(range(0, 3)).unwrap().unwrap().bytes
        );
        assert!(cache.lookup(range(4, 8)).unwrap().is_none());
    }

    #[test]
    fn missing_ranges_reports_only_closed_gaps() {
        let cache = cache(32, 4);
        cache.store_unprotected(range(2, 5), &[2; 4]).unwrap();
        cache.store_unprotected(range(8, 11), &[8; 4]).unwrap();
        cache.store_unprotected(range(12, 15), &[12; 4]).unwrap();

        assert_eq!(
            vec![range(0, 1), range(6, 7), range(16, 17)],
            cache.missing_ranges(range(0, 17)).unwrap()
        );
        assert_eq!(
            vec![range(6, 7)],
            cache.missing_ranges(range(3, 14)).unwrap()
        );
    }

    #[test]
    fn ranges_ending_at_u64_max_do_not_wrap() {
        let cache = cache(4, 2);
        let start = u64::MAX - 3;
        cache
            .store_unprotected(range(start + 2, u64::MAX), &[2, 3])
            .unwrap();

        assert_eq!(
            vec![range(start, start + 1)],
            cache.missing_ranges(range(start, u64::MAX)).unwrap()
        );
        assert_eq!(
            vec![2, 3],
            cache
                .lookup(range(start + 2, u64::MAX))
                .unwrap()
                .unwrap()
                .bytes
        );
    }

    #[test]
    fn invalid_metadata_is_rejected_without_mutation() {
        let cache = cache(8, 4);

        assert_eq!(
            Err(RangeCacheError::InvalidRange {
                start: 4,
                end_inclusive: 3,
            }),
            cache.store_unprotected(range(4, 3), &[])
        );
        assert_eq!(
            Err(RangeCacheError::ByteCountMismatch {
                expected: 4,
                actual: 3,
            }),
            cache.store_unprotected(range(0, 3), &[0; 3])
        );
        assert_eq!(0, cache.total_bytes());
    }

    #[test]
    fn zero_segment_size_is_rejected() {
        assert!(matches!(
            RangeWindowCache::with_segment_bytes(8, 0),
            Err(RangeCacheError::ZeroSegmentBytes)
        ));
    }

    #[test]
    fn concurrent_lookups_and_stores_preserve_capacity_and_coverage() {
        let cache = Arc::new(cache(256, 8));
        let barrier = Arc::new(Barrier::new(5));
        let writers = (0_u64..4)
            .map(|worker| {
                let cache = Arc::clone(&cache);
                let barrier = Arc::clone(&barrier);
                thread::spawn(move || {
                    barrier.wait();
                    for iteration in 0_u64..100 {
                        // Each writer owns eight slots. The cache itself remains shared,
                        // while the value assertion cannot be invalidated by a legitimate
                        // overwrite from another writer between store and lookup.
                        let slot = worker * 8 + iteration % 8;
                        let start = slot * 8;
                        let value = u8::try_from(slot).unwrap();
                        cache
                            .store_unprotected(range(start, start + 7), &[value; 8])
                            .unwrap();
                        let lookup = cache.lookup(range(start, start + 7)).unwrap().unwrap();
                        assert_eq!(vec![value; 8], lookup.bytes);
                    }
                })
            })
            .collect::<Vec<_>>();
        barrier.wait();
        for writer in writers {
            writer.join().unwrap();
        }

        assert!(cache.total_bytes() <= 256);
        assert!(cache.window_count() <= 32);
    }

    #[test]
    fn clear_drops_all_windows_and_resets_accounting() {
        let cache = cache(8, 4);
        cache.store_unprotected(range(0, 3), &[0; 4]).unwrap();

        cache.clear();

        assert_eq!(0, cache.window_count());
        assert_eq!(0, cache.total_bytes());
        assert_eq!(
            vec![range(0, 3)],
            cache.missing_ranges(range(0, 3)).unwrap()
        );
    }
}
