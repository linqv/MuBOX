#[no_mangle]
pub extern "C" fn comic_core_smoke_value() -> i32 {
    42
}

#[cfg(test)]
mod tests {
    use super::comic_core_smoke_value;

    #[test]
    fn smoke_value_is_stable() {
        assert_eq!(comic_core_smoke_value(), 42);
    }
}
