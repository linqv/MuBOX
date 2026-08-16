use std::fmt::Display;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use thiserror::Error;

use super::range_cache::{
    DEFAULT_MAX_CACHE_BYTES, RangeCacheError, RangeWindowCache, StoreSkipReason,
};
use crate::scheduler::range_planner::ByteRange;
use crate::zip::RangeReader;

/// Forward expansion applied to demand reads on unmetered networks.
///
/// Read-ahead is disabled by default so that metadata reads (the archive index at
/// open time) fetch exact ranges; the session owner enables it once the effective
/// network class is known.
pub(crate) const WIFI_DEMAND_READ_AHEAD_BYTES: u64 = 4 * 1024 * 1024;
pub(crate) const PROTECTED_FALLBACK_MAX_PRIORITY: u8 = 2;

/// Blocking transport used by a remote range session.
///
/// `fetch` must return exactly the requested inclusive range. The session validates
/// the response before publishing it. `cancel` is best effort and may return before
/// a transport callback has unwound; the session's cancellation epoch prevents such
/// a late response from entering the cache.
pub(crate) trait RangeTransport: Send + Sync {
    type Error: Display + Send + Sync;

    fn fetch(&self, request_id: u64, range: ByteRange) -> Result<Vec<u8>, Self::Error>;

    fn cancel(&self, request_id: u64);
}

/// Session-owned remote range cache and covering-only in-flight coordinator.
///
/// The session intentionally remains blocking: callers can place it behind JNI or
/// another transport adapter without leaking an Android coroutine model into Rust.
/// Multiple callers requesting a range fully covered by an existing fetch join it;
/// partial overlaps remain independent fetches.
pub(crate) struct RemoteRangeSession<T> {
    transport: T,
    file_size: u64,
    read_ahead_bytes: AtomicU64,
    cache: RangeWindowCache,
    state: Mutex<SessionState>,
}

impl<T: RangeTransport> RemoteRangeSession<T> {
    pub(crate) fn new(file_size: u64, transport: T) -> Self {
        Self {
            transport,
            file_size,
            read_ahead_bytes: AtomicU64::new(0),
            cache: RangeWindowCache::new(DEFAULT_MAX_CACHE_BYTES),
            state: Mutex::new(SessionState::default()),
        }
    }

    #[cfg(test)]
    pub(crate) fn with_limits(
        file_size: u64,
        transport: T,
        max_cache_bytes: u64,
        segment_bytes: usize,
        read_ahead_bytes: u64,
    ) -> Result<Self, RangeSessionError> {
        Ok(Self {
            transport,
            file_size,
            read_ahead_bytes: AtomicU64::new(read_ahead_bytes),
            cache: RangeWindowCache::with_segment_bytes(max_cache_bytes, segment_bytes)
                .map_err(RangeSessionError::from_cache)?,
            state: Mutex::new(SessionState::default()),
        })
    }

    /// Configures the forward expansion applied to demand reads.
    ///
    /// Disabled by default so metadata reads (e.g. the archive index at open time)
    /// fetch exact ranges; the session owner raises this once the effective network
    /// class is known.
    pub(crate) fn set_read_ahead_bytes(&self, bytes: u64) {
        self.read_ahead_bytes.store(bytes, Ordering::Release);
    }

    pub(crate) fn read_range(&self, requested: ByteRange) -> Result<Vec<u8>, RangeSessionError> {
        self.validate_file_range(requested)?;
        let expanded = ByteRange::new(
            requested.start,
            requested
                .end_inclusive
                .saturating_add(self.read_ahead_bytes.load(Ordering::Acquire))
                .min(self.file_size - 1),
        );

        let mut may_retry_failed_prefetch = true;
        loop {
            match self.read_decision(requested, expanded)? {
                ReadDecision::Cached(bytes) => return Ok(bytes),
                ReadDecision::Join(flight) => match flight.await_slice(requested) {
                    Ok(bytes) => return Ok(bytes),
                    Err(error)
                        if flight.owner == InFlightOwner::Prefetch
                            && may_retry_failed_prefetch
                            && self.can_retry_failed_prefetch(&error) =>
                    {
                        may_retry_failed_prefetch = false;
                    }
                    Err(error) => return Err(error),
                },
                ReadDecision::Fetch(flight) => {
                    let published = self.fetch_and_publish_demand(&flight, requested)?;
                    return slice_response(&published.bytes, flight.range, requested);
                }
            }
        }
    }

    pub(crate) fn read_cached_range(
        &self,
        requested: ByteRange,
    ) -> Result<Option<Vec<u8>>, RangeSessionError> {
        self.validate_file_range(requested)?;
        let state = self.lock_state();
        state.check_open()?;
        Ok(self
            .cache
            .lookup(requested)
            .map_err(RangeSessionError::from_cache)?
            .map(|lookup| lookup.bytes))
    }

    pub(crate) fn is_range_cached(&self, requested: ByteRange) -> Result<bool, RangeSessionError> {
        self.validate_file_range(requested)?;
        let state = self.lock_state();
        state.check_open()?;
        self.cache
            .is_covered(requested)
            .map_err(RangeSessionError::from_cache)
    }

