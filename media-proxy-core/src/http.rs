//! Small, synchronous HTTP primitives used by the loopback media proxy.
//!
//! This module deliberately owns no sockets and starts no runtime.  A caller can accumulate bytes
//! from any blocking or non-blocking transport, pass them to [`parse_request`], and use the returned
//! byte count to retain a pipelined request.  Only the subset needed by the local media server is
//! modelled, but parsing is strict enough that malformed input cannot become a response-splitting
//! vector.

use std::error::Error;
use std::fmt;

/// Maximum body represented by an open-ended (`bytes=N-`) request.
///
/// Keeping this bounded is important for seek-heavy players: a new seek can cancel a relatively
/// small native request instead of leaving a full-file transfer in flight.
pub const MAX_OPEN_ENDED_RANGE_BYTES: u64 = 8 * 1024 * 1024;

/// HTTP versions supported by the loopback server.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Version {
    Http10,
    Http11,
}

impl Version {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Http10 => "HTTP/1.0",
            Self::Http11 => "HTTP/1.1",
        }
    }
}

/// Methods understood by the proxy. Unknown valid method tokens are retained so the engine can
/// return `405 Method Not Allowed` instead of treating them as malformed HTTP.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Method {
    Get,
    Head,
    Other(String),
}

impl Method {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Get => "GET",
            Self::Head => "HEAD",
            Self::Other(method) => method,
        }
    }

    pub const fn is_supported(&self) -> bool {
        matches!(self, Self::Get | Self::Head)
    }
}

/// A parsed HTTP header. Request header names are normalized to lowercase ASCII.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Header {
    pub name: String,
    pub value: String,
}

/// A complete request head. Bodies are intentionally outside this module's contract.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Request {
    pub method: Method,
    pub target: String,
    pub version: Version,
    pub headers: Vec<Header>,
}

impl Request {
    /// Returns the first value for `name`, comparing the header name as ASCII case-insensitive.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|header| header.name.eq_ignore_ascii_case(name))
            .map(|header| header.value.as_str())
    }

    /// Tests all comma-separated `Connection` values without allocating a token collection.
    pub fn has_connection_token(&self, token: &str) -> bool {
        self.headers
            .iter()
            .filter(|header| header.name.eq_ignore_ascii_case("connection"))
            .flat_map(|header| header.value.split(','))
            .map(trim_optional_whitespace)
            .any(|candidate| !candidate.is_empty() && candidate.eq_ignore_ascii_case(token))
    }

    /// Applies the HTTP/1.0 and HTTP/1.1 persistence defaults.
    ///
    /// `close` wins if a peer sends contradictory tokens. This makes the conservative choice and
    /// avoids accidentally parsing response-body bytes as the next request.
    pub fn allows_persistent_connection(&self) -> bool {
        if self.has_connection_token("close") {
            return false;
        }
        match self.version {
            Version::Http11 => true,
            Version::Http10 => self.has_connection_token("keep-alive"),
        }
    }

    /// Alias useful at connection-loop call sites.
    pub fn should_keep_alive(&self) -> bool {
        self.allows_persistent_connection()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HttpError {
    /// More bytes are required before the request head can be parsed.
    Incomplete,
    /// The terminating empty line was not found within the configured limit.
    HeaderTooLarge {
        max: usize,
    },
    MalformedRequestLine,
    InvalidMethod,
    InvalidTarget,
    UnsupportedVersion,
    InvalidHeader,
}

impl fmt::Display for HttpError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Incomplete => formatter.write_str("incomplete HTTP request head"),
            Self::HeaderTooLarge { max } => {
                write!(formatter, "HTTP request head exceeds {max} bytes")
            }
            Self::MalformedRequestLine => formatter.write_str("malformed HTTP request line"),
            Self::InvalidMethod => formatter.write_str("invalid HTTP method"),
            Self::InvalidTarget => formatter.write_str("invalid HTTP request target"),
            Self::UnsupportedVersion => formatter.write_str("unsupported HTTP version"),
            Self::InvalidHeader => formatter.write_str("invalid HTTP header"),
        }
    }
}

impl Error for HttpError {}

