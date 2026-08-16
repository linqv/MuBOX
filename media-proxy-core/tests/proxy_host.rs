use media_proxy_core::engine::{MediaStream, ProxyConfig, ProxyEngine};
use media_proxy_core::error::ProxyError;
use media_proxy_core::transport::{FetchMetadata, FetchMode, NetworkBridge};
use media_proxy_core::{OPEN_ENDED_BYTES, SEGMENT_BYTES};
use std::collections::HashMap;
use std::io::{ErrorKind, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

struct FetchState {
    bytes: Vec<u8>,
    cursor: usize,
}

type OpenedRange = (FetchMode, Option<u64>, Option<u64>);

struct MockBridge {
    data: Arc<Vec<u8>>,
    opens: AtomicUsize,
    heads: AtomicUsize,
    cancels: Mutex<Vec<u64>>,
    closes: Mutex<Vec<u64>>,
    opened_ranges: Mutex<Vec<OpenedRange>>,
    fetches: Mutex<HashMap<u64, FetchState>>,
    open_delay: Duration,
}

impl MockBridge {
    fn new(data: Vec<u8>) -> Arc<Self> {
        Arc::new(Self {
            data: Arc::new(data),
            opens: AtomicUsize::new(0),
            heads: AtomicUsize::new(0),
            cancels: Mutex::new(Vec::new()),
            closes: Mutex::new(Vec::new()),
            opened_ranges: Mutex::new(Vec::new()),
            fetches: Mutex::new(HashMap::new()),
            open_delay: Duration::ZERO,
        })
    }

    fn with_open_delay(data: Vec<u8>, open_delay: Duration) -> Arc<Self> {
        Arc::new(Self {
            data: Arc::new(data),
            opens: AtomicUsize::new(0),
            heads: AtomicUsize::new(0),
            cancels: Mutex::new(Vec::new()),
            closes: Mutex::new(Vec::new()),
            opened_ranges: Mutex::new(Vec::new()),
            fetches: Mutex::new(HashMap::new()),
            open_delay,
        })
    }
}

impl NetworkBridge for MockBridge {
    fn head(&self) -> Result<Option<u64>, ProxyError> {
        self.heads.fetch_add(1, Ordering::Relaxed);
        Ok(Some(self.data.len() as u64))
    }

    fn open_fetch(
        &self,
        request_id: u64,
        start: Option<u64>,
        end_inclusive: Option<u64>,
        mode: FetchMode,
    ) -> Result<FetchMetadata, ProxyError> {
        self.opens.fetch_add(1, Ordering::Relaxed);
        self.opened_ranges
            .lock()
            .unwrap()
            .push((mode, start, end_inclusive));
        if !self.open_delay.is_zero() {
            thread::sleep(self.open_delay);
        }
        let total = self.data.len() as u64;
        let (start, end_inclusive, status) = match mode {
            FetchMode::Full => (0, total.saturating_sub(1), 200),
            FetchMode::Range => (
                start.ok_or_else(|| ProxyError::Transport("missing range start".to_string()))?,
                end_inclusive
                    .ok_or_else(|| ProxyError::Transport("missing range end".to_string()))?,
                206,
            ),
        };
        let bytes = if total == 0 {
            Vec::new()
        } else {
            self.data[start as usize..=end_inclusive as usize].to_vec()
        };
        self.fetches
            .lock()
            .unwrap()
            .insert(request_id, FetchState { bytes, cursor: 0 });
        Ok(FetchMetadata {
            status,
            content_length: Some(if total == 0 {
                0
            } else {
                end_inclusive - start + 1
            }),
            content_range_start: (mode == FetchMode::Range).then_some(start),
            content_range_end: (mode == FetchMode::Range).then_some(end_inclusive),
            total_size: Some(total),
        })
    }

    fn read_fetch_into(
        &self,
        request_id: u64,
        target: &mut [u8],
    ) -> Result<Option<usize>, ProxyError> {
        let mut fetches = self.fetches.lock().unwrap();
        let state = fetches
            .get_mut(&request_id)
            .ok_or_else(|| ProxyError::Transport("fetch was closed".to_string()))?;
        if state.cursor == state.bytes.len() {
            return Ok(None);
        }
        let count = target.len().min(state.bytes.len() - state.cursor);
        target[..count].copy_from_slice(&state.bytes[state.cursor..state.cursor + count]);
        state.cursor += count;
        Ok(Some(count))
    }

    fn cancel_fetch(&self, request_id: u64) {
        self.cancels.lock().unwrap().push(request_id);
    }

    fn close_fetch(&self, request_id: u64) {
        self.closes.lock().unwrap().push(request_id);
        self.fetches.lock().unwrap().remove(&request_id);
    }
}

struct BlockingBridge {
    state: Mutex<BlockingState>,
    changed: Condvar,
}

#[derive(Default)]
struct BlockingState {
    opened: bool,
    reading: bool,
    cancelled: bool,
    closed: bool,
}

impl BlockingBridge {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(BlockingState::default()),
            changed: Condvar::new(),
        })
    }

    fn wait_until_reading(&self) {
        let deadline = Instant::now() + Duration::from_secs(2);
        let mut state = self.state.lock().unwrap();
        while !state.reading {
            let remaining = deadline.saturating_duration_since(Instant::now());
            assert!(!remaining.is_zero(), "proxy never entered bridge read");
            (state, _) = self.changed.wait_timeout(state, remaining).unwrap();
        }
    }
}

