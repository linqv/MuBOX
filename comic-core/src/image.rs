fn ends_with_ignore_ascii_case(name: &str, suffix: &str) -> bool {
    name.len() >= suffix.len()
        && name.as_bytes()[name.len() - suffix.len()..].eq_ignore_ascii_case(suffix.as_bytes())
}

pub fn is_supported_image(name: &str) -> bool {
    ends_with_ignore_ascii_case(name, ".jpg")
        || ends_with_ignore_ascii_case(name, ".jpeg")
        || ends_with_ignore_ascii_case(name, ".png")
        || ends_with_ignore_ascii_case(name, ".webp")
        || ends_with_ignore_ascii_case(name, ".gif")
        || ends_with_ignore_ascii_case(name, ".bmp")
        || ends_with_ignore_ascii_case(name, ".heif")
        || ends_with_ignore_ascii_case(name, ".heic")
        || ends_with_ignore_ascii_case(name, ".avif")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lowercase_extensions() {
        assert!(is_supported_image("cover.jpg"));
        assert!(is_supported_image("page.png"));
        assert!(is_supported_image("img.webp"));
        assert!(is_supported_image("img.gif"));
        assert!(is_supported_image("img.bmp"));
        assert!(is_supported_image("img.heif"));
        assert!(is_supported_image("img.heic"));
        assert!(is_supported_image("photo.jpeg"));
    }

    #[test]
    fn uppercase_extensions() {
        assert!(is_supported_image("COVER.JPG"));
        assert!(is_supported_image("page.WebP"));
        assert!(is_supported_image("IMG.PNG"));
        assert!(is_supported_image("PHOTO.JPEG"));
        assert!(is_supported_image("pic.GIF"));
        assert!(is_supported_image("pic.BMP"));
        assert!(is_supported_image("pic.HEIF"));
        assert!(is_supported_image("pic.HEIC"));
    }

    #[test]
    fn non_image_rejected() {
        assert!(!is_supported_image("readme.txt"));
        assert!(!is_supported_image("archive.zip"));
        assert!(!is_supported_image(""));
    }

    #[test]
    fn avif_is_supported() {
        assert!(is_supported_image(".avif"));
        assert!(is_supported_image(".AVIF"));
        assert!(is_supported_image(".Avif"));
        assert!(is_supported_image("photo.avif"));
        assert!(is_supported_image("PHOTO.AVIF"));
        assert!(is_supported_image("img.Avif"));
    }

    #[test]
    fn just_extension_is_valid() {
        // A filename that is exactly ".jpg" has length >= suffix length
        assert!(is_supported_image(".jpg"));
    }
}