    pub(crate) fn prefetch(
        &self,
        requested: ByteRange,
        priority: u8,
        protected_ranges: &[ByteRange],
    ) -> Result<bool, RangeSessionError> {
        if requested.start >= self.file_size {
            return Ok(false);
        }
        validate_range(requested)?;
        let requested = ByteRange::new(
            requested.start,
            requested.end_inclusive.min(self.file_size - 1),
        );
        let protected_ranges = normalize_ranges(protected_ranges)?;

        match self.prefetch_decision(requested, &protected_ranges)? {
            PrefetchDecision::Cached => Ok(true),
            PrefetchDecision::Join(flight) => {
                flight.await_slice(requested)?;
                self.is_range_cached(requested)
            }
            PrefetchDecision::Fetch(flight) => {
                let published = self.fetch_and_publish_prefetch(
                    &flight,
                    priority,
                    protected_ranges.as_slice(),
                )?;
                Ok(published.stored)
            }
        }
    }

    /// Cancels all current demand and prefetch requests while keeping cached data.
    pub(crate) fn cancel(&self) -> Result<(), RangeSessionError> {
        self.cancel_internal(false)
    }

    /// Prevents future operations, cancels current requests, and releases the cache.
    pub(crate) fn close(&self) -> Result<(), RangeSessionError> {
        let result = self.cancel_internal(true);
        self.cache.clear();
        result
    }

    #[cfg(test)]
    pub(crate) fn cached_bytes(&self) -> u64 {
        self.cache.total_bytes()
    }

    fn can_retry_failed_prefetch(&self, error: &RangeSessionError) -> bool {
        error.retryable_after_prefetch_failure() && !self.lock_state().closed
    }

    fn read_decision(
        &self,
        requested: ByteRange,
        expanded: ByteRange,
    ) -> Result<ReadDecision, RangeSessionError> {
        let mut state = self.lock_state();
        state.check_open()?;
        if let Some(cached) = self
            .cache
            .lookup(requested)
            .map_err(RangeSessionError::from_cache)?
        {
            return Ok(ReadDecision::Cached(cached.bytes));
        }
        if let Some(flight) = state.covering_flight(requested) {
            return Ok(ReadDecision::Join(flight));
        }
        Ok(ReadDecision::Fetch(
            state.register(expanded, InFlightOwner::Demand)?,
        ))
    }

    fn prefetch_decision(
        &self,
        requested: ByteRange,
        protected_ranges: &[ByteRange],
    ) -> Result<PrefetchDecision, RangeSessionError> {
        let mut state = self.lock_state();
        state.check_open()?;
        if !protected_ranges.is_empty() {
            state.latest_protected_ranges = protected_ranges.to_vec();
        }
        if self
            .cache
            .lookup(requested)
            .map_err(RangeSessionError::from_cache)?
            .is_some()
        {
            return Ok(PrefetchDecision::Cached);
        }
        if let Some(flight) = state.covering_flight(requested) {
            return Ok(PrefetchDecision::Join(flight));
        }
        Ok(PrefetchDecision::Fetch(
            state.register(requested, InFlightOwner::Prefetch)?,
        ))
    }

    fn fetch_and_publish_demand(
        &self,
        flight: &Arc<InFlight>,
        requested: ByteRange,
    ) -> Result<PublishedFetch, RangeSessionError> {
        let response = self.fetch_exact(flight);
        self.publish_fetch(flight, response, |state, bytes| {
            self.store_demand_response(
                flight.range,
                requested,
                bytes,
                &state.latest_protected_ranges,
            )
        })
    }

    fn fetch_and_publish_prefetch(
        &self,
        flight: &Arc<InFlight>,
        priority: u8,
        protected_ranges: &[ByteRange],
    ) -> Result<PublishedFetch, RangeSessionError> {
        let response = self.fetch_exact(flight);
        self.publish_fetch(flight, response, |_state, bytes| {
            let protected_result = self
                .cache
                .store(flight.range, bytes, protected_ranges)
                .map_err(RangeSessionError::from_cache)?;
            if priority <= PROTECTED_FALLBACK_MAX_PRIORITY
                && protected_result.skipped_reason == Some(StoreSkipReason::ProtectedCapacity)
            {
                return self
                    .cache
                    .store_unprotected(flight.range, bytes)
                    .map(|result| result.stored)
                    .map_err(RangeSessionError::from_cache);
            }
            Ok(protected_result.stored)
        })
    }

    fn fetch_exact(&self, flight: &Arc<InFlight>) -> Result<Vec<u8>, RangeSessionError> {
        if !flight.begin_transport() {
            return Err(RangeSessionError::Cancelled);
        }
        let bytes = self
            .transport
            .fetch(flight.request_id, flight.range)
            .map_err(|error| RangeSessionError::Transport(error.to_string()))?;
        let expected = range_len_usize(flight.range)?;
        if bytes.len() != expected {
            return Err(RangeSessionError::InvalidResponseLength {
                start: flight.range.start,
                end_inclusive: flight.range.end_inclusive,
                expected,
                actual: bytes.len(),
            });
        }
        Ok(bytes)
    }

    fn publish_fetch<F>(
        &self,
        flight: &Arc<InFlight>,
        response: Result<Vec<u8>, RangeSessionError>,
        store: F,
    ) -> Result<PublishedFetch, RangeSessionError>
    where
        F: FnOnce(&SessionState, &[u8]) -> Result<bool, RangeSessionError>,
    {
        let mut state = self.lock_state();
        if state.closed || state.cancel_epoch != flight.cancel_epoch || !flight.is_pending() {
            state.remove_flight(flight);
            flight.publish(Err(RangeSessionError::Cancelled));
            return Err(RangeSessionError::Cancelled);
        }

        let published = match response {
            Ok(bytes) => match store(&state, &bytes) {
                Ok(stored) => Ok(PublishedFetch {
                    // Keep the transport Vec allocation for in-flight consumers;
                    // Arc<Vec<_>> does not recopy the byte buffer.
                    bytes: Arc::new(bytes),
                    stored,
                }),
                Err(error) => Err(error),
            },
            Err(error) => Err(error),
        };
        state.remove_flight(flight);
        flight.publish(
            published
                .as_ref()
                .map(|fetch| Arc::clone(&fetch.bytes))
                .map_err(Clone::clone),
        );
        published
    }