impl NetworkBridge for BlockingBridge {
    fn head(&self) -> Result<Option<u64>, ProxyError> {
        Ok(Some(1024))
    }

    fn open_fetch(
        &self,
        _request_id: u64,
        _start: Option<u64>,
        _end_inclusive: Option<u64>,
        _mode: FetchMode,
    ) -> Result<FetchMetadata, ProxyError> {
        self.state.lock().unwrap().opened = true;
        Ok(FetchMetadata {
            status: 200,
            content_length: Some(1024),
            content_range_start: None,
            content_range_end: None,
            total_size: Some(1024),
        })
    }

    fn read_fetch_into(
        &self,
        _request_id: u64,
        _target: &mut [u8],
    ) -> Result<Option<usize>, ProxyError> {
        let mut state = self.state.lock().unwrap();
        state.reading = true;
        self.changed.notify_all();
        while !state.cancelled {
            state = self.changed.wait(state).unwrap();
        }
        Err(ProxyError::Closed)
    }

    fn cancel_fetch(&self, _request_id: u64) {
        let mut state = self.state.lock().unwrap();
        state.cancelled = true;
        self.changed.notify_all();
    }

    fn close_fetch(&self, _request_id: u64) {
        self.state.lock().unwrap().closed = true;
    }
}

struct BodyLengthBridge {
    body: Vec<u8>,
    cursor: Mutex<usize>,
}

impl BodyLengthBridge {
    fn new(body: Vec<u8>) -> Arc<Self> {
        Arc::new(Self {
            body,
            cursor: Mutex::new(0),
        })
    }
}

impl NetworkBridge for BodyLengthBridge {
    fn head(&self) -> Result<Option<u64>, ProxyError> {
        Ok(Some(4))
    }

    fn open_fetch(
        &self,
        _request_id: u64,
        _start: Option<u64>,
        _end_inclusive: Option<u64>,
        _mode: FetchMode,
    ) -> Result<FetchMetadata, ProxyError> {
        *self.cursor.lock().unwrap() = 0;
        Ok(FetchMetadata {
            status: 200,
            content_length: Some(4),
            content_range_start: None,
            content_range_end: None,
            total_size: Some(4),
        })
    }

