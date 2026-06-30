#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ImageFormatOptions {
    pub avif: bool,
}

fn ends_with_ignore_ascii_case(name: &str, suffix: &str) -> bool {
    name.len() >= suffix.len()
        && name.as_bytes()[name.len() - suffix.len()..].eq_ignore_ascii_case(suffix.as_bytes())
}

pub fn is_supported_image(name: &str, options: ImageFormatOptions) -> bool {
    ends_with_ignore_ascii_case(name, ".jpg")
        || ends_with_ignore_ascii_case(name, ".jpeg")
        || ends_with_ignore_ascii_case(name, ".png")
        || ends_with_ignore_ascii_case(name, ".webp")
        || ends_with_ignore_ascii_case(name, ".gif")
        || ends_with_ignore_ascii_case(name, ".bmp")
        || ends_with_ignore_ascii_case(name, ".heif")
        || ends_with_ignore_ascii_case(name, ".heic")
        || (options.avif && ends_with_ignore_ascii_case(name, ".avif"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lowercase_extensions() {
        let opts = ImageFormatOptions::default();
        assert!(is_supported_image("cover.jpg", opts));
        assert!(is_supported_image("page.png", opts));
        assert!(is_supported_image("img.webp", opts));
        assert!(is_supported_image("img.gif", opts));
        assert!(is_supported_image("img.bmp", opts));
        assert!(is_supported_image("img.heif", opts));
        assert!(is_supported_image("img.heic", opts));
        assert!(is_supported_image("photo.jpeg", opts));
    }

    #[test]
    fn uppercase_extensions() {
        let opts = ImageFormatOptions::default();
        assert!(is_supported_image("COVER.JPG", opts));
        assert!(is_supported_image("page.WebP", opts));
        assert!(is_supported_image("IMG.PNG", opts));
        assert!(is_supported_image("PHOTO.JPEG", opts));
        assert!(is_supported_image("pic.GIF", opts));
        assert!(is_supported_image("pic.BMP", opts));
        assert!(is_supported_image("pic.HEIF", opts));
        assert!(is_supported_image("pic.HEIC", opts));
    }

    #[test]
    fn non_image_rejected() {
        let opts = ImageFormatOptions::default();
        assert!(!is_supported_image("readme.txt", opts));
        assert!(!is_supported_image("archive.zip", opts));
        assert!(!is_supported_image("", opts));
    }

    #[test]
    fn avif_disabled_by_default() {
        let opts = ImageFormatOptions::default();
        assert!(!is_supported_image("photo.avif", opts));
        assert!(!is_supported_image("PHOTO.AVIF", opts));
    }

    #[test]
    fn avif_enabled() {
        let opts = ImageFormatOptions { avif: true };
        assert!(is_supported_image("photo.avif", opts));
        assert!(is_supported_image("PHOTO.AVIF", opts));
        assert!(is_supported_image("img.Avif", opts));
    }

    #[test]
    fn just_extension_is_valid() {
        // A filename that is exactly ".jpg" has length >= suffix length
        let opts = ImageFormatOptions::default();
        assert!(is_supported_image(".jpg", opts));
    }
}
