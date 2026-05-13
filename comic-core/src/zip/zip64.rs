use crate::error::ComicCoreError;

pub fn unsupported_zip64_error() -> ComicCoreError {
    ComicCoreError::InvalidZip("zip64 metadata missing".to_string())
}

#[cfg(test)]
mod tests {
    use super::unsupported_zip64_error;

    #[test]
    fn zip64_without_metadata_is_explicitly_invalid() {
        assert_eq!(
            "invalid zip: zip64 metadata missing",
            unsupported_zip64_error().to_string()
        );
    }
}