    fn read_fetch_into(
        &self,
        _request_id: u64,
        target: &mut [u8],
    ) -> Result<Option<usize>, ProxyError> {
        let mut cursor = self.cursor.lock().unwrap();
        if *cursor == self.body.len() {
            return Ok(None);
        }
        let count = target.len().min(self.body.len() - *cursor);
        target[..count].copy_from_slice(&self.body[*cursor..*cursor + count]);
        *cursor += count;
        Ok(Some(count))
    }

    fn cancel_fetch(&self, _request_id: u64) {}

    fn close_fetch(&self, _request_id: u64) {}
}

struct Fixture {
    engine: Arc<ProxyEngine>,
    stream: Arc<MediaStream>,
    port: u16,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        self.engine.close();
    }
}

fn fixture(
    bridge: Arc<dyn NetworkBridge>,
    size: Option<u64>,
    seek_enabled: bool,
    cache_bytes: u64,
    forward_prefetch_chunks: usize,
) -> Fixture {
    fixture_with_config(
        bridge,
        size,
        seek_enabled,
        forward_prefetch_chunks,
        ProxyConfig {
            cache_bytes,
            port_start: 0,
            port_end: 0,
            header_timeout: Duration::from_secs(2),
            max_header_bytes: 16 * 1024,
            max_requests_per_connection: 64,
            max_connections: 8,
        },
    )
}

fn fixture_with_config(
    bridge: Arc<dyn NetworkBridge>,
    size: Option<u64>,
    seek_enabled: bool,
    forward_prefetch_chunks: usize,
    config: ProxyConfig,
) -> Fixture {
    let engine = ProxyEngine::new(config).unwrap();
    let stream = engine
        .register_stream(
            bridge,
            "route-token".to_string(),
            size,
            "video/mp4".to_string(),
            seek_enabled,
            forward_prefetch_chunks,
        )
        .unwrap();
    let port = engine.start().unwrap();
    Fixture {
        engine,
        stream,
        port,
    }
}

fn request(port: u16, request: &str) -> Vec<u8> {
    let mut socket = TcpStream::connect(("127.0.0.1", port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(4)))
        .unwrap();
    socket.write_all(request.as_bytes()).unwrap();
    socket.shutdown(Shutdown::Write).unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).unwrap();
    response
}

fn split_response(response: &[u8]) -> (&str, &[u8]) {
    let boundary = response
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("response head terminator");
    let head = std::str::from_utf8(&response[..boundary + 4]).unwrap();
    (head, &response[boundary + 4..])
}

fn patterned_data(length: usize) -> Vec<u8> {
    (0..length).map(|index| (index % 251) as u8).collect()
}

fn read_head(socket: &mut TcpStream) -> String {
    let mut bytes = Vec::new();
    let mut byte = [0u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        socket.read_exact(&mut byte).unwrap();
        bytes.push(byte[0]);
    }
    String::from_utf8(bytes).unwrap()
}

