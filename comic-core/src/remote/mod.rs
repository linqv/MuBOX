use anyhow::Result;

use crate::zip::RangeReader;

pub mod jni_range_reader;

pub trait RangeCallbacks {
    fn size(&self, file_id: u64) -> Result<u64>;
    fn read_range(&self, file_id: u64, start: u64, end_inclusive: u64) -> Result<Vec<u8>>;
}

pub struct CallbackRangeReader<C> {
    file_id: u64,
    callbacks: C,
}

impl<C> CallbackRangeReader<C> {
    pub fn new(file_id: u64, callbacks: C) -> Self {
        Self { file_id, callbacks }
    }
}

impl<C: RangeCallbacks> RangeReader for CallbackRangeReader<C> {
    fn size(&self) -> Result<u64> {
        self.callbacks.size(self.file_id)
    }

    fn read_range(&self, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
        self.callbacks.read_range(self.file_id, start, end_inclusive)
    }
}

#[cfg(test)]
mod tests {
    use super::{CallbackRangeReader, RangeCallbacks};
    use crate::zip::RangeReader;
    use anyhow::Result;

    #[test]
    fn callback_range_reader_serves_inclusive_ranges() {
        let reader = CallbackRangeReader::new(7, FakeCallbacks);

        assert_eq!(10, reader.size().unwrap());
        assert_eq!(vec![2, 3, 4], reader.read_range(2, 4).unwrap());
    }

    struct FakeCallbacks;

    impl RangeCallbacks for FakeCallbacks {
        fn size(&self, file_id: u64) -> Result<u64> {
            assert_eq!(7, file_id);
            Ok(10)
        }

        fn read_range(&self, file_id: u64, start: u64, end_inclusive: u64) -> Result<Vec<u8>> {
            assert_eq!(7, file_id);
            Ok((start..=end_inclusive).map(|value| value as u8).collect())
        }
    }
}