    fn store_demand_response(
        &self,
        fetched: ByteRange,
        requested: ByteRange,
        bytes: &[u8],
        protected_ranges: &[ByteRange],
    ) -> Result<bool, RangeSessionError> {
        let has_read_ahead = fetched.end_inclusive > requested.end_inclusive;
        let expanded_result = self
            .cache
            .store(
                fetched,
                bytes,
                if has_read_ahead {
                    protected_ranges
                } else {
                    &[]
                },
            )
            .map_err(RangeSessionError::from_cache)?;
        if !has_read_ahead || expanded_result.stored {
            return Ok(expanded_result.stored);
        }

        let requested_len = range_len_usize(requested)?;
        let requested_bytes = bytes
            .get(..requested_len)
            .ok_or(RangeSessionError::CorruptState)?;
        let requested_result = self
            .cache
            .store(requested, requested_bytes, protected_ranges)
            .map_err(RangeSessionError::from_cache)?;
        if requested_result.stored {
            return Ok(true);
        }
        if requested_result.skipped_reason == Some(StoreSkipReason::ProtectedCapacity) {
            return self
                .cache
                .store_unprotected(requested, requested_bytes)
                .map(|result| result.stored)
                .map_err(RangeSessionError::from_cache);
        }
        Ok(false)
    }

    fn cancel_internal(&self, close: bool) -> Result<(), RangeSessionError> {
        let flights = {
            let mut state = self.lock_state();
            if state.closed {
                return Ok(());
            }
            // All flights from the prior epoch are removed below. Resetting after
            // checked overflow is safe because an old flight also carries a
            // completed cancellation outcome, which independently blocks publish.
            state.cancel_epoch = state.cancel_epoch.checked_add(1).unwrap_or(0);
            if close {
                state.closed = true;
            }
            std::mem::take(&mut state.in_flight)
        };

        let started_request_ids = flights
            .iter()
            .filter_map(|flight| flight.cancel().then_some(flight.request_id))
            .collect::<Vec<_>>();
        for request_id in started_request_ids {
            self.transport.cancel(request_id);
        }
        Ok(())
    }

    fn validate_file_range(&self, range: ByteRange) -> Result<(), RangeSessionError> {
        validate_range(range)?;
        if range.end_inclusive >= self.file_size {
            return Err(RangeSessionError::RangeOutOfBounds {
                start: range.start,
                end_inclusive: range.end_inclusive,
                size: self.file_size,
            });
        }
        Ok(())
    }

    fn lock_state(&self) -> std::sync::MutexGuard<'_, SessionState> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl<T: RangeTransport> RangeReader for RemoteRangeSession<T> {
    fn size(&self) -> anyhow::Result<u64> {
        Ok(self.file_size)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Vec<u8>> {
        RemoteRangeSession::read_range(self, ByteRange::new(start, end_inclusive))
            .map_err(Into::into)
    }

    fn read_cached_range(&self, start: u64, end_inclusive: u64) -> anyhow::Result<Option<Vec<u8>>> {
        RemoteRangeSession::read_cached_range(self, ByteRange::new(start, end_inclusive))
            .map_err(Into::into)
    }
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub(crate) enum RangeSessionError {
    #[error("range end {end_inclusive} precedes start {start}")]
    InvalidRange { start: u64, end_inclusive: u64 },
    #[error("range out of bounds: {start}-{end_inclusive} for size {size}")]
    RangeOutOfBounds {
        start: u64,
        end_inclusive: u64,
        size: u64,
    },
    #[error("inclusive range length overflows u64")]
    RangeLengthOverflow,
    #[error("range length cannot be represented on this platform")]
    PlatformRangeTooLarge,
    #[error(
        "invalid range response length for {start}-{end_inclusive}: expected={expected} actual={actual}"
    )]
    InvalidResponseLength {
        start: u64,
        end_inclusive: u64,
        expected: usize,
        actual: usize,
    },
    #[error("range transport failed: {0}")]
    Transport(String),
    #[error("range request cancelled")]
    Cancelled,
    #[error("remote range session closed")]
    Closed,
    #[error("range request id exhausted")]
    RequestIdExhausted,
    #[error("range cache failed: {0}")]
    Cache(String),
    #[error("remote range session invariant was violated")]
    CorruptState,
}

impl RangeSessionError {
    fn from_cache(error: RangeCacheError) -> Self {
        match error {
            RangeCacheError::InvalidRange {
                start,
                end_inclusive,
            } => Self::InvalidRange {
                start,
                end_inclusive,
            },
            RangeCacheError::RangeLengthOverflow => Self::RangeLengthOverflow,
            RangeCacheError::PlatformRangeTooLarge => Self::PlatformRangeTooLarge,
            other => Self::Cache(other.to_string()),
        }
    }

    fn retryable_after_prefetch_failure(&self) -> bool {
        matches!(
            self,
            Self::Transport(_)
                | Self::InvalidResponseLength { .. }
                | Self::Cancelled
                | Self::Cache(_)
                | Self::CorruptState
        )
    }
}