#[test]
fn serves_head_full_get_and_all_single_range_forms() {
    let data = patterned_data(9 * 1024 * 1024);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        false,
        4 * 1024 * 1024,
        0,
    );

    let response = request(
        fixture.port,
        "HEAD /stream/route-token/movie.mp4 HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert!(head.starts_with("HTTP/1.1 200 OK"));
    assert!(head.contains(&format!("Content-Length: {}", data.len())));
    assert!(head.contains("Accept-Ranges: bytes"));
    assert!(body.is_empty());
    assert_eq!(bridge.heads.load(Ordering::Relaxed), 0);

    let response = request(
        fixture.port,
        "GET /stream/route-token/movie.mp4 HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert!(head.starts_with("HTTP/1.1 200 OK"));
    assert_eq!(body, data.as_slice());

    let response = request(
        fixture.port,
        "GET /stream/route-token/movie.mp4 HTTP/1.1\r\nRange: bytes=7-23\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert!(head.starts_with("HTTP/1.1 206 Partial Content"));
    assert!(head.contains(&format!("Content-Range: bytes 7-23/{}", data.len())));
    assert_eq!(body, &data[7..=23]);

    let response = request(
        fixture.port,
        "GET /stream/route-token/movie.mp4 HTTP/1.1\r\nRange: bytes=-11\r\nConnection: close\r\n\r\n",
    );
    let (_, body) = split_response(&response);
    assert_eq!(body, &data[data.len() - 11..]);

    let response = request(
        fixture.port,
        "GET /stream/route-token/movie.mp4 HTTP/1.1\r\nRange: bytes=100-\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert_eq!(body.len(), OPEN_ENDED_BYTES as usize);
    assert_eq!(body, &data[100..100 + OPEN_ENDED_BYTES as usize]);
    assert!(head.contains(&format!(
        "Content-Range: bytes 100-{}/{}",
        100 + OPEN_ENDED_BYTES - 1,
        data.len()
    )));
}

#[test]
fn maps_paths_methods_and_invalid_ranges_to_empty_errors() {
    let data = patterned_data(1024);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    let cases = [
        ("GET /missing HTTP/1.1\r\n\r\n", "404 Not Found"),
        (
            "POST /stream/route-token/a HTTP/1.1\r\n\r\n",
            "405 Method Not Allowed",
        ),
        (
            "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=2000-3000\r\n\r\n",
            "416 Range Not Satisfiable",
        ),
        (
            "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-1,3-4\r\n\r\n",
            "416 Range Not Satisfiable",
        ),
        (
            "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-1\r\nRange: bytes=2-3\r\n\r\n",
            "416 Range Not Satisfiable",
        ),
    ];
    for (request_text, expected) in cases {
        let response = request(fixture.port, request_text);
        let (head, body) = split_response(&response);
        assert!(head.contains(expected), "{head}");
        assert!(head.contains("Content-Length: 0"));
        assert!(head.contains("Connection: close"));
        assert!(body.is_empty());
    }
}

#[test]
fn supports_http_11_and_opt_in_http_10_keep_alive() {
    let data = patterned_data(100);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.1\r\n\r\n")
        .unwrap();
    let first = read_head(&mut socket);
    assert!(first.contains("Connection: keep-alive"));
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
        .unwrap();
    let second = read_head(&mut socket);
    assert!(second.contains("Connection: keep-alive"));
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.0\r\n\r\n")
        .unwrap();
    let third = read_head(&mut socket);
    assert!(third.contains("Connection: close"));
    let mut byte = [0u8; 1];
    assert_eq!(socket.read(&mut byte).unwrap(), 0);
}

#[test]
fn enforces_header_and_per_connection_request_limits() {
    let data = patterned_data(100);
    let config = ProxyConfig {
        cache_bytes: 0,
        port_start: 0,
        port_end: 0,
        header_timeout: Duration::from_secs(1),
        max_header_bytes: 80,
        max_requests_per_connection: 1,
        max_connections: 8,
    };
    let fixture = fixture_with_config(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        config,
    );
    let oversized = format!(
        "GET /stream/route-token/a HTTP/1.1\r\nX-Fill: {}\r\n\r\n",
        "x".repeat(100)
    );
    let response = request(fixture.port, &oversized);
    let (head, _) = split_response(&response);
    assert!(head.contains("431 Request Header Fields Too Large"));

    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.1\r\n\r\n")
        .unwrap();
    let head = read_head(&mut socket);
    assert!(head.contains("Connection: close"));
    let mut byte = [0u8; 1];
    assert_eq!(socket.read(&mut byte).unwrap(), 0);
}

#[test]
fn rejects_connections_over_the_configured_limit() {
    let data = patterned_data(100);
    let config = ProxyConfig {
        cache_bytes: 0,
        port_start: 0,
        port_end: 0,
        header_timeout: Duration::from_secs(2),
        max_header_bytes: 1024,
        max_requests_per_connection: 4,
        max_connections: 1,
    };
    let fixture = fixture_with_config(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        config,
    );
    let first = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    thread::sleep(Duration::from_millis(40));
    let mut second = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    second
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    let _ = second.write_all(b"HEAD /stream/route-token/a HTTP/1.1\r\n\r\n");
    let mut byte = [0u8; 1];
    match second.read(&mut byte) {
        Ok(0) => {}
        Err(error)
            if matches!(
                error.kind(),
                ErrorKind::ConnectionReset | ErrorKind::BrokenPipe
            ) => {}
        result => panic!("excess connection was not rejected: {result:?}"),
    }
    drop(first);
}

#[test]
fn same_segment_requests_merge_into_one_remote_fetch_and_then_hit_lru() {
    let data = patterned_data(SEGMENT_BYTES as usize * 2);
    let bridge = MockBridge::with_open_delay(data.clone(), Duration::from_millis(100));
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        true,
        SEGMENT_BYTES * 2,
        0,
    );
    let barrier = Arc::new(std::sync::Barrier::new(3));
    let mut workers = Vec::new();
    for (start, end) in [(1000usize, 400_000usize), (500_000usize, 900_000usize)] {
        let barrier = Arc::clone(&barrier);
        let port = fixture.port;
        workers.push(thread::spawn(move || {
            barrier.wait();
            let response = request(
                port,
                &format!(
                    "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes={start}-{end}\r\nConnection: close\r\n\r\n"
                ),
            );
            split_response(&response).1.len()
        }));
    }
    barrier.wait();
    assert_eq!(workers.remove(0).join().unwrap(), 399_001);
    assert_eq!(workers.remove(0).join().unwrap(), 400_001);
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 1);

    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=100-300000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, &data[100..=300_000]);
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 1);
    assert!(fixture.stream.stats_json().contains("\"memoryCacheHits\":"));
}