/// Parses one complete request head from `input`.
///
/// The returned byte count ends immediately after the blank line, so bytes for a pipelined request
/// remain available to the caller. Both the RFC form (`CRLF CRLF`) and the legacy form (`LF LF`)
/// accepted by the old Kotlin proxy are recognized. A two-field request line is treated as
/// HTTP/1.0 for compatibility with that implementation.
pub fn parse_request(input: &[u8], max_header_bytes: usize) -> Result<(Request, usize), HttpError> {
    let Some((head_len, consumed)) = find_header_end(input) else {
        return if input.len() > max_header_bytes {
            Err(HttpError::HeaderTooLarge {
                max: max_header_bytes,
            })
        } else {
            Err(HttpError::Incomplete)
        };
    };

    if consumed > max_header_bytes {
        return Err(HttpError::HeaderTooLarge {
            max: max_header_bytes,
        });
    }

    let mut lines = input[..head_len].split(|byte| *byte == b'\n');
    let request_line = strip_trailing_carriage_return(lines.next().unwrap_or_default());
    let (method, target, version) = parse_request_line(request_line)?;

    let mut headers = Vec::new();
    for raw_line in lines {
        let line = strip_trailing_carriage_return(raw_line);
        if line.is_empty()
            || line
                .first()
                .is_some_and(|byte| matches!(byte, b' ' | b'\t'))
        {
            // Empty lines inside the header block and obsolete folded fields are both ambiguous.
            return Err(HttpError::InvalidHeader);
        }
        let Some(colon) = line.iter().position(|byte| *byte == b':') else {
            return Err(HttpError::InvalidHeader);
        };
        let name = &line[..colon];
        let value = trim_optional_whitespace_bytes(&line[colon + 1..]);
        if name.is_empty()
            || !name.iter().all(|byte| is_token_byte(*byte))
            || !is_valid_header_value_bytes(value)
        {
            return Err(HttpError::InvalidHeader);
        }
        headers.push(Header {
            name: ascii_lowercase(name),
            value: latin1_to_string(value),
        });
    }

    Ok((
        Request {
            method,
            target,
            version,
            headers,
        },
        consumed,
    ))
}

fn find_header_end(input: &[u8]) -> Option<(usize, usize)> {
    for index in 0..input.len() {
        if input[index..].starts_with(b"\r\n\r\n") {
            return Some((index, index + 4));
        }
        if input[index..].starts_with(b"\n\n") {
            return Some((index, index + 2));
        }
    }
    None
}

fn parse_request_line(line: &[u8]) -> Result<(Method, String, Version), HttpError> {
    let parts: Vec<&[u8]> = line
        .split(|byte| matches!(byte, b' ' | b'\t'))
        .filter(|part| !part.is_empty())
        .collect();
    if !(2..=3).contains(&parts.len()) {
        return Err(HttpError::MalformedRequestLine);
    }

    let method_bytes = parts[0];
    if !method_bytes.iter().all(|byte| is_token_byte(*byte)) {
        return Err(HttpError::InvalidMethod);
    }
    let method_text = std::str::from_utf8(method_bytes).map_err(|_| HttpError::InvalidMethod)?;
    let method = if method_text.eq_ignore_ascii_case("GET") {
        Method::Get
    } else if method_text.eq_ignore_ascii_case("HEAD") {
        Method::Head
    } else {
        Method::Other(method_text.to_owned())
    };

    let target_bytes = parts[1];
    if target_bytes.is_empty()
        || target_bytes
            .iter()
            .any(|byte| *byte <= b' ' || *byte == 0x7f)
    {
        return Err(HttpError::InvalidTarget);
    }
    let target = latin1_to_string(target_bytes);

    let version = match parts.get(2) {
        None => Version::Http10,
        Some(value) if value.eq_ignore_ascii_case(b"HTTP/1.0") => Version::Http10,
        Some(value) if value.eq_ignore_ascii_case(b"HTTP/1.1") => Version::Http11,
        Some(_) => return Err(HttpError::UnsupportedVersion),
    };

    Ok((method, target, version))
}

fn strip_trailing_carriage_return(line: &[u8]) -> &[u8] {
    line.strip_suffix(b"\r").unwrap_or(line)
}

fn trim_optional_whitespace(value: &str) -> &str {
    value.trim_matches(|character| matches!(character, ' ' | '\t'))
}