#[derive(Default)]
struct SessionState {
    in_flight: Vec<Arc<InFlight>>,
    latest_protected_ranges: Vec<ByteRange>,
    cancel_epoch: u64,
    next_request_id: u64,
    closed: bool,
}

impl SessionState {
    fn check_open(&self) -> Result<(), RangeSessionError> {
        if self.closed {
            Err(RangeSessionError::Closed)
        } else {
            Ok(())
        }
    }

    fn covering_flight(&self, requested: ByteRange) -> Option<Arc<InFlight>> {
        self.in_flight
            .iter()
            .find(|flight| range_covers(flight.range, requested))
            .map(Arc::clone)
    }

    fn register(
        &mut self,
        range: ByteRange,
        owner: InFlightOwner,
    ) -> Result<Arc<InFlight>, RangeSessionError> {
        self.next_request_id = self
            .next_request_id
            .checked_add(1)
            .ok_or(RangeSessionError::RequestIdExhausted)?;
        let flight = Arc::new(InFlight {
            request_id: self.next_request_id,
            range,
            owner,
            cancel_epoch: self.cancel_epoch,
            outcome: Mutex::new(FlightOutcome::Pending {
                transport_started: false,
            }),
            ready: Condvar::new(),
            #[cfg(test)]
            waiter_count: std::sync::atomic::AtomicUsize::new(0),
        });
        self.in_flight.push(Arc::clone(&flight));
        Ok(flight)
    }

    fn remove_flight(&mut self, target: &Arc<InFlight>) {
        self.in_flight.retain(|flight| !Arc::ptr_eq(flight, target));
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InFlightOwner {
    Demand,
    Prefetch,
}

struct InFlight {
    request_id: u64,
    range: ByteRange,
    owner: InFlightOwner,
    cancel_epoch: u64,
    outcome: Mutex<FlightOutcome>,
    ready: Condvar,
    #[cfg(test)]
    waiter_count: std::sync::atomic::AtomicUsize,
}

impl InFlight {
    fn await_slice(&self, requested: ByteRange) -> Result<Vec<u8>, RangeSessionError> {
        let mut outcome = self.lock_outcome();
        #[cfg(test)]
        let counted_waiter = matches!(*outcome, FlightOutcome::Pending { .. });
        #[cfg(test)]
        if counted_waiter {
            self.waiter_count
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        }
        while matches!(*outcome, FlightOutcome::Pending { .. }) {
            outcome = self
                .ready
                .wait(outcome)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
        }
        #[cfg(test)]
        if counted_waiter {
            self.waiter_count
                .fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
        }
        match &*outcome {
            FlightOutcome::Pending { .. } => Err(RangeSessionError::CorruptState),
            FlightOutcome::Complete(Ok(bytes)) => slice_response(bytes, self.range, requested),
            FlightOutcome::Complete(Err(error)) => Err(error.clone()),
        }
    }

    fn is_pending(&self) -> bool {
        matches!(*self.lock_outcome(), FlightOutcome::Pending { .. })
    }

    /// Atomically marks a pending flight as entering its transport callback.
    fn begin_transport(&self) -> bool {
        let mut outcome = self.lock_outcome();
        match &mut *outcome {
            FlightOutcome::Pending { transport_started } => {
                *transport_started = true;
                true
            }
            FlightOutcome::Complete(_) => false,
        }
    }

    /// Publishes cancellation and reports whether the transport callback started.
    fn cancel(&self) -> bool {
        let transport_started = {
            let mut outcome = self.lock_outcome();
            let FlightOutcome::Pending { transport_started } = *outcome else {
                return false;
            };
            *outcome = FlightOutcome::Complete(Err(RangeSessionError::Cancelled));
            transport_started
        };
        self.ready.notify_all();
        transport_started
    }

    fn publish(&self, result: Result<Arc<Vec<u8>>, RangeSessionError>) {
        let published = {
            let mut outcome = self.lock_outcome();
            if !matches!(*outcome, FlightOutcome::Pending { .. }) {
                false
            } else {
                *outcome = FlightOutcome::Complete(result);
                true
            }
        };
        if published {
            self.ready.notify_all();
        }
    }

    fn lock_outcome(&self) -> std::sync::MutexGuard<'_, FlightOutcome> {
        self.outcome
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

enum FlightOutcome {
    Pending { transport_started: bool },
    Complete(Result<Arc<Vec<u8>>, RangeSessionError>),
}

enum ReadDecision {
    Cached(Vec<u8>),
    Join(Arc<InFlight>),
    Fetch(Arc<InFlight>),
}

enum PrefetchDecision {
    Cached,
    Join(Arc<InFlight>),
    Fetch(Arc<InFlight>),
}

struct PublishedFetch {
    bytes: Arc<Vec<u8>>,
    stored: bool,
}

fn slice_response(
    bytes: &[u8],
    fetched: ByteRange,
    requested: ByteRange,
) -> Result<Vec<u8>, RangeSessionError> {
    if !range_covers(fetched, requested) {
        return Err(RangeSessionError::CorruptState);
    }
    let from = usize::try_from(requested.start - fetched.start)
        .map_err(|_| RangeSessionError::PlatformRangeTooLarge)?;
    let len = range_len_usize(requested)?;
    let to = from
        .checked_add(len)
        .ok_or(RangeSessionError::PlatformRangeTooLarge)?;
    bytes
        .get(from..to)
        .map(<[u8]>::to_vec)
        .ok_or(RangeSessionError::CorruptState)
}

fn normalize_ranges(ranges: &[ByteRange]) -> Result<Vec<ByteRange>, RangeSessionError> {
    let mut ranges = ranges.to_vec();
    for range in &ranges {
        validate_range(*range)?;
    }
    ranges.sort_by_key(|range| (range.start, range.end_inclusive));
    let mut merged: Vec<ByteRange> = Vec::with_capacity(ranges.len());
    for range in ranges {
        let Some(last) = merged.last_mut() else {
            merged.push(range);
            continue;
        };
        let overlaps = range.start <= last.end_inclusive;
        let adjacent = last.end_inclusive.checked_add(1) == Some(range.start);
        if overlaps || adjacent {
            last.end_inclusive = last.end_inclusive.max(range.end_inclusive);
        } else {
            merged.push(range);
        }
    }
    Ok(merged)
}

fn range_covers(covering: ByteRange, requested: ByteRange) -> bool {
    requested.start >= covering.start && requested.end_inclusive <= covering.end_inclusive
}

fn validate_range(range: ByteRange) -> Result<(), RangeSessionError> {
    if range.end_inclusive < range.start {
        return Err(RangeSessionError::InvalidRange {
            start: range.start,
            end_inclusive: range.end_inclusive,
        });
    }
    range_len(range).map(|_| ())
}

fn range_len(range: ByteRange) -> Result<u64, RangeSessionError> {
    if range.end_inclusive < range.start {
        return Err(RangeSessionError::InvalidRange {
            start: range.start,
            end_inclusive: range.end_inclusive,
        });
    }
    range
        .end_inclusive
        .checked_sub(range.start)
        .and_then(|difference| difference.checked_add(1))
        .ok_or(RangeSessionError::RangeLengthOverflow)
}

fn range_len_usize(range: ByteRange) -> Result<usize, RangeSessionError> {
    usize::try_from(range_len(range)?).map_err(|_| RangeSessionError::PlatformRangeTooLarge)
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Condvar, Mutex, mpsc};
    use std::thread;
    use std::time::{Duration, Instant};

    use super::{
        FlightOutcome, InFlight, InFlightOwner, RangeSessionError, RangeTransport,
        RemoteRangeSession, normalize_ranges,
    };
    use crate::scheduler::range_planner::ByteRange;

    fn range(start: u64, end_inclusive: u64) -> ByteRange {
        ByteRange::new(start, end_inclusive)
    }

    #[test]
    fn demand_joins_covering_prefetch_and_uses_one_transport_fetch() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        let prefetch_session = Arc::clone(&session);
        let prefetch = thread::spawn(move || prefetch_session.prefetch(range(8, 23), 3, &[]));
        transport.wait_for_calls(1);

        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(12, 15)));
        wait_for_in_flight_waiters(&session, 1);
        transport.release_all();