#[test]
fn small_range_streams_directly_while_warming_the_complete_segment() {
    let data = patterned_data(SEGMENT_BYTES as usize * 2);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        true,
        SEGMENT_BYTES * 2,
        0,
    );
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=4000-5000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, &data[4000..=5000]);
    let deadline = Instant::now() + Duration::from_secs(2);
    while bridge.opens.load(Ordering::Relaxed) < 2 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 2);

    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=6000-7000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, &data[6000..=7000]);
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 2);
}

#[test]
fn stream_close_cancels_an_open_fetch_and_unblocks_the_socket_worker() {
    let bridge = BlockingBridge::new();
    let fixture = fixture(bridge.clone(), Some(1024), false, 0, 0);
    let port = fixture.port;
    let client = thread::spawn(move || {
        request(
            port,
            "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
        )
    });
    bridge.wait_until_reading();
    assert!(fixture.stream.close());
    let response = client.join().unwrap();
    assert!(split_response(&response).0.starts_with("HTTP/1.1 200 OK"));
    let state = bridge.state.lock().unwrap();
    assert!(state.cancelled);
    assert!(state.closed);
}

#[test]
fn close_is_idempotent_and_start_cannot_restart_a_closed_proxy() {
    let data = patterned_data(16);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    assert_eq!(fixture.engine.start().unwrap(), fixture.port);
    fixture.engine.close();
    fixture.engine.close();
    assert_eq!(fixture.engine.start().unwrap_err(), ProxyError::Closed);
}

#[test]
fn unknown_size_is_resolved_once_and_cached_for_head_requests() {
    let data = patterned_data(512);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(bridge.clone(), None, false, 0, 0);
    for _ in 0..2 {
        let response = request(
            fixture.port,
            "HEAD /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
        );
        let (head, _) = split_response(&response);
        assert!(head.contains("Content-Length: 512"));
    }
    assert_eq!(bridge.heads.load(Ordering::Relaxed), 1);
}

#[test]
fn duplicate_route_tokens_are_rejected_without_replacing_the_original_stream() {
    let data = patterned_data(32);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(bridge.clone(), Some(data.len() as u64), false, 0, 0);
    assert!(fixture
        .engine
        .register_stream(
            bridge,
            "route-token".to_string(),
            Some(data.len() as u64),
            "video/mp4".to_string(),
            false,
            0,
        )
        .is_err());
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, data.as_slice());
}