fn trim_optional_whitespace_bytes(mut value: &[u8]) -> &[u8] {
    while value
        .first()
        .is_some_and(|byte| matches!(byte, b' ' | b'\t'))
    {
        value = &value[1..];
    }
    while value
        .last()
        .is_some_and(|byte| matches!(byte, b' ' | b'\t'))
    {
        value = &value[..value.len() - 1];
    }
    value
}

fn is_token_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric()
        || matches!(
            byte,
            b'!' | b'#'
                | b'$'
                | b'%'
                | b'&'
                | b'\''
                | b'*'
                | b'+'
                | b'-'
                | b'.'
                | b'^'
                | b'_'
                | b'`'
                | b'|'
                | b'~'
        )
}

fn is_valid_header_value_bytes(value: &[u8]) -> bool {
    value
        .iter()
        .all(|byte| *byte == b'\t' || *byte >= b' ' && *byte != 0x7f)
}

fn ascii_lowercase(value: &[u8]) -> String {
    value
        .iter()
        .map(|byte| char::from(byte.to_ascii_lowercase()))
        .collect()
}

fn latin1_to_string(value: &[u8]) -> String {
    value.iter().map(|byte| char::from(*byte)).collect()
}

/// The three supported forms of a single `bytes` range.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RangeSpec {
    /// `bytes=start-end`
    Bounded { start: u64, end_inclusive: u64 },
    /// `bytes=start-`
    From { start: u64 },
    /// `bytes=-length`
    Suffix { length: u64 },
}

impl RangeSpec {
    /// Parses exactly one byte range. Multiple ranges are intentionally rejected because the media
    /// proxy does not produce multipart responses.
    pub fn parse(value: &str) -> Result<Self, RangeError> {
        let value = trim_optional_whitespace(value);
        let Some((unit, range)) = value.split_once('=') else {
            return Err(RangeError::InvalidSyntax);
        };
        if !unit.eq_ignore_ascii_case("bytes") || range.contains(',') {
            return Err(RangeError::InvalidSyntax);
        }
        let Some((start, end)) = range.split_once('-') else {
            return Err(RangeError::InvalidSyntax);
        };
        if end.contains('-') {
            return Err(RangeError::InvalidSyntax);
        }

        match (start.is_empty(), end.is_empty()) {
            (true, true) => Err(RangeError::InvalidSyntax),
            (true, false) => {
                let length = parse_decimal_u64(end)?;
                if length == 0 {
                    Err(RangeError::InvalidSyntax)
                } else {
                    Ok(Self::Suffix { length })
                }
            }
            (false, true) => Ok(Self::From {
                start: parse_decimal_u64(start)?,
            }),
            (false, false) => {
                let start = parse_decimal_u64(start)?;
                let end_inclusive = parse_decimal_u64(end)?;
                if end_inclusive < start {
                    Err(RangeError::InvalidSyntax)
                } else {
                    Ok(Self::Bounded {
                        start,
                        end_inclusive,
                    })
                }
            }
        }
    }
}

fn parse_decimal_u64(value: &str) -> Result<u64, RangeError> {
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(RangeError::InvalidSyntax);
    }
    value.parse::<u64>().map_err(|_| RangeError::InvalidSyntax)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RangeError {
    InvalidSyntax,
    Unsatisfiable,
}

impl fmt::Display for RangeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidSyntax => formatter.write_str("invalid byte range syntax"),
            Self::Unsatisfiable => formatter.write_str("byte range is not satisfiable"),
        }
    }
}

impl Error for RangeError {}

/// An inclusive range resolved against a known representation size.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ByteRange {
    pub start: u64,
    pub end_inclusive: u64,
    pub content_len: u64,
}

impl ByteRange {
    fn new(start: u64, end_inclusive: u64) -> Self {
        debug_assert!(end_inclusive >= start);
        Self {
            start,
            end_inclusive,
            content_len: end_inclusive - start + 1,
        }
    }

    pub fn content_range_value(self, total_size: u64) -> String {
        format!("bytes {}-{}/{total_size}", self.start, self.end_inclusive)
    }

    pub fn request_header_value(self) -> String {
        format!("bytes={}-{}", self.start, self.end_inclusive)
    }
}

