use std::collections::{HashMap, VecDeque};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct SegmentKey {
    pub stream_id: u64,
    pub segment_index: u64,
}

#[derive(Debug)]
pub struct Segment {
    pub key: SegmentKey,
    pub start: u64,
    pub bytes: Arc<[u8]>,
}

impl Segment {
    pub fn new(key: SegmentKey, start: u64, bytes: Vec<u8>) -> Self {
        Self {
            key,
            start,
            bytes: Arc::from(bytes),
        }
    }

    pub fn end_inclusive(&self) -> Option<u64> {
        let length = u64::try_from(self.bytes.len()).ok()?;
        self.start.checked_add(length.checked_sub(1)?)
    }

    pub fn slice(&self, start: u64, end_inclusive: u64) -> Option<SegmentSlice> {
        if start < self.start || end_inclusive < start || end_inclusive > self.end_inclusive()? {
            return None;
        }
        let from = usize::try_from(start.checked_sub(self.start)?).ok()?;
        let to = usize::try_from(end_inclusive.checked_sub(self.start)?.checked_add(1)?).ok()?;
        Some(SegmentSlice {
            bytes: Arc::clone(&self.bytes),
            from,
            to,
        })
    }
}

#[derive(Clone, Debug)]
pub struct SegmentSlice {
    pub bytes: Arc<[u8]>,
    pub from: usize,
    pub to: usize,
}

impl SegmentSlice {
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes[self.from..self.to]
    }

    pub fn len(&self) -> usize {
        self.to - self.from
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// Byte-bounded access-order LRU. Returned segments retain their `Arc` storage, so an eviction
/// can never invalidate a response that is already being written to a client socket.
#[derive(Debug)]
pub struct ByteLru {
    max_bytes: u64,
    total_bytes: u64,
    entries: HashMap<SegmentKey, Arc<Segment>>,
    oldest_to_newest: VecDeque<SegmentKey>,
}

impl ByteLru {
    pub fn new(max_bytes: u64) -> Self {
        Self {
            max_bytes,
            total_bytes: 0,
            entries: HashMap::new(),
            oldest_to_newest: VecDeque::new(),
        }
    }

    pub fn get(&mut self, key: SegmentKey) -> Option<Arc<Segment>> {
        let segment = self.entries.get(&key).cloned()?;
        self.touch(key);
        Some(segment)
    }

    pub fn contains(&self, key: SegmentKey) -> bool {
        self.entries.contains_key(&key)
    }

    pub fn insert(&mut self, segment: Arc<Segment>) -> bool {
        let byte_count = u64::try_from(segment.bytes.len()).unwrap_or(u64::MAX);
        if byte_count > self.max_bytes {
            return false;
        }

        let key = segment.key;
        if let Some(previous) = self.entries.remove(&key) {
            self.total_bytes = self
                .total_bytes
                .saturating_sub(u64::try_from(previous.bytes.len()).unwrap_or(u64::MAX));
            self.remove_order_key(key);
        }
        self.total_bytes = self.total_bytes.saturating_add(byte_count);
        self.entries.insert(key, segment);
        self.oldest_to_newest.push_back(key);
        self.trim();
        true
    }

    pub fn remove_stream(&mut self, stream_id: u64) {
        let keys = self
            .entries
            .keys()
            .copied()
            .filter(|key| key.stream_id == stream_id)
            .collect::<Vec<_>>();
        for key in keys {
            if let Some(segment) = self.entries.remove(&key) {
                self.total_bytes = self
                    .total_bytes
                    .saturating_sub(u64::try_from(segment.bytes.len()).unwrap_or(u64::MAX));
            }
            self.remove_order_key(key);
        }
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.oldest_to_newest.clear();
        self.total_bytes = 0;
    }

    pub fn total_bytes(&self) -> u64 {
        self.total_bytes
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    fn touch(&mut self, key: SegmentKey) {
        self.remove_order_key(key);
        self.oldest_to_newest.push_back(key);
    }

    fn remove_order_key(&mut self, key: SegmentKey) {
        if let Some(position) = self
            .oldest_to_newest
            .iter()
            .position(|candidate| *candidate == key)
        {
            self.oldest_to_newest.remove(position);
        }
    }

    fn trim(&mut self) {
        while self.total_bytes > self.max_bytes {
            let Some(key) = self.oldest_to_newest.pop_front() else {
                self.total_bytes = 0;
                break;
            };
            if let Some(segment) = self.entries.remove(&key) {
                self.total_bytes = self
                    .total_bytes
                    .saturating_sub(u64::try_from(segment.bytes.len()).unwrap_or(u64::MAX));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{ByteLru, Segment, SegmentKey};
    use std::sync::Arc;

    fn segment(stream_id: u64, index: u64, value: u8, len: usize) -> Arc<Segment> {
        Arc::new(Segment::new(
            SegmentKey {
                stream_id,
                segment_index: index,
            },
            index * 10,
            vec![value; len],
        ))
    }

    #[test]
    fn evicts_by_bytes_in_access_order() {
        let mut cache = ByteLru::new(6);
        let first = segment(1, 0, 1, 3);
        let second = segment(1, 1, 2, 3);
        let third = segment(1, 2, 3, 3);
        assert!(cache.insert(first));
        assert!(cache.insert(second));
        assert!(cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 0
            })
            .is_some());
        assert!(cache.insert(third));

        assert!(cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 1
            })
            .is_none());
        assert!(cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 0
            })
            .is_some());
        assert_eq!(cache.total_bytes(), 6);
    }

    #[test]
    fn stream_keys_are_isolated_and_removable() {
        let mut cache = ByteLru::new(12);
        assert!(cache.insert(segment(1, 0, 1, 3)));
        assert!(cache.insert(segment(2, 0, 2, 3)));
        cache.remove_stream(1);
        assert!(cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 0
            })
            .is_none());
        assert!(cache
            .get(SegmentKey {
                stream_id: 2,
                segment_index: 0
            })
            .is_some());
    }

    #[test]
    fn retained_slice_survives_eviction() {
        let mut cache = ByteLru::new(4);
        assert!(cache.insert(segment(1, 0, 7, 4)));
        let retained = cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 0,
            })
            .unwrap()
            .slice(0, 3)
            .unwrap();
        assert!(cache.insert(segment(1, 1, 8, 4)));
        assert_eq!(retained.as_bytes(), &[7, 7, 7, 7]);
    }

    #[test]
    fn rejects_entry_larger_than_budget_without_evicting_existing_data() {
        let mut cache = ByteLru::new(4);
        assert!(cache.insert(segment(1, 0, 1, 4)));
        assert!(!cache.insert(segment(1, 1, 2, 5)));
        assert!(cache
            .get(SegmentKey {
                stream_id: 1,
                segment_index: 0
            })
            .is_some());
    }
}