#[test]
fn proxy_only_binds_ipv4_loopback() {
    let data = patterned_data(8);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    assert!(TcpStream::connect(("127.0.0.1", fixture.port)).is_ok());
    let ipv6 = TcpStream::connect_timeout(
        &format!("[::1]:{}", fixture.port).parse().unwrap(),
        Duration::from_millis(100),
    );
    assert!(ipv6.is_err());
}

#[test]
fn open_ended_streaming_caches_only_complete_segments() {
    let data = patterned_data(12 * 1024 * 1024);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        true,
        10 * 1024 * 1024,
        0,
    );
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(
        split_response(&response).1,
        &data[..OPEN_ENDED_BYTES as usize]
    );
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 1);

    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=2097152-4194303\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(
        split_response(&response).1,
        &data[2 * 1024 * 1024..4 * 1024 * 1024]
    );
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 1);
}

#[test]
fn malformed_headers_time_out_or_close_without_reaching_bridge() {
    let data = patterned_data(32);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(bridge.clone(), Some(data.len() as u64), false, 0, 0);
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/2\r\nConnection: close\r\n\r\n",
    );
    assert!(response.is_empty());
    assert_eq!(bridge.opens.load(Ordering::Relaxed), 0);
}

#[test]
fn bridge_close_is_called_once_after_a_successful_fetch() {
    let data = patterned_data(128);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(bridge.clone(), Some(data.len() as u64), false, 0, 0);
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, data.as_slice());
    assert_eq!(bridge.closes.lock().unwrap().len(), 1);
    assert!(bridge.cancels.lock().unwrap().is_empty());
}

#[test]
fn stream_close_is_idempotent() {
    let data = patterned_data(16);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    assert!(fixture.stream.close());
    assert!(!fixture.stream.close());
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    assert!(split_response(&response).0.contains("404 Not Found"));
}

#[test]
fn config_rejects_invalid_limits_and_port_ranges() {
    let invalid = ProxyConfig {
        cache_bytes: 0,
        port_start: 9000,
        port_end: 8000,
        header_timeout: Duration::ZERO,
        max_header_bytes: 0,
        max_requests_per_connection: 0,
        max_connections: 0,
    };
    assert!(ProxyEngine::new(invalid).is_err());
}

#[test]
fn proxy_close_cancels_active_streams() {
    let bridge = BlockingBridge::new();
    let fixture = fixture(bridge.clone(), Some(1024), false, 0, 0);
    let port = fixture.port;
    let client = thread::spawn(move || {
        request(
            port,
            "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
        )
    });
    bridge.wait_until_reading();
    fixture.engine.close();
    let _ = client.join().unwrap();
    let state = bridge.state.lock().unwrap();
    assert!(state.cancelled);
    assert!(state.closed);
}

#[test]
fn request_limit_boundary_allows_exactly_the_configured_count() {
    let data = patterned_data(10);
    let config = ProxyConfig {
        cache_bytes: 0,
        port_start: 0,
        port_end: 0,
        header_timeout: Duration::from_secs(1),
        max_header_bytes: 1024,
        max_requests_per_connection: 2,
        max_connections: 1,
    };
    let fixture = fixture_with_config(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        config,
    );
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.1\r\n\r\n")
        .unwrap();
    assert!(read_head(&mut socket).contains("Connection: keep-alive"));
    socket
        .write_all(b"HEAD /stream/route-token/a HTTP/1.1\r\n\r\n")
        .unwrap();
    assert!(read_head(&mut socket).contains("Connection: close"));
    let mut byte = [0u8; 1];
    assert_eq!(socket.read(&mut byte).unwrap(), 0);
}

#[test]
fn explicit_end_is_clamped_to_eof() {
    let data = patterned_data(100);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        0,
    );
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=90-9999\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert!(head.contains("Content-Range: bytes 90-99/100"));
    assert_eq!(body, &data[90..]);
}

