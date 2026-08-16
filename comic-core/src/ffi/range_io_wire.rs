use anyhow::{Result, anyhow, bail};

use crate::scheduler::range_planner::ByteRange;

pub(super) const MAX_PROTECTED_RANGE_WIRE_WORDS: usize = 1_000_000;
const HEADER_WORDS: usize = 2;
const RANGE_WORDS: usize = 2;
const MAX_PROTECTED_RANGES: usize = (MAX_PROTECTED_RANGE_WIRE_WORDS - HEADER_WORDS) / RANGE_WORDS;
const VERSION: i64 = 1;

/// Decodes `[version, count, start, endInclusive, ...]`.
///
/// The decoder deliberately requires an exact payload length. This prevents a
/// newer or corrupted wire shape from being partially accepted as valid cache
/// protection metadata.
pub(super) fn decode_protected_ranges(values: &[i64]) -> Result<Vec<ByteRange>> {
    if values.len() > MAX_PROTECTED_RANGE_WIRE_WORDS {
        bail!("protected range payload is too large");
    }
    let version = values
        .first()
        .copied()
        .ok_or_else(|| anyhow!("missing protected range wire version"))?;
    if version != VERSION {
        bail!("unsupported protected range wire version");
    }
    let count = values
        .get(1)
        .copied()
        .ok_or_else(|| anyhow!("missing protected range count"))?;
    let count = usize::try_from(count)
        .map_err(|_| anyhow!("protected range count must be non-negative"))?;
    if count > MAX_PROTECTED_RANGES {
        bail!("protected range count is too large");
    }
    let expected_words = count
        .checked_mul(RANGE_WORDS)
        .and_then(|words| words.checked_add(HEADER_WORDS))
        .ok_or_else(|| anyhow!("protected range payload length overflowed"))?;
    if values.len() != expected_words {
        bail!(
            "protected range payload length mismatch: expected {expected_words} words, received {}",
            values.len(),
        );
    }

    let mut ranges = Vec::with_capacity(count);
    for pair in values[HEADER_WORDS..].chunks_exact(RANGE_WORDS) {
        let start = u64::try_from(pair[0])
            .map_err(|_| anyhow!("protected range start must be non-negative"))?;
        let end_inclusive = u64::try_from(pair[1])
            .map_err(|_| anyhow!("protected range end must be non-negative"))?;
        if end_inclusive < start {
            bail!("protected range end precedes start");
        }
        ranges.push(ByteRange::new(start, end_inclusive));
    }
    Ok(ranges)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_empty_and_populated_payloads() {
        assert_eq!(
            Vec::<ByteRange>::new(),
            decode_protected_ranges(&[1, 0]).unwrap()
        );
        assert_eq!(
            vec![ByteRange::new(0, 9), ByteRange::new(20, 29)],
            decode_protected_ranges(&[1, 2, 0, 9, 20, 29]).unwrap(),
        );
    }

    #[test]
    fn rejects_missing_or_unknown_headers() {
        for values in [vec![], vec![1], vec![2, 0], vec![1, -1]] {
            assert!(
                decode_protected_ranges(&values).is_err(),
                "accepted {values:?}",
            );
        }
    }

    #[test]
    fn rejects_truncated_and_trailing_payloads() {
        for values in [vec![1, 1], vec![1, 1, 10], vec![1, 0, 10, 19]] {
            assert!(
                decode_protected_ranges(&values).is_err(),
                "accepted {values:?}",
            );
        }
    }

    #[test]
    fn rejects_negative_and_inverted_ranges() {
        for values in [vec![1, 1, -1, 9], vec![1, 1, 0, -1], vec![1, 1, 10, 9]] {
            assert!(
                decode_protected_ranges(&values).is_err(),
                "accepted {values:?}",
            );
        }
    }

    #[test]
    fn rejects_counts_above_the_wire_limit_before_allocating() {
        assert!(decode_protected_ranges(&[1, MAX_PROTECTED_RANGES as i64 + 1]).is_err(),);
    }
}