/// Resolves a parsed byte range against a known representation size.
///
/// Explicit end offsets are clamped to the final byte, suffixes larger than the resource select the
/// full resource, and open-ended requests are capped at [`MAX_OPEN_ENDED_RANGE_BYTES`].
pub fn plan_range(spec: RangeSpec, size: u64) -> Result<ByteRange, RangeError> {
    if size == 0 {
        return Err(RangeError::Unsatisfiable);
    }
    let last_byte = size - 1;
    match spec {
        RangeSpec::Bounded {
            start,
            end_inclusive,
        } => {
            if start >= size || end_inclusive < start {
                return Err(RangeError::Unsatisfiable);
            }
            Ok(ByteRange::new(start, end_inclusive.min(last_byte)))
        }
        RangeSpec::From { start } => {
            if start >= size {
                return Err(RangeError::Unsatisfiable);
            }
            let capped_end = start
                .saturating_add(MAX_OPEN_ENDED_RANGE_BYTES - 1)
                .min(last_byte);
            Ok(ByteRange::new(start, capped_end))
        }
        RangeSpec::Suffix { length } => {
            if length == 0 {
                return Err(RangeError::Unsatisfiable);
            }
            let selected_length = length.min(size);
            Ok(ByteRange::new(size - selected_length, last_byte))
        }
    }
}

/// Value for the response `Connection` header.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Connection {
    KeepAlive,
    Close,
}

impl Connection {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::KeepAlive => "keep-alive",
            Self::Close => "close",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HeaderError {
    InvalidName,
    InvalidValue,
}

impl fmt::Display for HeaderError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidName => formatter.write_str("invalid response header name"),
            Self::InvalidValue => formatter.write_str("invalid response header value"),
        }
    }
}

impl Error for HeaderError {}

/// Common response codes used by the engine.
pub mod status {
    pub const OK: u16 = 200;
    pub const PARTIAL_CONTENT: u16 = 206;
    pub const BAD_REQUEST: u16 = 400;
    pub const NOT_FOUND: u16 = 404;
    pub const METHOD_NOT_ALLOWED: u16 = 405;
    pub const REQUEST_TIMEOUT: u16 = 408;
    pub const RANGE_NOT_SATISFIABLE: u16 = 416;
    pub const REQUEST_HEADER_FIELDS_TOO_LARGE: u16 = 431;
    pub const INTERNAL_SERVER_ERROR: u16 = 500;
    pub const BAD_GATEWAY: u16 = 502;
    pub const SERVICE_UNAVAILABLE: u16 = 503;
}

/// Returns the conventional reason phrase for proxy response codes.
pub const fn reason_phrase(status_code: u16) -> &'static str {
    match status_code {
        100 => "Continue",
        200 => "OK",
        204 => "No Content",
        206 => "Partial Content",
        400 => "Bad Request",
        404 => "Not Found",
        405 => "Method Not Allowed",
        408 => "Request Timeout",
        413 => "Payload Too Large",
        416 => "Range Not Satisfiable",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        505 => "HTTP Version Not Supported",
        _ => "Unknown",
    }
}

/// Builder and serializer for an HTTP response head.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResponseHead {
    version: Version,
    status_code: u16,
    headers: Vec<Header>,
}

impl ResponseHead {
    /// Creates an HTTP/1.1 response head. Headers are added explicitly by the engine.
    pub fn new(status_code: u16) -> Self {
        Self {
            version: Version::Http11,
            status_code,
            headers: Vec::new(),
        }
    }

    /// Creates a zero-length response which closes the connection.
    pub fn empty(status_code: u16) -> Self {
        Self::new(status_code)
            .with_content_length(0)
            .with_connection(Connection::Close)
    }

    pub const fn version(&self) -> Version {
        self.version
    }

    pub const fn status_code(&self) -> u16 {
        self.status_code
    }

    pub fn headers(&self) -> &[Header] {
        &self.headers
    }

    pub fn with_version(mut self, version: Version) -> Self {
        self.version = version;
        self
    }

    /// Replaces all existing fields with the same case-insensitive name.
    pub fn set_header(
        &mut self,
        name: impl Into<String>,
        value: impl Into<String>,
    ) -> Result<(), HeaderError> {
        let name = name.into();
        let value = value.into();
        validate_response_header(&name, &value)?;
        self.headers
            .retain(|header| !header.name.eq_ignore_ascii_case(&name));
        self.headers.push(Header { name, value });
        Ok(())
    }