#[test]
fn percent_encoded_route_token_is_decoded() {
    let data = patterned_data(10);
    let engine = ProxyEngine::new(ProxyConfig {
        cache_bytes: 0,
        port_start: 0,
        port_end: 0,
        header_timeout: Duration::from_secs(1),
        max_header_bytes: 1024,
        max_requests_per_connection: 2,
        max_connections: 2,
    })
    .unwrap();
    let stream = engine
        .register_stream(
            MockBridge::new(data.clone()),
            "route-token_2".to_string(),
            Some(10),
            "video/mp4".to_string(),
            false,
            0,
        )
        .unwrap();
    let port = engine.start().unwrap();
    let fixture = Fixture {
        engine,
        stream,
        port,
    };
    let response = request(
        fixture.port,
        "GET /stream/route%2Dtoken_2/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, data.as_slice());
}

#[test]
fn empty_file_full_get_is_supported_but_range_is_416() {
    let bridge = MockBridge::new(Vec::new());
    let fixture = fixture(bridge, Some(0), false, 0, 0);
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    let (head, body) = split_response(&response);
    assert!(head.starts_with("HTTP/1.1 200 OK"));
    assert!(body.is_empty());

    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-\r\n\r\n",
    );
    let (head, _) = split_response(&response);
    assert!(head.contains("416 Range Not Satisfiable"));
    assert!(head.contains("Content-Range: bytes */0"));
}

#[test]
fn stats_are_valid_shape_after_remote_and_cache_activity() {
    let data = patterned_data(SEGMENT_BYTES as usize);
    let fixture = fixture(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        true,
        SEGMENT_BYTES,
        0,
    );
    let _ = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-300000\r\nConnection: close\r\n\r\n",
    );
    let stats = fixture.stream.stats_json();
    assert!(stats.starts_with('{') && stats.ends_with('}'));
    assert!(stats.contains("\"currentRange\":\"bytes=0-300000\""));
    assert!(stats.contains("\"remoteHttpStatus\":206"));
    assert!(stats.contains("\"memoryCacheHits\":"));
    assert!(stats.contains("\"prefetchState\":"));
    assert!(stats.contains("\"diagnosticMessage\":"));
}

#[test]
fn malformed_mime_cannot_inject_response_headers() {
    let data = patterned_data(1);
    let engine = ProxyEngine::new(ProxyConfig {
        cache_bytes: 0,
        port_start: 0,
        port_end: 0,
        header_timeout: Duration::from_secs(1),
        max_header_bytes: 1024,
        max_requests_per_connection: 1,
        max_connections: 1,
    })
    .unwrap();
    let stream = engine
        .register_stream(
            MockBridge::new(data),
            "safe".to_string(),
            Some(1),
            "video/mp4\r\nX-Injected: yes".to_string(),
            false,
            0,
        )
        .unwrap();
    let port = engine.start().unwrap();
    let fixture = Fixture {
        engine,
        stream,
        port,
    };
    let response = request(
        fixture.port,
        "HEAD /stream/safe/a HTTP/1.1\r\nConnection: close\r\n\r\n",
    );
    let (head, _) = split_response(&response);
    assert!(head.contains("Content-Type: application/octet-stream"));
    assert!(!head.contains("X-Injected"));
}

#[test]
fn start_uses_requested_port_range_in_order() {
    let occupied = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let occupied_port = occupied.local_addr().unwrap().port();
    if occupied_port == u16::MAX {
        return;
    }
    let engine = ProxyEngine::new(ProxyConfig {
        cache_bytes: 0,
        port_start: occupied_port,
        port_end: occupied_port + 1,
        header_timeout: Duration::from_secs(1),
        max_header_bytes: 1024,
        max_requests_per_connection: 1,
        max_connections: 1,
    })
    .unwrap();
    let port = engine.start().unwrap();
    assert_eq!(port, occupied_port + 1);
    engine.close();
}