        assert!(prefetch.join().unwrap().unwrap());
        assert_eq!(vec![12, 13, 14, 15], demand.join().unwrap().unwrap());
        assert_eq!(vec![range(8, 23)], transport.call_ranges());
    }

    #[test]
    fn demand_read_ahead_is_off_by_default_and_follows_configured_bytes() {
        let transport = ControlledTransport::immediate(data(256));
        let session = RemoteRangeSession::new(256, transport.clone());

        // Disabled by default: metadata-style reads fetch exact ranges.
        session.read_range(range(0, 15)).unwrap();
        assert_eq!(vec![range(0, 15)], transport.call_ranges());

        // Once configured, demand misses expand forward and clamp at the file end.
        session.set_read_ahead_bytes(32);
        session.read_range(range(32, 47)).unwrap();
        session.read_range(range(240, 255)).unwrap();
        assert_eq!(
            vec![range(0, 15), range(32, 79), range(240, 255)],
            transport.call_ranges()
        );

        // Lowering the read-ahead immediately narrows later misses.
        session.set_read_ahead_bytes(8);
        session.read_range(range(96, 103)).unwrap();
        assert_eq!(
            vec![
                range(0, 15),
                range(32, 79),
                range(240, 255),
                range(96, 111),
            ],
            transport.call_ranges()
        );
    }

    #[test]
    fn prefetch_joins_covering_demand_read_ahead_fetch() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 8));
        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(8, 11)));
        transport.wait_for_calls(1);

        let prefetch_session = Arc::clone(&session);
        let prefetch = thread::spawn(move || prefetch_session.prefetch(range(12, 15), 3, &[]));
        wait_for_in_flight_waiters(&session, 1);
        transport.release_all();

        assert_eq!(vec![8, 9, 10, 11], demand.join().unwrap().unwrap());
        assert!(prefetch.join().unwrap().unwrap());
        assert_eq!(vec![range(8, 19)], transport.call_ranges());
    }

    #[test]
    fn demand_joiner_consumes_published_bytes_when_prefetch_is_too_large_to_cache() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 4, 4, 0));
        let prefetch_session = Arc::clone(&session);
        let prefetch = thread::spawn(move || prefetch_session.prefetch(range(8, 15), 3, &[]));
        transport.wait_for_calls(1);

        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(10, 12)));
        wait_for_in_flight_waiters(&session, 1);
        transport.release_all();

        assert!(!prefetch.join().unwrap().unwrap());
        assert_eq!(vec![10, 11, 12], demand.join().unwrap().unwrap());
        assert_eq!(vec![range(8, 15)], transport.call_ranges());
        assert!(!session.is_range_cached(range(10, 12)).unwrap());
    }

    #[test]
    fn demand_joiner_consumes_published_bytes_when_prefetch_cannot_evict_protected_cache() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 8, 4, 0));
        session
            .cache
            .store_unprotected(range(0, 3), &[0, 1, 2, 3])
            .unwrap();
        session
            .cache
            .store_unprotected(range(4, 7), &[4, 5, 6, 7])
            .unwrap();
        let prefetch_session = Arc::clone(&session);
        let prefetch =
            thread::spawn(move || prefetch_session.prefetch(range(8, 11), 3, &[range(0, 7)]));
        transport.wait_for_calls(1);

        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(9, 10)));
        wait_for_in_flight_waiters(&session, 1);
        transport.release_all();

        assert!(!prefetch.join().unwrap().unwrap());
        assert_eq!(vec![9, 10], demand.join().unwrap().unwrap());
        assert_eq!(vec![range(8, 11)], transport.call_ranges());
        assert!(session.is_range_cached(range(0, 7)).unwrap());
        assert!(!session.is_range_cached(range(8, 11)).unwrap());
    }

    #[test]
    fn prefetch_joiner_returns_false_when_covering_demand_is_too_large_to_cache() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 4, 4, 0));
        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(8, 15)));
        transport.wait_for_calls(1);

        let prefetch_session = Arc::clone(&session);
        let prefetch = thread::spawn(move || prefetch_session.prefetch(range(10, 12), 3, &[]));
        wait_for_in_flight_waiters(&session, 1);
        transport.release_all();

        assert_eq!(
            (8_u8..16).collect::<Vec<_>>(),
            demand.join().unwrap().unwrap()
        );
        assert!(!prefetch.join().unwrap().unwrap());
        assert_eq!(vec![range(8, 15)], transport.call_ranges());
        assert!(!session.is_range_cached(range(10, 12)).unwrap());
    }

    #[test]
    fn partial_overlap_starts_a_second_fetch() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        let first_session = Arc::clone(&session);
        let first = thread::spawn(move || first_session.read_range(range(0, 7)));
        transport.wait_for_calls(1);

        let second_session = Arc::clone(&session);
        let second = thread::spawn(move || second_session.read_range(range(4, 11)));
        transport.wait_for_calls(2);
        transport.release_all();

        assert_eq!(
            (0_u8..8).collect::<Vec<_>>(),
            first.join().unwrap().unwrap()
        );
        assert_eq!(
            (4_u8..12).collect::<Vec<_>>(),
            second.join().unwrap().unwrap()
        );
        let mut calls = transport.call_ranges();
        calls.sort_by_key(|range| range.start);
        assert_eq!(vec![range(0, 7), range(4, 11)], calls);
    }

    #[test]
    fn demand_retries_after_covering_prefetch_transport_failure() {
        let transport = FailFirstTransport::new(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        let prefetch_session = Arc::clone(&session);
        let prefetch = thread::spawn(move || prefetch_session.prefetch(range(8, 15), 3, &[]));
        transport.wait_for_first_call();

        let demand_session = Arc::clone(&session);
        let demand = thread::spawn(move || demand_session.read_range(range(10, 12)));
        wait_for_in_flight_waiters(&session, 1);
        transport.fail_first();

        assert!(matches!(
            prefetch.join().unwrap(),
            Err(RangeSessionError::Transport(_))
        ));
        assert_eq!(vec![10, 11, 12], demand.join().unwrap().unwrap());
        assert_eq!(2, transport.call_count());
    }

    #[test]
    fn cancelled_prefetch_wakes_demand_to_refetch_and_late_response_is_not_cached() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        let owner_session = Arc::clone(&session);
        let owner = thread::spawn(move || owner_session.prefetch(range(16, 23), 3, &[]));
        transport.wait_for_calls(1);

        let waiter_session = Arc::clone(&session);
        let (waiter_done_tx, waiter_done_rx) = mpsc::channel();
        let waiter = thread::spawn(move || {
            let result = waiter_session.read_range(range(18, 20));
            waiter_done_tx.send(result.clone()).unwrap();
            result
        });
        wait_for_in_flight_waiters(&session, 1);

        session.cancel().unwrap();
        transport.wait_for_calls(2);
        assert_eq!(1, transport.cancelled_request_ids().len());
        transport.release_all();

        assert_eq!(
            Ok(vec![18, 19, 20]),
            waiter_done_rx.recv_timeout(Duration::from_secs(2)).unwrap()
        );
        assert_eq!(Err(RangeSessionError::Cancelled), owner.join().unwrap());
        assert_eq!(Ok(vec![18, 19, 20]), waiter.join().unwrap());
        assert!(!session.is_range_cached(range(16, 23)).unwrap());
        assert!(session.is_range_cached(range(18, 20)).unwrap());
        assert_eq!(vec![range(16, 23), range(18, 20)], transport.call_ranges());
    }

    #[test]
    fn close_wakes_demand_joiner_without_retrying_cancelled_prefetch() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        let owner_session = Arc::clone(&session);
        let owner = thread::spawn(move || owner_session.prefetch(range(16, 23), 3, &[]));
        transport.wait_for_calls(1);

        let waiter_session = Arc::clone(&session);
        let (waiter_done_tx, waiter_done_rx) = mpsc::channel();
        let waiter = thread::spawn(move || {
            let result = waiter_session.read_range(range(18, 20));
            waiter_done_tx.send(result.clone()).unwrap();
            result
        });
        wait_for_in_flight_waiters(&session, 1);

        session.close().unwrap();
        assert_eq!(
            Err(RangeSessionError::Cancelled),
            waiter_done_rx.recv_timeout(Duration::from_secs(2)).unwrap()
        );
        assert_eq!(1, transport.call_ranges().len());

        transport.release_all();
        assert_eq!(Err(RangeSessionError::Cancelled), owner.join().unwrap());
        assert_eq!(Err(RangeSessionError::Cancelled), waiter.join().unwrap());
    }

    #[test]
    fn cancellation_only_forwards_requests_that_entered_the_transport() {
        let unstarted = test_flight(1);
        assert!(!unstarted.cancel());
        assert!(!unstarted.begin_transport());

        let started = test_flight(2);
        assert!(started.begin_transport());
        assert!(started.cancel());
        assert!(!started.begin_transport());
    }

    #[test]
    fn cancel_epoch_wrap_still_rejects_the_old_flight_response() {
        let transport = ControlledTransport::blocking(data(64));
        let session = Arc::new(session(64, transport.clone(), 64, 4, 0));
        session.lock_state().cancel_epoch = u64::MAX;
        let owner_session = Arc::clone(&session);
        let owner = thread::spawn(move || owner_session.prefetch(range(24, 31), 3, &[]));
        transport.wait_for_calls(1);

        session.cancel().unwrap();
        assert_eq!(0, session.lock_state().cancel_epoch);
        transport.release_all();

        assert_eq!(Err(RangeSessionError::Cancelled), owner.join().unwrap());
        assert!(!session.is_range_cached(range(24, 31)).unwrap());
    }

    #[test]
    fn exhausted_request_id_fails_before_calling_transport() {
        let transport = ControlledTransport::immediate(data(16));
        let session = session(16, transport.clone(), 16, 4, 0);
        session.lock_state().next_request_id = u64::MAX;

        assert_eq!(
            Err(RangeSessionError::RequestIdExhausted),
            session.read_range(range(0, 3))
        );
        assert!(transport.call_ranges().is_empty());
    }

    #[test]
    fn truncated_transport_response_is_rejected_and_not_cached() {
        let transport = ControlledTransport::truncated(data(64));
        let session = session(64, transport.clone(), 64, 4, 0);

        let result = session.read_range(range(8, 15));

        assert_eq!(
            Err(RangeSessionError::InvalidResponseLength {
                start: 8,
                end_inclusive: 15,
                expected: 8,
                actual: 7,
            }),
            result
        );
        assert!(!session.is_range_cached(range(8, 15)).unwrap());
    }

    #[test]
    fn oversized_demand_response_is_returned_but_not_cached() {
        let transport = ControlledTransport::immediate(data(64));
        let session = session(64, transport.clone(), 4, 4, 0);

        assert_eq!(
            (8_u8..16).collect::<Vec<_>>(),
            session.read_range(range(8, 15)).unwrap()
        );
        assert_eq!(
            (8_u8..16).collect::<Vec<_>>(),
            session.read_range(range(8, 15)).unwrap()
        );

        assert_eq!(2, transport.call_ranges().len());
        assert_eq!(0, session.cached_bytes());
    }

    #[test]
    fn priority_two_retries_without_protection_when_capacity_is_protected() {
        let transport = ControlledTransport::immediate(data(64));
        let session = session(64, transport, 8, 4, 0);
        assert!(session.prefetch(range(0, 3), 3, &[]).unwrap());
        assert!(session.prefetch(range(4, 7), 3, &[]).unwrap());

        assert!(session.prefetch(range(8, 11), 2, &[range(0, 7)]).unwrap());

        assert!(session.is_range_cached(range(8, 11)).unwrap());
        assert_eq!(8, session.cached_bytes());
    }

    #[test]
    fn close_cancels_requests_rejects_future_reads_and_clears_cache() {
        let transport = ControlledTransport::immediate(data(64));
        let session = session(64, transport, 16, 4, 0);
        session.read_range(range(0, 3)).unwrap();
        assert_eq!(4, session.cached_bytes());

        session.close().unwrap();

        assert_eq!(0, session.cached_bytes());
        assert_eq!(
            Err(RangeSessionError::Closed),
            session.read_range(range(0, 3))
        );
    }

    #[test]
    fn protected_ranges_are_sorted_merged_and_checked_at_u64_max() {
        assert_eq!(
            vec![range(0, 7), range(u64::MAX - 1, u64::MAX)],
            normalize_ranges(&[
                range(4, 7),
                range(u64::MAX, u64::MAX),
                range(0, 3),
                range(u64::MAX - 1, u64::MAX - 1),
            ])
            .unwrap()
        );
    }

    fn session<T: RangeTransport>(
        file_size: u64,
        transport: T,
        max_cache_bytes: u64,
        segment_bytes: usize,
        read_ahead_bytes: u64,
    ) -> RemoteRangeSession<T> {
        RemoteRangeSession::with_limits(
            file_size,
            transport,
            max_cache_bytes,
            segment_bytes,
            read_ahead_bytes,
        )
        .unwrap()
    }

    fn data(len: usize) -> Vec<u8> {
        (0..len).map(|value| value as u8).collect()
    }

    fn test_flight(request_id: u64) -> InFlight {
        InFlight {
            request_id,
            range: range(0, 3),
            owner: InFlightOwner::Demand,
            cancel_epoch: 0,
            outcome: Mutex::new(FlightOutcome::Pending {
                transport_started: false,
            }),
            ready: Condvar::new(),
            waiter_count: std::sync::atomic::AtomicUsize::new(0),
        }
    }

    fn wait_for_in_flight_waiters<T: RangeTransport>(
        session: &RemoteRangeSession<T>,
        expected: usize,
    ) {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            let waiters = session
                .lock_state()
                .in_flight
                .iter()
                .map(|flight| {
                    flight
                        .waiter_count
                        .load(std::sync::atomic::Ordering::SeqCst)
                })
                .sum::<usize>();
            if waiters >= expected {
                return;
            }
            assert!(
                Instant::now() < deadline,
                "covering request did not join in-flight fetch"
            );
            thread::yield_now();
        }
    }

    #[derive(Clone)]
    struct ControlledTransport {
        data: Arc<Vec<u8>>,
        state: Arc<(Mutex<ControlledState>, Condvar)>,
    }

    struct ControlledState {
        calls: Vec<(u64, ByteRange)>,
        cancelled: Vec<u64>,
        block: bool,
        released: bool,
        truncate: bool,
    }

    impl ControlledTransport {
        fn immediate(data: Vec<u8>) -> Self {
            Self::with_options(data, false, false)
        }

        fn blocking(data: Vec<u8>) -> Self {
            Self::with_options(data, true, false)
        }

        fn truncated(data: Vec<u8>) -> Self {
            Self::with_options(data, false, true)
        }

        fn with_options(data: Vec<u8>, block: bool, truncate: bool) -> Self {
            Self {
                data: Arc::new(data),
                state: Arc::new((
                    Mutex::new(ControlledState {
                        calls: Vec::new(),
                        cancelled: Vec::new(),
                        block,
                        released: !block,
                        truncate,
                    }),
                    Condvar::new(),
                )),
            }
        }

        fn wait_for_calls(&self, expected: usize) {
            let (lock, changed) = &*self.state;
            let mut state = lock.lock().unwrap();
            while state.calls.len() < expected {
                let (next, timeout) = changed.wait_timeout(state, Duration::from_secs(2)).unwrap();
                assert!(!timeout.timed_out(), "transport call did not start");
                state = next;
            }
        }

        fn release_all(&self) {
            let (lock, changed) = &*self.state;
            lock.lock().unwrap().released = true;
            changed.notify_all();
        }

        fn call_ranges(&self) -> Vec<ByteRange> {
            self.state
                .0
                .lock()
                .unwrap()
                .calls
                .iter()
                .map(|(_, range)| *range)
                .collect()
        }

        fn cancelled_request_ids(&self) -> Vec<u64> {
            self.state.0.lock().unwrap().cancelled.clone()
        }
    }

    #[derive(Debug)]
    struct TestTransportError;

    impl std::fmt::Display for TestTransportError {
        fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            formatter.write_str("test transport failed")
        }
    }

    impl RangeTransport for ControlledTransport {
        type Error = TestTransportError;

        fn fetch(&self, request_id: u64, range: ByteRange) -> Result<Vec<u8>, Self::Error> {
            let (lock, changed) = &*self.state;
            let mut state = lock.lock().unwrap();
            state.calls.push((request_id, range));
            changed.notify_all();
            while state.block && !state.released {
                state = changed.wait(state).unwrap();
            }
            let truncate = state.truncate;
            drop(state);

            let start = usize::try_from(range.start).unwrap();
            let end = usize::try_from(range.end_inclusive).unwrap();
            let mut bytes = self.data[start..=end].to_vec();
            if truncate {
                bytes.pop();
            }
            Ok(bytes)
        }

        fn cancel(&self, request_id: u64) {
            let (lock, changed) = &*self.state;
            lock.lock().unwrap().cancelled.push(request_id);
            changed.notify_all();
        }
    }

    #[derive(Clone)]
    struct FailFirstTransport {
        data: Arc<Vec<u8>>,
        state: Arc<(Mutex<FailFirstState>, Condvar)>,
    }

    struct FailFirstState {
        calls: usize,
        release_first: bool,
    }

    impl FailFirstTransport {
        fn new(data: Vec<u8>) -> Self {
            Self {
                data: Arc::new(data),
                state: Arc::new((
                    Mutex::new(FailFirstState {
                        calls: 0,
                        release_first: false,
                    }),
                    Condvar::new(),
                )),
            }
        }

        fn wait_for_first_call(&self) {
            let (lock, changed) = &*self.state;
            let mut state = lock.lock().unwrap();
            while state.calls == 0 {
                let (next, timeout) = changed.wait_timeout(state, Duration::from_secs(2)).unwrap();
                assert!(!timeout.timed_out(), "first transport call did not start");
                state = next;
            }
        }

        fn fail_first(&self) {
            let (lock, changed) = &*self.state;
            lock.lock().unwrap().release_first = true;
            changed.notify_all();
        }

        fn call_count(&self) -> usize {
            self.state.0.lock().unwrap().calls
        }
    }

    impl RangeTransport for FailFirstTransport {
        type Error = TestTransportError;

        fn fetch(&self, _request_id: u64, range: ByteRange) -> Result<Vec<u8>, Self::Error> {
            let call = {
                let (lock, changed) = &*self.state;
                let mut state = lock.lock().unwrap();
                state.calls += 1;
                let call = state.calls;
                changed.notify_all();
                if call == 1 {
                    while !state.release_first {
                        state = changed.wait(state).unwrap();
                    }
                }
                call
            };
            if call == 1 {
                return Err(TestTransportError);
            }
            let start = usize::try_from(range.start).unwrap();
            let end = usize::try_from(range.end_inclusive).unwrap();
            Ok(self.data[start..=end].to_vec())
        }

        fn cancel(&self, _request_id: u64) {}
    }
}
