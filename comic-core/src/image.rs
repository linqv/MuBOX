#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ImageFormatOptions {
    pub avif: bool,
}

impl Default for ImageFormatOptions {
    fn default() -> Self {
        Self { avif: false }
    }
}

pub fn is_supported_image(name: &str, options: ImageFormatOptions) -> bool {
    let lower = name.to_lowercase();
    lower.ends_with(".jpg")
        || lower.ends_with(".jpeg")
        || lower.ends_with(".png")
        || lower.ends_with(".webp")
        || lower.ends_with(".gif")
        || lower.ends_with(".bmp")
        || lower.ends_with(".heif")
        || lower.ends_with(".heic")
        || (options.avif && lower.ends_with(".avif"))
}