#[test]
fn forward_prefetch_reads_the_next_four_segments_sequentially_per_chunk() {
    let data = patterned_data(12 * 1024 * 1024);
    let bridge = MockBridge::new(data.clone());
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        true,
        12 * 1024 * 1024,
        1,
    );
    let response = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-300000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&response).1, &data[..=300_000]);

    let deadline = Instant::now() + Duration::from_secs(2);
    while bridge.opens.load(Ordering::Relaxed) < 5 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
    let ranges = bridge.opened_ranges.lock().unwrap().clone();
    assert_eq!(ranges.len(), 5);
    for (position, (_, start, end)) in ranges.iter().enumerate() {
        let index = position as u64;
        assert_eq!(*start, Some(index * SEGMENT_BYTES));
        assert_eq!(*end, Some((index + 1) * SEGMENT_BYTES - 1));
    }
}

#[test]
fn a_new_seek_cancels_non_overlapping_stale_prefetch() {
    let data = patterned_data(14 * 1024 * 1024);
    let bridge = MockBridge::with_open_delay(data.clone(), Duration::from_millis(80));
    let fixture = fixture(
        bridge.clone(),
        Some(data.len() as u64),
        true,
        14 * 1024 * 1024,
        1,
    );
    let first = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=0-300000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&first).1, &data[..=300_000]);
    let deadline = Instant::now() + Duration::from_secs(2);
    while bridge.opens.load(Ordering::Relaxed) < 2 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(5));
    }
    let second = request(
        fixture.port,
        "GET /stream/route-token/a HTTP/1.1\r\nRange: bytes=10485760-10800000\r\nConnection: close\r\n\r\n",
    );
    assert_eq!(split_response(&second).1, &data[10_485_760..=10_800_000]);
    let deadline = Instant::now() + Duration::from_secs(2);
    while bridge.cancels.lock().unwrap().is_empty() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(5));
    }
    assert!(!bridge.cancels.lock().unwrap().is_empty());
}

#[test]
fn short_or_long_remote_bodies_close_keep_alive_before_a_pipelined_request() {
    for body in [vec![1, 2, 3], vec![1, 2, 3, 4, 5]] {
        let fixture = fixture(BodyLengthBridge::new(body), Some(4), false, 0, 0);
        let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        socket
            .write_all(
                b"GET /stream/route-token/a HTTP/1.1\r\n\r\nHEAD /stream/route-token/a HTTP/1.1\r\nConnection: close\r\n\r\n",
            )
            .unwrap();
        socket.shutdown(Shutdown::Write).unwrap();
        let mut response = Vec::new();
        socket.read_to_end(&mut response).unwrap();
        assert_eq!(
            response
                .windows(b"HTTP/1.1".len())
                .filter(|window| *window == b"HTTP/1.1")
                .count(),
            1
        );
    }
}

#[test]
fn partial_headers_are_closed_after_the_configured_timeout() {
    let data = patterned_data(8);
    let fixture = fixture_with_config(
        MockBridge::new(data.clone()),
        Some(data.len() as u64),
        false,
        0,
        ProxyConfig {
            cache_bytes: 0,
            port_start: 0,
            port_end: 0,
            header_timeout: Duration::from_millis(50),
            max_header_bytes: 1024,
            max_requests_per_connection: 1,
            max_connections: 1,
        },
    );
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    socket.write_all(b"GET /stream/route-token/a").unwrap();
    let started = Instant::now();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).unwrap();
    assert!(response.is_empty());
    assert!(started.elapsed() >= Duration::from_millis(30));
}

#[test]
fn cancellation_flag_is_observable_in_blocking_bridge() {
    let bridge = BlockingBridge::new();
    assert!(!bridge.state.lock().unwrap().cancelled);
    bridge.cancel_fetch(1);
    assert!(bridge.state.lock().unwrap().cancelled);
}
