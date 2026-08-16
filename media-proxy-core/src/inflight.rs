use crate::cache::Segment;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WaiterKind {
    Foreground,
    Prefetch,
}

#[derive(Debug)]
enum State {
    Pending,
    Complete(Result<Arc<Segment>, String>),
}

#[derive(Debug)]
pub struct InflightSegment {
    state: Mutex<State>,
    completed: Condvar,
    foreground_waiters: AtomicUsize,
    prefetch_waiters: AtomicUsize,
    request_id: AtomicU64,
    pub generation: u64,
}

impl InflightSegment {
    pub fn new(generation: u64) -> Self {
        Self {
            state: Mutex::new(State::Pending),
            completed: Condvar::new(),
            foreground_waiters: AtomicUsize::new(0),
            prefetch_waiters: AtomicUsize::new(0),
            request_id: AtomicU64::new(0),
            generation,
        }
    }

    pub fn enter(self: &Arc<Self>, kind: WaiterKind) -> WaiterGuard {
        match kind {
            WaiterKind::Foreground => {
                self.foreground_waiters.fetch_add(1, Ordering::AcqRel);
            }
            WaiterKind::Prefetch => {
                self.prefetch_waiters.fetch_add(1, Ordering::AcqRel);
            }
        }
        WaiterGuard {
            entry: Arc::clone(self),
            kind,
        }
    }

    pub fn foreground_waiters(&self) -> usize {
        self.foreground_waiters.load(Ordering::Acquire)
    }

    pub fn set_request_id(&self, request_id: u64) {
        self.request_id.store(request_id, Ordering::Release);
    }

    pub fn request_id(&self) -> Option<u64> {
        match self.request_id.load(Ordering::Acquire) {
            0 => None,
            value => Some(value),
        }
    }

    pub fn complete(&self, result: Result<Arc<Segment>, String>) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if matches!(*state, State::Pending) {
            *state = State::Complete(result);
            self.completed.notify_all();
        }
    }

    pub fn is_pending(&self) -> bool {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        matches!(*state, State::Pending)
    }

    pub fn cancel(&self, reason: impl Into<String>) {
        self.complete(Err(reason.into()));
    }

    pub fn wait(&self) -> Result<Arc<Segment>, String> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        loop {
            match &*state {
                State::Complete(result) => return result.clone(),
                State::Pending => {
                    state = self
                        .completed
                        .wait(state)
                        .unwrap_or_else(|poison| poison.into_inner());
                }
            }
        }
    }
}

pub struct WaiterGuard {
    entry: Arc<InflightSegment>,
    kind: WaiterKind,
}

impl Drop for WaiterGuard {
    fn drop(&mut self) {
        match self.kind {
            WaiterKind::Foreground => {
                self.entry.foreground_waiters.fetch_sub(1, Ordering::AcqRel);
            }
            WaiterKind::Prefetch => {
                self.entry.prefetch_waiters.fetch_sub(1, Ordering::AcqRel);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{InflightSegment, WaiterKind};
    use crate::cache::{Segment, SegmentKey};
    use std::sync::Arc;

    #[test]
    fn all_waiters_receive_the_same_arc() {
        let inflight = Arc::new(InflightSegment::new(1));
        let waiter = inflight.enter(WaiterKind::Foreground);
        let other = Arc::clone(&inflight);
        let thread = std::thread::spawn(move || other.wait().unwrap());
        let segment = Arc::new(Segment::new(
            SegmentKey {
                stream_id: 1,
                segment_index: 0,
            },
            0,
            vec![1, 2, 3],
        ));
        inflight.complete(Ok(Arc::clone(&segment)));
        let received = thread.join().unwrap();
        assert!(Arc::ptr_eq(&segment, &received));
        assert_eq!(inflight.foreground_waiters(), 1);
        drop(waiter);
        assert_eq!(inflight.foreground_waiters(), 0);
    }

    #[test]
    fn cancellation_wakes_waiters() {
        let inflight = Arc::new(InflightSegment::new(1));
        let other = Arc::clone(&inflight);
        let thread = std::thread::spawn(move || other.wait());
        inflight.cancel("closed");
        assert_eq!(thread.join().unwrap().unwrap_err(), "closed");
    }
}