    /// Appends a field without coalescing it. This is useful for response fields that may repeat.
    pub fn append_header(
        &mut self,
        name: impl Into<String>,
        value: impl Into<String>,
    ) -> Result<(), HeaderError> {
        let name = name.into();
        let value = value.into();
        validate_response_header(&name, &value)?;
        self.headers.push(Header { name, value });
        Ok(())
    }

    pub fn with_header(
        mut self,
        name: impl Into<String>,
        value: impl Into<String>,
    ) -> Result<Self, HeaderError> {
        self.set_header(name, value)?;
        Ok(self)
    }

    pub fn with_content_length(mut self, content_len: u64) -> Self {
        self.set_trusted_header("Content-Length", content_len.to_string());
        self
    }

    pub fn with_content_range(mut self, range: ByteRange, total_size: u64) -> Self {
        self.set_trusted_header("Content-Range", range.content_range_value(total_size));
        self
    }

    pub fn with_unsatisfied_content_range(mut self, total_size: u64) -> Self {
        self.set_trusted_header("Content-Range", format!("bytes */{total_size}"));
        self
    }

    pub fn with_connection(mut self, connection: Connection) -> Self {
        self.set_trusted_header("Connection", connection.as_str().to_owned());
        self
    }

    /// Appends the status line, all fields, and the terminating empty line to `output`.
    pub fn write_to(&self, output: &mut Vec<u8>) {
        output.extend_from_slice(self.version.as_str().as_bytes());
        output.push(b' ');
        output.extend_from_slice(self.status_code.to_string().as_bytes());
        output.push(b' ');
        output.extend_from_slice(reason_phrase(self.status_code).as_bytes());
        output.extend_from_slice(b"\r\n");
        for header in &self.headers {
            output.extend_from_slice(header.name.as_bytes());
            output.extend_from_slice(b": ");
            output.extend_from_slice(header.value.as_bytes());
            output.extend_from_slice(b"\r\n");
        }
        output.extend_from_slice(b"\r\n");
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut output = Vec::new();
        self.write_to(&mut output);
        output
    }

    fn set_trusted_header(&mut self, name: &'static str, value: String) {
        self.headers
            .retain(|header| !header.name.eq_ignore_ascii_case(name));
        self.headers.push(Header {
            name: name.to_owned(),
            value,
        });
    }
}

fn validate_response_header(name: &str, value: &str) -> Result<(), HeaderError> {
    if name.is_empty() || !name.bytes().all(is_token_byte) {
        return Err(HeaderError::InvalidName);
    }
    if value
        .bytes()
        .any(|byte| byte != b'\t' && (byte < b' ' || byte == 0x7f))
    {
        return Err(HeaderError::InvalidValue);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_get_and_reports_consumed_bytes() {
        let input = b"GET /stream/id/video.mp4?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=4-9\r\n\r\nNEXT";
        let (request, consumed) = parse_request(input, 16 * 1024).unwrap();

        assert_eq!(request.method, Method::Get);
        assert_eq!(request.target, "/stream/id/video.mp4?x=1");
        assert_eq!(request.version, Version::Http11);
        assert_eq!(request.header("HOST"), Some("127.0.0.1"));
        assert_eq!(request.header("range"), Some("bytes=4-9"));
        assert_eq!(&input[consumed..], b"NEXT");
    }

    #[test]
    fn parses_head_with_lf_terminator_and_legacy_default_version() {
        let (request, consumed) =
            parse_request(b"head /stream/id\nX-Test: yes\n\nbody", 128).unwrap();

        assert_eq!(request.method, Method::Head);
        assert_eq!(request.version, Version::Http10);
        assert_eq!(request.header("x-test"), Some("yes"));
        assert_eq!(consumed, 29);
    }

    #[test]
    fn distinguishes_incomplete_and_oversized_request_heads() {
        assert_eq!(
            parse_request(b"GET / HTTP/1.1\r\n", 64),
            Err(HttpError::Incomplete)
        );
        assert_eq!(
            parse_request(b"GET / HTTP/1.1\r\n", 8),
            Err(HttpError::HeaderTooLarge { max: 8 })
        );
        assert_eq!(
            parse_request(b"GET / HTTP/1.1\r\n\r\n", 8),
            Err(HttpError::HeaderTooLarge { max: 8 })
        );
    }

    #[test]
    fn header_limit_applies_only_to_the_first_request() {
        let input = b"GET / HTTP/1.1\r\n\r\nthis trailing pipeline data can exceed the limit";
        let (_, consumed) = parse_request(input, 18).unwrap();
        assert_eq!(consumed, 18);
    }

    #[test]
    fn rejects_malformed_request_components() {
        assert_eq!(
            parse_request(b"GET\r\n\r\n", 64),
            Err(HttpError::MalformedRequestLine)
        );
        assert_eq!(
            parse_request(b"GET / HTTP/2\r\n\r\n", 64),
            Err(HttpError::UnsupportedVersion)
        );
        assert_eq!(
            parse_request(b"G(ET / HTTP/1.1\r\n\r\n", 64),
            Err(HttpError::InvalidMethod)
        );
        assert_eq!(
            parse_request(b"GET / HTTP/1.1\r\nBad Header: x\r\n\r\n", 64),
            Err(HttpError::InvalidHeader)
        );
        assert_eq!(
            parse_request(b"GET / HTTP/1.1\r\n folded\r\n\r\n", 64),
            Err(HttpError::InvalidHeader)
        );
    }

    #[test]
    fn preserves_unknown_valid_method_for_405() {
        let (request, _) = parse_request(b"POST / HTTP/1.1\r\n\r\n", 64).unwrap();
        assert_eq!(request.method, Method::Other("POST".to_owned()));
        assert!(!request.method.is_supported());
    }

    #[test]
    fn applies_http_keep_alive_rules_and_case_insensitive_tokens() {
        let (http11, _) = parse_request(b"GET / HTTP/1.1\r\n\r\n", 64).unwrap();
        assert!(http11.allows_persistent_connection());

        let (http11_close, _) =
            parse_request(b"GET / HTTP/1.1\r\nConnection: Upgrade, ClOsE\r\n\r\n", 128).unwrap();
        assert!(!http11_close.allows_persistent_connection());

        let (http10, _) = parse_request(b"GET / HTTP/1.0\r\n\r\n", 64).unwrap();
        assert!(!http10.allows_persistent_connection());

        let (http10_keep_alive, _) = parse_request(
            b"GET / HTTP/1.0\r\nconnection: Upgrade\r\nCONNECTION: KeEp-AlIvE\r\n\r\n",
            128,
        )
        .unwrap();
        assert!(http10_keep_alive.should_keep_alive());
    }

    #[test]
    fn close_wins_over_keep_alive() {
        let (request, _) = parse_request(
            b"GET / HTTP/1.0\r\nConnection: keep-alive, close\r\n\r\n",
            128,
        )
        .unwrap();
        assert!(!request.allows_persistent_connection());
    }

    #[test]
    fn parses_all_single_byte_range_forms() {
        assert_eq!(
            RangeSpec::parse("bytes=10-19"),
            Ok(RangeSpec::Bounded {
                start: 10,
                end_inclusive: 19
            })
        );
        assert_eq!(
            RangeSpec::parse(" BYTES=10- \t"),
            Ok(RangeSpec::From { start: 10 })
        );
        assert_eq!(
            RangeSpec::parse("bytes=-25"),
            Ok(RangeSpec::Suffix { length: 25 })
        );
    }

    #[test]
    fn rejects_invalid_or_multiple_ranges() {
        for value in [
            "items=0-1",
            "bytes=",
            "bytes=-",
            "bytes=-0",
            "bytes=9-2",
            "bytes=1-2,4-5",
            "bytes=+1-2",
            "bytes=18446744073709551616-",
            "bytes =1-2",
        ] {
            assert_eq!(
                RangeSpec::parse(value),
                Err(RangeError::InvalidSyntax),
                "{value}"
            );
        }
    }

    #[test]
    fn plans_and_clamps_bounded_ranges() {
        assert_eq!(
            plan_range(
                RangeSpec::Bounded {
                    start: 10,
                    end_inclusive: 19,
                },
                100,
            ),
            Ok(ByteRange {
                start: 10,
                end_inclusive: 19,
                content_len: 10,
            })
        );
        assert_eq!(
            plan_range(
                RangeSpec::Bounded {
                    start: 90,
                    end_inclusive: 999,
                },
                100,
            ),
            Ok(ByteRange {
                start: 90,
                end_inclusive: 99,
                content_len: 10,
            })
        );
    }

    #[test]
    fn caps_open_ended_ranges_at_eight_mib() {
        let large_size = MAX_OPEN_ENDED_RANGE_BYTES * 3;
        assert_eq!(
            plan_range(RangeSpec::From { start: 7 }, large_size),
            Ok(ByteRange {
                start: 7,
                end_inclusive: 7 + MAX_OPEN_ENDED_RANGE_BYTES - 1,
                content_len: MAX_OPEN_ENDED_RANGE_BYTES,
            })
        );
        assert_eq!(
            plan_range(
                RangeSpec::From {
                    start: large_size - 4
                },
                large_size
            ),
            Ok(ByteRange {
                start: large_size - 4,
                end_inclusive: large_size - 1,
                content_len: 4,
            })
        );
    }

    #[test]
    fn plans_suffix_ranges() {
        assert_eq!(
            plan_range(RangeSpec::Suffix { length: 25 }, 100),
            Ok(ByteRange {
                start: 75,
                end_inclusive: 99,
                content_len: 25,
            })
        );
        assert_eq!(
            plan_range(RangeSpec::Suffix { length: 250 }, 100),
            Ok(ByteRange {
                start: 0,
                end_inclusive: 99,
                content_len: 100,
            })
        );
    }

    #[test]
    fn rejects_unsatisfiable_ranges() {
        assert_eq!(
            plan_range(RangeSpec::From { start: 0 }, 0),
            Err(RangeError::Unsatisfiable)
        );
        assert_eq!(
            plan_range(RangeSpec::From { start: 100 }, 100),
            Err(RangeError::Unsatisfiable)
        );
        assert_eq!(
            plan_range(RangeSpec::Suffix { length: 0 }, 100),
            Err(RangeError::Unsatisfiable)
        );
    }

    #[test]
    fn serializes_partial_response_head() {
        let range = ByteRange {
            start: 10,
            end_inclusive: 19,
            content_len: 10,
        };
        let mut response = ResponseHead::new(status::PARTIAL_CONTENT)
            .with_content_length(range.content_len)
            .with_content_range(range, 100)
            .with_connection(Connection::KeepAlive);
        response.set_header("Content-Type", "video/mp4").unwrap();

        assert_eq!(
            response.to_bytes(),
            b"HTTP/1.1 206 Partial Content\r\nContent-Length: 10\r\nContent-Range: bytes 10-19/100\r\nConnection: keep-alive\r\nContent-Type: video/mp4\r\n\r\n"
        );
    }

    #[test]
    fn serializes_empty_error_and_unsatisfied_content_range() {
        let response = ResponseHead::empty(status::RANGE_NOT_SATISFIABLE)
            .with_unsatisfied_content_range(1234)
            .with_version(Version::Http10);
        assert_eq!(
            response.to_bytes(),
            b"HTTP/1.0 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\nContent-Range: bytes */1234\r\n\r\n"
        );
    }

    #[test]
    fn response_header_replacement_is_case_insensitive() {
        let mut response = ResponseHead::new(status::OK);
        response.set_header("content-type", "first").unwrap();
        response.set_header("Content-Type", "second").unwrap();
        assert_eq!(response.headers().len(), 1);
        assert_eq!(response.headers()[0].value, "second");
    }

    #[test]
    fn response_headers_reject_injection_and_bad_names() {
        let mut response = ResponseHead::new(status::OK);
        assert_eq!(
            response.set_header("Bad Header", "value"),
            Err(HeaderError::InvalidName)
        );
        assert_eq!(
            response.set_header("X-Test", "safe\r\nInjected: yes"),
            Err(HeaderError::InvalidValue)
        );
    }

    #[test]
    fn byte_range_formats_request_and_content_range_values() {
        let range = ByteRange {
            start: 4,
            end_inclusive: 9,
            content_len: 6,
        };
        assert_eq!(range.request_header_value(), "bytes=4-9");
        assert_eq!(range.content_range_value(20), "bytes 4-9/20");
    }
}
