use crate::cache::{ByteLru, Segment, SegmentKey, SegmentSlice};
use crate::error::ProxyError;
use crate::http::{
    parse_request, plan_range, status, Connection, HttpError, Method, RangeSpec, Request,
    ResponseHead,
};
use crate::inflight::{InflightSegment, WaiterKind};
use crate::transport::{FetchMetadata, FetchMode, NetworkBridge};
use crate::{
    MAX_FORWARD_PREFETCH_CHUNKS, NATIVE_CHUNK_BYTES, OPEN_ENDED_BYTES, SEGMENT_BYTES,
    SMALL_RANGE_DIRECT_BYTES,
};
use std::collections::{HashMap, HashSet};
use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddrV4, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU16, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::thread::{self, JoinHandle};
use std::time::Duration;

static NEXT_STREAM_ID: AtomicU64 = AtomicU64::new(1);
static NEXT_CONNECTION_ID: AtomicU64 = AtomicU64::new(1);
type ChunkObserver<'a> = dyn Fn(u64, &[u8]) + 'a;

#[derive(Clone, Debug)]
pub struct ProxyConfig {
    pub cache_bytes: u64,
    pub port_start: u16,
    pub port_end: u16,
    pub header_timeout: Duration,
    pub max_header_bytes: usize,
    pub max_requests_per_connection: usize,
    pub max_connections: usize,
}

impl ProxyConfig {
    pub fn validate(&self) -> Result<(), ProxyError> {
        if self.port_start != 0 && self.port_end < self.port_start {
            return Err(ProxyError::InvalidArgument(
                "port end must be greater than or equal to port start".to_string(),
            ));
        }
        if self.port_start == 0 && self.port_end != 0 {
            return Err(ProxyError::InvalidArgument(
                "an ephemeral port requires both port bounds to be zero".to_string(),
            ));
        }
        if self.header_timeout.is_zero() {
            return Err(ProxyError::InvalidArgument(
                "header timeout must be positive".to_string(),
            ));
        }
        if self.max_header_bytes == 0
            || self.max_requests_per_connection == 0
            || self.max_connections == 0
        {
            return Err(ProxyError::InvalidArgument(
                "HTTP limits must be positive".to_string(),
            ));
        }
        Ok(())
    }
}

pub struct ProxyEngine {
    config: ProxyConfig,
    closed: AtomicBool,
    start_lock: Mutex<()>,
    port: AtomicU16,
    accept_thread: Mutex<Option<JoinHandle<()>>>,
    worker_threads: Mutex<Vec<JoinHandle<()>>>,
    background_threads: Mutex<Vec<JoinHandle<()>>>,
    active_connections: AtomicUsize,
    active_background_tasks: AtomicUsize,
    client_sockets: Mutex<HashMap<u64, TcpStream>>,
    routes: Mutex<HashMap<String, Arc<MediaStream>>>,
    cache: Mutex<ByteLru>,
    inflight: Mutex<HashMap<SegmentKey, Arc<InflightSegment>>>,
}

impl ProxyEngine {
    pub fn new(config: ProxyConfig) -> Result<Arc<Self>, ProxyError> {
        config.validate()?;
        let cache_bytes = config.cache_bytes;
        Ok(Arc::new(Self {
            config,
            closed: AtomicBool::new(false),
            start_lock: Mutex::new(()),
            port: AtomicU16::new(0),
            accept_thread: Mutex::new(None),
            worker_threads: Mutex::new(Vec::new()),
            background_threads: Mutex::new(Vec::new()),
            active_connections: AtomicUsize::new(0),
            active_background_tasks: AtomicUsize::new(0),
            client_sockets: Mutex::new(HashMap::new()),
            routes: Mutex::new(HashMap::new()),
            cache: Mutex::new(ByteLru::new(cache_bytes)),
            inflight: Mutex::new(HashMap::new()),
        }))
    }

    pub fn start(self: &Arc<Self>) -> Result<u16, ProxyError> {
        let _start = self
            .start_lock
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        let port = self.port.load(Ordering::Acquire);
        if port != 0 {
            return Ok(port);
        }

        let listener = self.bind_listener()?;
        listener.set_nonblocking(true)?;
        let port = listener.local_addr()?.port();
        let engine = Arc::clone(self);
        let accept_thread = thread::Builder::new()
            .name("media-proxy-accept".to_string())
            .spawn(move || engine.accept_loop(listener))
            .map_err(ProxyError::from)?;
        self.port.store(port, Ordering::Release);
        *self
            .accept_thread
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = Some(accept_thread);
        Ok(port)
    }

    pub fn port(&self) -> Option<u16> {
        match self.port.load(Ordering::Acquire) {
            0 => None,
            port => Some(port),
        }
    }

    pub fn register_stream(
        self: &Arc<Self>,
        bridge: Arc<dyn NetworkBridge>,
        route_token: String,
        size: Option<u64>,
        mime: String,
        seek_enabled: bool,
        forward_prefetch_chunks: usize,
    ) -> Result<Arc<MediaStream>, ProxyError> {
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        validate_route_token(&route_token)?;
        if forward_prefetch_chunks > MAX_FORWARD_PREFETCH_CHUNKS {
            return Err(ProxyError::InvalidArgument(format!(
                "forward prefetch chunks must not exceed {MAX_FORWARD_PREFETCH_CHUNKS}"
            )));
        }
        let id = NEXT_STREAM_ID.fetch_add(1, Ordering::Relaxed);
        if id == 0 {
            return Err(ProxyError::InvalidArgument(
                "stream handle space exhausted".to_string(),
            ));
        }
        let stream = Arc::new(MediaStream {
            id,
            proxy: Arc::downgrade(self),
            bridge,
            route_token: route_token.clone(),
            size: Mutex::new(size),
            mime: sanitize_mime(&mime),
            seek_enabled,
            forward_prefetch_chunks,
            closed: AtomicBool::new(false),
            next_request_id: AtomicU64::new(1),
            generation: AtomicU64::new(0),
            active_fetches: Mutex::new(HashMap::new()),
            stats: StreamStats::default(),
        });
        let mut routes = self
            .routes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        if routes.contains_key(&route_token) {
            return Err(ProxyError::InvalidArgument(
                "route token is already registered".to_string(),
            ));
        }
        routes.insert(route_token, Arc::clone(&stream));
        Ok(stream)
    }

    pub fn unregister_stream(&self, stream: &Arc<MediaStream>) -> bool {
        let removed = {
            let mut routes = self
                .routes
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            routes
                .get(&stream.route_token)
                .is_some_and(|registered| Arc::ptr_eq(registered, stream))
                .then(|| routes.remove(&stream.route_token))
                .flatten()
                .is_some()
        };
        stream.close_transport();
        self.cancel_stream_inflight(stream);
        self.cache
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove_stream(stream.id);
        removed
    }

    pub fn close(&self) {
        let accept_thread = {
            let _start = self
                .start_lock
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if self.closed.swap(true, Ordering::AcqRel) {
                return;
            }
            self.port.store(0, Ordering::Release);
            self.shutdown_client_sockets();
            self.accept_thread
                .lock()
                .unwrap_or_else(|poison| poison.into_inner())
                .take()
        };

        let streams = self
            .routes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .drain()
            .map(|(_, stream)| stream)
            .collect::<Vec<_>>();
        for stream in &streams {
            stream.close_transport();
        }
        let inflight = self
            .inflight
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .drain()
            .map(|(_, entry)| entry)
            .collect::<Vec<_>>();
        for entry in inflight {
            entry.cancel("proxy closed");
        }
        self.cache
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clear();

        if let Some(thread) = accept_thread {
            let _ = thread.join();
        }
        self.shutdown_client_sockets();
        join_all(&self.worker_threads);
        join_all(&self.background_threads);
        self.shutdown_client_sockets();
    }

    fn bind_listener(&self) -> Result<TcpListener, ProxyError> {
        let mut last_error = None;
        let ports: Box<dyn Iterator<Item = u16>> = if self.config.port_start == 0 {
            Box::new(std::iter::once(0))
        } else {
            Box::new(self.config.port_start..=self.config.port_end)
        };
        for port in ports {
            match TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port)) {
                Ok(listener) => return Ok(listener),
                Err(error) => last_error = Some(error),
            }
        }
        Err(ProxyError::Io(format!(
            "unable to bind video proxy port: {}",
            last_error
                .map(|error| error.to_string())
                .unwrap_or_else(|| "empty port range".to_string())
        )))
    }

    fn accept_loop(self: Arc<Self>, listener: TcpListener) {
        while !self.closed.load(Ordering::Acquire) {
            match listener.accept() {
                Ok((client, _)) => {
                    reap_finished(&self.worker_threads);
                    if self.closed.load(Ordering::Acquire) || !self.try_acquire_connection() {
                        let _ = client.shutdown(Shutdown::Both);
                        continue;
                    }
                    let connection_id = NEXT_CONNECTION_ID.fetch_add(1, Ordering::Relaxed);
                    match client.try_clone() {
                        Ok(clone) => {
                            self.client_sockets
                                .lock()
                                .unwrap_or_else(|poison| poison.into_inner())
                                .insert(connection_id, clone);
                        }
                        Err(_) => {
                            self.active_connections.fetch_sub(1, Ordering::AcqRel);
                            let _ = client.shutdown(Shutdown::Both);
                            continue;
                        }
                    }
                    let engine = Arc::clone(&self);
                    match thread::Builder::new()
                        .name(format!("media-proxy-connection-{connection_id}"))
                        .spawn(move || {
                            let _guard = ConnectionGuard {
                                engine: Arc::clone(&engine),
                                connection_id,
                            };
                            engine.handle_connection(client);
                        }) {
                        Ok(worker) => self
                            .worker_threads
                            .lock()
                            .unwrap_or_else(|poison| poison.into_inner())
                            .push(worker),
                        Err(_) => self.release_connection(connection_id),
                    }
                }
                Err(error) if error.kind() == ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(_) if self.closed.load(Ordering::Acquire) => break,
                Err(_) => thread::sleep(Duration::from_millis(10)),
            }
        }
    }

    fn try_acquire_connection(&self) -> bool {
        let mut current = self.active_connections.load(Ordering::Acquire);
        loop {
            if current >= self.config.max_connections {
                return false;
            }
            match self.active_connections.compare_exchange_weak(
                current,
                current + 1,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return true,
                Err(actual) => current = actual,
            }
        }
    }

    fn release_connection(&self, connection_id: u64) {
        if let Some(socket) = self
            .client_sockets
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(&connection_id)
        {
            let _ = socket.shutdown(Shutdown::Both);
            self.active_connections.fetch_sub(1, Ordering::AcqRel);
        }
    }

    fn shutdown_client_sockets(&self) {
        let sockets = self
            .client_sockets
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
            .filter_map(|socket| socket.try_clone().ok())
            .collect::<Vec<_>>();
        for socket in sockets {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }

    fn handle_connection(self: &Arc<Self>, mut socket: TcpStream) {
        let _ = socket.set_read_timeout(Some(self.config.header_timeout));
        let _ = socket.set_write_timeout(Some(self.config.header_timeout));
        for request_index in 0..self.config.max_requests_per_connection {
            let request = match read_request(&mut socket, self.config.max_header_bytes) {
                Ok(Some(request)) => request,
                Ok(None) | Err(ReadRequestError::Timeout) | Err(ReadRequestError::Malformed) => {
                    return;
                }
                Err(ReadRequestError::TooLarge) => {
                    let _ = write_head(
                        &mut socket,
                        ResponseHead::empty(status::REQUEST_HEADER_FIELDS_TOO_LARGE),
                    );
                    return;
                }
                Err(ReadRequestError::Io) => return,
            };
            let request_keep_alive = request.allows_persistent_connection()
                && request_index + 1 < self.config.max_requests_per_connection;
            if !self.handle_request(&mut socket, request, request_keep_alive) {
                return;
            }
        }
    }

    fn handle_request(
        self: &Arc<Self>,
        socket: &mut TcpStream,
        request: Request,
        request_keep_alive: bool,
    ) -> bool {
        let Some(route_token) = route_token_from_target(&request.target) else {
            let _ = write_head(socket, ResponseHead::empty(status::NOT_FOUND));
            return false;
        };
        let stream = self
            .routes
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&route_token)
            .cloned();
        let Some(stream) = stream else {
            let _ = write_head(socket, ResponseHead::empty(status::NOT_FOUND));
            return false;
        };
        let range_header_count = request
            .headers
            .iter()
            .filter(|header| header.name.eq_ignore_ascii_case("range"))
            .count();
        let range_header = match range_header_count {
            0 => None,
            1 => request.header("range").map(str::to_owned),
            _ => Some("multiple-range-fields".to_string()),
        };
        match request.method {
            Method::Head => self.handle_head(socket, &stream, request_keep_alive),
            Method::Get => {
                self.handle_get(socket, &stream, range_header.as_deref(), request_keep_alive)
            }
            Method::Other(_) => {
                let _ = write_head(socket, ResponseHead::empty(status::METHOD_NOT_ALLOWED));
                false
            }
        }
    }

    fn handle_head(
        &self,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        request_keep_alive: bool,
    ) -> bool {
        let size = match stream.resolve_size() {
            Ok(size) => size,
            Err(ProxyError::Closed) => return false,
            Err(_) => {
                let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
                return false;
            }
        };
        let keep_alive = request_keep_alive && size.is_some();
        let mut head = media_head(status::OK, stream, keep_alive);
        if let Some(size) = size {
            head = head.with_content_length(size);
        }
        write_head(socket, head).is_ok() && keep_alive
    }

    fn handle_get(
        self: &Arc<Self>,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        range_header: Option<&str>,
        request_keep_alive: bool,
    ) -> bool {
        let size = match stream.resolve_size() {
            Ok(size) => size,
            Err(ProxyError::Closed) => return false,
            Err(_) => {
                let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
                return false;
            }
        };

        let Some(range_header) = range_header else {
            stream.stats.set_current_range(None);
            return self.handle_full_get(socket, stream, size, request_keep_alive);
        };
        let Some(total_size) = size else {
            let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
            return false;
        };
        let spec = match RangeSpec::parse(range_header) {
            Ok(spec) => spec,
            Err(_) => {
                let _ = write_head(
                    socket,
                    ResponseHead::empty(status::RANGE_NOT_SATISFIABLE)
                        .with_unsatisfied_content_range(total_size),
                );
                return false;
            }
        };
        let range = match plan_range(spec, total_size) {
            Ok(range) => range,
            Err(_) => {
                let _ = write_head(
                    socket,
                    ResponseHead::empty(status::RANGE_NOT_SATISFIABLE)
                        .with_unsatisfied_content_range(total_size),
                );
                return false;
            }
        };
        stream
            .stats
            .set_current_range(Some(range.request_header_value()));
        let is_open_ended = matches!(spec, RangeSpec::From { .. });
        let seek_eligible = matches!(spec, RangeSpec::Bounded { .. })
            && range.content_len <= SEGMENT_BYTES
            && range.start / SEGMENT_BYTES == range.end_inclusive / SEGMENT_BYTES;
        if stream.seek_enabled && seek_eligible {
            self.handle_optimized_range(socket, stream, range, total_size, request_keep_alive)
        } else if stream.seek_enabled && is_open_ended {
            self.handle_open_ended_range(socket, stream, range, total_size, request_keep_alive)
        } else {
            self.handle_direct_range(socket, stream, range, total_size, request_keep_alive, None)
        }
    }

    fn handle_full_get(
        &self,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        known_size: Option<u64>,
        request_keep_alive: bool,
    ) -> bool {
        let (fetch, metadata) =
            match stream.open_fetch(None, None, FetchMode::Full, FetchKind::Foreground) {
                Ok(fetch) => fetch,
                Err(ProxyError::Closed) => return false,
                Err(_) => {
                    let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
                    return false;
                }
            };
        let effective_size = known_size
            .or(metadata.total_size)
            .or(metadata.content_length);
        if !valid_full_metadata(&metadata, effective_size) {
            let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
            return false;
        }
        if known_size.is_none() {
            stream.store_size(effective_size);
        }
        let expected = effective_size.or(metadata.content_length);
        let keep_alive = request_keep_alive && expected.is_some();
        let mut head = media_head(status::OK, stream, keep_alive);
        if let Some(length) = expected {
            head = head.with_content_length(length);
        }
        if write_head(socket, head).is_err() {
            return false;
        }
        stream_fetch_to_writer(stream, fetch, socket, expected, keep_alive, |_, _| {}).is_ok()
            && keep_alive
    }

    fn handle_optimized_range(
        self: &Arc<Self>,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        range: crate::http::ByteRange,
        total_size: u64,
        request_keep_alive: bool,
    ) -> bool {
        let segment_index = range.start / SEGMENT_BYTES;
        let generation = self.begin_foreground(stream, &[segment_index]);
        let key = SegmentKey {
            stream_id: stream.id,
            segment_index,
        };
        if let Some(slice) = self.cached_slice(key, range.start, range.end_inclusive, stream) {
            self.schedule_forward_prefetch(stream, segment_index, total_size, generation);
            return write_cached_range(
                socket,
                stream,
                range,
                total_size,
                request_keep_alive,
                &[slice],
            );
        }

        if range.content_len <= SMALL_RANGE_DIRECT_BYTES {
            let existing = self
                .inflight
                .lock()
                .unwrap_or_else(|poison| poison.into_inner())
                .get(&key)
                .cloned();
            if existing.is_none() {
                stream.stats.set_diagnostic(Some(format!(
                    "small_range_direct range={}-{}",
                    range.start, range.end_inclusive
                )));
                self.spawn_segment_batch(stream, vec![segment_index], total_size, generation, true);
                let result = self.handle_direct_range(
                    socket,
                    stream,
                    range,
                    total_size,
                    request_keep_alive,
                    None,
                );
                self.schedule_forward_prefetch(stream, segment_index, total_size, generation);
                return result;
            }
        }

        let segment = match self.get_or_fetch_segment(
            stream,
            segment_index,
            total_size,
            WaiterKind::Foreground,
            generation,
        ) {
            Ok(segment) => segment,
            Err(ProxyError::Closed) => return false,
            Err(_) => {
                return self.handle_direct_range(
                    socket,
                    stream,
                    range,
                    total_size,
                    request_keep_alive,
                    None,
                );
            }
        };
        let Some(slice) = segment.slice(range.start, range.end_inclusive) else {
            return self.handle_direct_range(
                socket,
                stream,
                range,
                total_size,
                request_keep_alive,
                None,
            );
        };
        self.schedule_forward_prefetch(stream, segment_index, total_size, generation);
        write_cached_range(
            socket,
            stream,
            range,
            total_size,
            request_keep_alive,
            &[slice],
        )
    }

    fn handle_open_ended_range(
        self: &Arc<Self>,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        range: crate::http::ByteRange,
        total_size: u64,
        request_keep_alive: bool,
    ) -> bool {
        debug_assert!(range.content_len <= OPEN_ENDED_BYTES);
        let first_segment = range.start / SEGMENT_BYTES;
        let last_segment = range.end_inclusive / SEGMENT_BYTES;
        let foreground = (first_segment..=last_segment).collect::<Vec<_>>();
        let generation = self.begin_foreground(stream, &foreground);
        if let Some(slices) = self.cached_range(stream, range.start, range.end_inclusive) {
            self.schedule_forward_prefetch(stream, last_segment, total_size, generation);
            return write_cached_range(
                socket,
                stream,
                range,
                total_size,
                request_keep_alive,
                &slices,
            );
        }

        let tee = Mutex::new(SegmentTee::new(
            stream.id,
            range.start,
            total_size,
            SEGMENT_BYTES,
        ));
        stream.stats.set_diagnostic(Some(format!(
            "open_ended_direct range={}-{}",
            range.start, range.end_inclusive
        )));
        let result = self.handle_direct_range(
            socket,
            stream,
            range,
            total_size,
            request_keep_alive,
            Some(&|offset, bytes| {
                tee.lock()
                    .unwrap_or_else(|poison| poison.into_inner())
                    .record(offset, bytes, &self.cache);
            }),
        );
        self.schedule_forward_prefetch(stream, last_segment, total_size, generation);
        result
    }

    fn handle_direct_range(
        &self,
        socket: &mut TcpStream,
        stream: &Arc<MediaStream>,
        range: crate::http::ByteRange,
        total_size: u64,
        request_keep_alive: bool,
        observer: Option<&ChunkObserver<'_>>,
    ) -> bool {
        let (fetch, metadata) = match stream.open_fetch(
            Some(range.start),
            Some(range.end_inclusive),
            FetchMode::Range,
            FetchKind::Foreground,
        ) {
            Ok(fetch) => fetch,
            Err(ProxyError::Closed) => return false,
            Err(_) => {
                let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
                return false;
            }
        };
        if !valid_range_metadata(&metadata, range, total_size) {
            let _ = write_head(socket, ResponseHead::empty(status::BAD_GATEWAY));
            return false;
        }
        let keep_alive = request_keep_alive;
        let head = media_head(status::PARTIAL_CONTENT, stream, keep_alive)
            .with_content_length(range.content_len)
            .with_content_range(range, total_size);
        if write_head(socket, head).is_err() {
            return false;
        }
        let mut observer = |offset: u64, bytes: &[u8]| {
            if let Some(observer) = observer {
                observer(offset, bytes);
            }
        };
        stream_fetch_to_writer(
            stream,
            fetch,
            socket,
            Some(range.content_len),
            keep_alive,
            &mut observer,
        )
        .is_ok()
            && keep_alive
    }

    fn cached_slice(
        &self,
        key: SegmentKey,
        start: u64,
        end_inclusive: u64,
        stream: &MediaStream,
    ) -> Option<SegmentSlice> {
        let segment = self
            .cache
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(key)?;
        let slice = segment.slice(start, end_inclusive)?;
        stream.stats.cache_hits.fetch_add(1, Ordering::Relaxed);
        Some(slice)
    }

    fn cached_range(
        &self,
        stream: &MediaStream,
        start: u64,
        end_inclusive: u64,
    ) -> Option<Vec<SegmentSlice>> {
        let first = start / SEGMENT_BYTES;
        let last = end_inclusive / SEGMENT_BYTES;
        let mut cache = self
            .cache
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let mut slices = Vec::new();
        for index in first..=last {
            let segment = cache.get(SegmentKey {
                stream_id: stream.id,
                segment_index: index,
            })?;
            let slice_start = start.max(index.checked_mul(SEGMENT_BYTES)?);
            let segment_end = segment.end_inclusive()?;
            let slice_end = end_inclusive.min(segment_end);
            slices.push(segment.slice(slice_start, slice_end)?);
        }
        stream.stats.cache_hits.fetch_add(
            u64::try_from(slices.len()).unwrap_or(u64::MAX),
            Ordering::Relaxed,
        );
        Some(slices)
    }

    fn begin_foreground(&self, stream: &Arc<MediaStream>, foreground: &[u64]) -> u64 {
        let generation = stream.generation.fetch_add(1, Ordering::AcqRel) + 1;
        let foreground = foreground.iter().copied().collect::<HashSet<_>>();
        let stale = {
            let mut inflight = self
                .inflight
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            let keys = inflight
                .iter()
                .filter(|(key, entry)| {
                    key.stream_id == stream.id
                        && entry.generation < generation
                        && entry.foreground_waiters() == 0
                        && !foreground.contains(&key.segment_index)
                })
                .map(|(key, _)| *key)
                .collect::<Vec<_>>();
            keys.into_iter()
                .filter_map(|key| inflight.remove(&key))
                .collect::<Vec<_>>()
        };
        let request_ids = stale
            .iter()
            .filter_map(|entry| entry.request_id())
            .collect::<Vec<_>>();
        for entry in stale {
            entry.cancel("stale prefetch cancelled by a new seek");
        }
        for request_id in request_ids {
            stream.cancel_active_fetch(request_id);
        }
        generation
    }

    fn get_or_fetch_segment(
        &self,
        stream: &Arc<MediaStream>,
        segment_index: u64,
        total_size: u64,
        kind: WaiterKind,
        generation: u64,
    ) -> Result<Arc<Segment>, ProxyError> {
        let key = SegmentKey {
            stream_id: stream.id,
            segment_index,
        };
        if let Some(segment) = self
            .cache
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(key)
        {
            stream.stats.cache_hits.fetch_add(1, Ordering::Relaxed);
            return Ok(segment);
        }
        let (entry, owner) = {
            let mut inflight = self
                .inflight
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if let Some(entry) = inflight.get(&key) {
                (Arc::clone(entry), false)
            } else {
                let entry = Arc::new(InflightSegment::new(generation));
                inflight.insert(key, Arc::clone(&entry));
                (entry, true)
            }
        };
        let _waiter = entry.enter(kind);
        if owner {
            let result = self.fetch_segment(stream, key, total_size, kind, generation, &entry);
            entry.complete(result.map_err(|error| error.to_string()));
            let mut inflight = self
                .inflight
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if inflight
                .get(&key)
                .is_some_and(|current| Arc::ptr_eq(current, &entry))
            {
                inflight.remove(&key);
            }
        }
        entry.wait().map_err(|message| {
            if stream.closed.load(Ordering::Acquire) || self.closed.load(Ordering::Acquire) {
                ProxyError::Closed
            } else {
                ProxyError::Transport(message)
            }
        })
    }

    fn fetch_segment(
        &self,
        stream: &Arc<MediaStream>,
        key: SegmentKey,
        total_size: u64,
        kind: WaiterKind,
        _generation: u64,
        entry: &InflightSegment,
    ) -> Result<Arc<Segment>, ProxyError> {
        if !entry.is_pending() {
            return Err(ProxyError::Closed);
        }
        let start = key
            .segment_index
            .checked_mul(SEGMENT_BYTES)
            .ok_or_else(|| ProxyError::InvalidArgument("segment offset overflow".to_string()))?;
        if start >= total_size {
            return Err(ProxyError::InvalidArgument(
                "segment starts past end of stream".to_string(),
            ));
        }
        let end_inclusive = start.saturating_add(SEGMENT_BYTES - 1).min(total_size - 1);
        stream.stats.set_diagnostic(Some(format!(
            "remote_fetch segment={} range={start}-{end_inclusive}",
            key.segment_index
        )));
        let fetch_kind = match kind {
            WaiterKind::Foreground => FetchKind::Foreground,
            WaiterKind::Prefetch => FetchKind::Prefetch,
        };
        let (fetch, metadata) = stream.open_fetch(
            Some(start),
            Some(end_inclusive),
            FetchMode::Range,
            fetch_kind,
        )?;
        entry.set_request_id(fetch.request_id);
        if !entry.is_pending() {
            stream.cancel_active_fetch(fetch.request_id);
            return Err(ProxyError::Closed);
        }
        let range = crate::http::ByteRange {
            start,
            end_inclusive,
            content_len: end_inclusive - start + 1,
        };
        if !valid_range_metadata(&metadata, range, total_size) {
            return Err(ProxyError::Transport(
                "segment response metadata did not match requested range".to_string(),
            ));
        }
        let bytes = read_fetch_exact(stream, fetch, range.content_len)?;
        let segment = Arc::new(Segment::new(key, start, bytes));
        if !stream.closed.load(Ordering::Acquire) {
            self.cache
                .lock()
                .unwrap_or_else(|poison| poison.into_inner())
                .insert(Arc::clone(&segment));
        }
        Ok(segment)
    }

    fn schedule_forward_prefetch(
        self: &Arc<Self>,
        stream: &Arc<MediaStream>,
        last_segment: u64,
        total_size: u64,
        generation: u64,
    ) {
        let segment_count = stream
            .forward_prefetch_chunks
            .saturating_mul(usize::try_from(OPEN_ENDED_BYTES / SEGMENT_BYTES).unwrap_or(4));
        if segment_count == 0 {
            return;
        }
        let indexes = (1..=segment_count)
            .filter_map(|offset| last_segment.checked_add(u64::try_from(offset).ok()?))
            .take_while(|index| index.saturating_mul(SEGMENT_BYTES) < total_size)
            .collect::<Vec<_>>();
        self.spawn_segment_batch(stream, indexes, total_size, generation, false);
    }

    fn spawn_segment_batch(
        self: &Arc<Self>,
        stream: &Arc<MediaStream>,
        indexes: Vec<u64>,
        total_size: u64,
        generation: u64,
        warmup: bool,
    ) {
        if indexes.is_empty() || self.closed.load(Ordering::Acquire) {
            return;
        }
        reap_finished(&self.background_threads);
        if !self.try_acquire_background_task() {
            return;
        }
        let engine = Arc::clone(self);
        let stream = Arc::clone(stream);
        let label = if warmup { "warmup" } else { "prefetch" };
        let mut threads = self
            .background_threads
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if self.closed.load(Ordering::Acquire) {
            self.active_background_tasks.fetch_sub(1, Ordering::AcqRel);
            return;
        }
        let thread = thread::Builder::new()
            .name(format!("media-proxy-{label}-{}", stream.id))
            .spawn(move || {
                let _guard = BackgroundTaskGuard {
                    engine: Arc::clone(&engine),
                };
                stream.stats.set_prefetch_state(Some(format!(
                    "scheduled {}",
                    indexes
                        .iter()
                        .map(u64::to_string)
                        .collect::<Vec<_>>()
                        .join(",")
                )));
                let mut completed = Vec::new();
                for index in indexes {
                    if engine.closed.load(Ordering::Acquire)
                        || stream.closed.load(Ordering::Acquire)
                        || stream.generation.load(Ordering::Acquire) != generation
                    {
                        break;
                    }
                    if engine
                        .get_or_fetch_segment(
                            &stream,
                            index,
                            total_size,
                            WaiterKind::Prefetch,
                            generation,
                        )
                        .is_err()
                    {
                        break;
                    }
                    completed.push(index);
                }
                if stream.generation.load(Ordering::Acquire) == generation
                    && !stream.closed.load(Ordering::Acquire)
                {
                    stream.stats.set_prefetch_state(Some(format!(
                        "completed {}",
                        completed
                            .iter()
                            .map(u64::to_string)
                            .collect::<Vec<_>>()
                            .join(",")
                    )));
                }
            });
        if let Ok(thread) = thread {
            threads.push(thread);
        } else {
            self.active_background_tasks.fetch_sub(1, Ordering::AcqRel);
        }
    }

    fn try_acquire_background_task(&self) -> bool {
        let mut current = self.active_background_tasks.load(Ordering::Acquire);
        loop {
            if current >= self.config.max_connections {
                return false;
            }
            match self.active_background_tasks.compare_exchange_weak(
                current,
                current + 1,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return true,
                Err(actual) => current = actual,
            }
        }
    }

    fn cancel_stream_inflight(&self, stream: &Arc<MediaStream>) {
        let entries = {
            let mut inflight = self
                .inflight
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            let keys = inflight
                .keys()
                .copied()
                .filter(|key| key.stream_id == stream.id)
                .collect::<Vec<_>>();
            keys.into_iter()
                .filter_map(|key| inflight.remove(&key))
                .collect::<Vec<_>>()
        };
        for entry in entries {
            entry.cancel("stream closed");
        }
    }
}

impl Drop for ProxyEngine {
    fn drop(&mut self) {
        self.closed.store(true, Ordering::Release);
        for socket in self
            .client_sockets
            .get_mut()
            .unwrap_or_else(|poison| poison.into_inner())
            .values()
        {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

pub struct MediaStream {
    pub id: u64,
    proxy: Weak<ProxyEngine>,
    bridge: Arc<dyn NetworkBridge>,
    route_token: String,
    size: Mutex<Option<u64>>,
    mime: String,
    seek_enabled: bool,
    forward_prefetch_chunks: usize,
    closed: AtomicBool,
    next_request_id: AtomicU64,
    generation: AtomicU64,
    active_fetches: Mutex<HashMap<u64, FetchKind>>,
    stats: StreamStats,
}

impl MediaStream {
    pub fn close(self: &Arc<Self>) -> bool {
        if self.closed.load(Ordering::Acquire) {
            return false;
        }
        if let Some(proxy) = self.proxy.upgrade() {
            proxy.unregister_stream(self)
        } else {
            self.close_transport()
        }
    }

    pub fn stats_json(&self) -> String {
        self.stats.snapshot_json()
    }

    pub(crate) fn belongs_to(&self, proxy: &Arc<ProxyEngine>) -> bool {
        self.proxy
            .upgrade()
            .is_some_and(|candidate| Arc::ptr_eq(&candidate, proxy))
    }

    fn resolve_size(&self) -> Result<Option<u64>, ProxyError> {
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        if let Some(size) = *self
            .size
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
        {
            return Ok(Some(size));
        }
        let size = self.bridge.head()?;
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        self.store_size(size);
        Ok(size)
    }

    fn store_size(&self, size: Option<u64>) {
        if let Some(size) = size {
            *self
                .size
                .lock()
                .unwrap_or_else(|poison| poison.into_inner()) = Some(size);
        }
    }

    fn open_fetch(
        self: &Arc<Self>,
        start: Option<u64>,
        end_inclusive: Option<u64>,
        mode: FetchMode,
        kind: FetchKind,
    ) -> Result<(FetchGuard, FetchMetadata), ProxyError> {
        let fetch = self.begin_fetch(kind)?;
        let metadata = self
            .bridge
            .open_fetch(fetch.request_id, start, end_inclusive, mode)?;
        if self.closed.load(Ordering::Acquire) {
            self.bridge.cancel_fetch(fetch.request_id);
            return Err(ProxyError::Closed);
        }
        self.stats
            .remote_status
            .store(metadata.status, Ordering::Release);
        Ok((fetch, metadata))
    }

    fn begin_fetch(self: &Arc<Self>, kind: FetchKind) -> Result<FetchGuard, ProxyError> {
        if self.closed.load(Ordering::Acquire) {
            return Err(ProxyError::Closed);
        }
        let request_id = self.next_request_id.fetch_add(1, Ordering::Relaxed);
        if request_id == 0 || request_id > i64::MAX as u64 {
            return Err(ProxyError::InvalidArgument(
                "request id space exhausted".to_string(),
            ));
        }
        {
            let mut active = self
                .active_fetches
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if self.closed.load(Ordering::Acquire) {
                return Err(ProxyError::Closed);
            }
            active.insert(request_id, kind);
        }
        if self.closed.load(Ordering::Acquire) {
            self.cancel_active_fetch(request_id);
            return Err(ProxyError::Closed);
        }
        Ok(FetchGuard {
            stream: Arc::clone(self),
            request_id,
        })
    }

    fn cancel_active_fetch(&self, request_id: u64) {
        let removed = self
            .active_fetches
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(&request_id)
            .is_some();
        if removed {
            self.bridge.cancel_fetch(request_id);
            self.bridge.close_fetch(request_id);
        }
    }

    fn close_transport(&self) -> bool {
        if self.closed.swap(true, Ordering::AcqRel) {
            return false;
        }
        self.generation.fetch_add(1, Ordering::AcqRel);
        let request_ids = self
            .active_fetches
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .drain()
            .map(|(request_id, _)| request_id)
            .collect::<Vec<_>>();
        for request_id in request_ids {
            self.bridge.cancel_fetch(request_id);
            self.bridge.close_fetch(request_id);
        }
        true
    }
}

#[derive(Clone, Copy, Debug)]
enum FetchKind {
    Foreground,
    Prefetch,
}

struct FetchGuard {
    stream: Arc<MediaStream>,
    request_id: u64,
}

impl Drop for FetchGuard {
    fn drop(&mut self) {
        let removed = self
            .stream
            .active_fetches
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .remove(&self.request_id)
            .is_some();
        if removed {
            self.stream.bridge.close_fetch(self.request_id);
        }
    }
}

#[derive(Default)]
struct StreamStats {
    current_range: Mutex<Option<String>>,
    remote_status: AtomicI32,
    cache_hits: AtomicU64,
    prefetch_state: Mutex<Option<String>>,
    diagnostic: Mutex<Option<String>>,
}

impl StreamStats {
    fn set_current_range(&self, range: Option<String>) {
        *self
            .current_range
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = range;
    }

    fn set_prefetch_state(&self, state: Option<String>) {
        *self
            .prefetch_state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = state;
    }

    fn set_diagnostic(&self, message: Option<String>) {
        *self
            .diagnostic
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = message;
    }

    fn snapshot_json(&self) -> String {
        let range = self
            .current_range
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone();
        let prefetch = self
            .prefetch_state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone();
        let diagnostic = self
            .diagnostic
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone();
        let remote = self.remote_status.load(Ordering::Acquire);
        format!(
            "{{\"currentRange\":{},\"remoteHttpStatus\":{},\"memoryCacheHits\":{},\"prefetchState\":{},\"diagnosticMessage\":{}}}",
            json_option(range.as_deref()),
            if remote == 0 { "null".to_string() } else { remote.to_string() },
            self.cache_hits.load(Ordering::Relaxed),
            json_option(prefetch.as_deref()),
            json_option(diagnostic.as_deref()),
        )
    }
}

struct ConnectionGuard {
    engine: Arc<ProxyEngine>,
    connection_id: u64,
}

struct BackgroundTaskGuard {
    engine: Arc<ProxyEngine>,
}

impl Drop for BackgroundTaskGuard {
    fn drop(&mut self) {
        self.engine
            .active_background_tasks
            .fetch_sub(1, Ordering::AcqRel);
    }
}

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        self.engine.release_connection(self.connection_id);
    }
}

fn media_head(code: u16, stream: &MediaStream, keep_alive: bool) -> ResponseHead {
    let mut head = ResponseHead::new(code).with_connection(if keep_alive {
        Connection::KeepAlive
    } else {
        Connection::Close
    });
    let _ = head.set_header("Content-Type", stream.mime.clone());
    let _ = head.set_header("Accept-Ranges", "bytes");
    head
}

fn write_head(socket: &mut TcpStream, head: ResponseHead) -> std::io::Result<()> {
    socket.write_all(&head.to_bytes())?;
    socket.flush()
}

fn write_cached_range(
    socket: &mut TcpStream,
    stream: &MediaStream,
    range: crate::http::ByteRange,
    total_size: u64,
    keep_alive: bool,
    slices: &[SegmentSlice],
) -> bool {
    let head = media_head(status::PARTIAL_CONTENT, stream, keep_alive)
        .with_content_length(range.content_len)
        .with_content_range(range, total_size);
    if write_head(socket, head).is_err() {
        return false;
    }
    for slice in slices {
        if socket.write_all(slice.as_bytes()).is_err() {
            return false;
        }
    }
    socket.flush().is_ok() && keep_alive
}

fn stream_fetch_to_writer(
    stream: &MediaStream,
    fetch: FetchGuard,
    writer: &mut impl Write,
    expected: Option<u64>,
    verify_eof: bool,
    mut observer: impl FnMut(u64, &[u8]),
) -> Result<u64, ProxyError> {
    let mut buffer = vec![0; NATIVE_CHUNK_BYTES];
    let mut written = 0u64;
    let mut zero_reads = 0usize;
    loop {
        if let Some(expected) = expected {
            if written >= expected {
                break;
            }
        }
        let limit = expected
            .and_then(|expected| expected.checked_sub(written))
            .and_then(|remaining| usize::try_from(remaining).ok())
            .map_or(buffer.len(), |remaining| remaining.min(buffer.len()));
        let read = stream
            .bridge
            .read_fetch_into(fetch.request_id, &mut buffer[..limit])?;
        let Some(count) = read else {
            if expected.is_some_and(|expected| written != expected) {
                return Err(ProxyError::Transport(format!(
                    "remote body ended after {written} bytes, expected {}",
                    expected.unwrap_or_default()
                )));
            }
            return Ok(written);
        };
        if count == 0 {
            zero_reads += 1;
            if zero_reads > 16 {
                return Err(ProxyError::Transport(
                    "remote body repeatedly returned zero bytes".to_string(),
                ));
            }
            thread::yield_now();
            continue;
        }
        zero_reads = 0;
        observer(written, &buffer[..count]);
        writer.write_all(&buffer[..count])?;
        written = written
            .checked_add(
                u64::try_from(count)
                    .map_err(|_| ProxyError::Transport("read byte count overflow".to_string()))?,
            )
            .ok_or_else(|| ProxyError::Transport("response length overflow".to_string()))?;
    }
    if verify_eof {
        let mut zero_reads = 0;
        loop {
            match stream
                .bridge
                .read_fetch_into(fetch.request_id, &mut buffer[..1])?
            {
                None => break,
                Some(0) if zero_reads < 16 => {
                    zero_reads += 1;
                    thread::yield_now();
                }
                Some(0) => {
                    return Err(ProxyError::Transport(
                        "remote body did not reach EOF after Content-Length".to_string(),
                    ));
                }
                Some(_) => {
                    return Err(ProxyError::Transport(
                        "remote body exceeded Content-Length".to_string(),
                    ));
                }
            }
        }
    }
    Ok(written)
}

fn read_fetch_exact(
    stream: &MediaStream,
    fetch: FetchGuard,
    expected: u64,
) -> Result<Vec<u8>, ProxyError> {
    let capacity = usize::try_from(expected).map_err(|_| {
        ProxyError::InvalidArgument("segment length cannot fit in memory".to_string())
    })?;
    let mut bytes = Vec::with_capacity(capacity);
    stream_fetch_to_writer(stream, fetch, &mut bytes, Some(expected), true, |_, _| {})?;
    Ok(bytes)
}

fn valid_range_metadata(
    metadata: &FetchMetadata,
    range: crate::http::ByteRange,
    total_size: u64,
) -> bool {
    if metadata.status != 206 {
        return false;
    }
    if metadata
        .content_length
        .is_some_and(|length| length != range.content_len)
    {
        return false;
    }
    match (metadata.content_range_start, metadata.content_range_end) {
        (Some(start), Some(end)) if start == range.start && end == range.end_inclusive => {}
        (None, None) => {}
        _ => return false,
    }
    !metadata.total_size.is_some_and(|size| size != total_size)
}

fn valid_full_metadata(metadata: &FetchMetadata, size: Option<u64>) -> bool {
    if matches!((size, metadata.total_size), (Some(size), Some(total)) if size != total) {
        return false;
    }
    match metadata.status {
        200 => {
            !matches!((size, metadata.content_length), (Some(size), Some(length)) if size != length)
        }
        206 => {
            let Some(size) = size else {
                return false;
            };
            metadata.content_length == Some(size)
                && metadata.content_range_start == Some(0)
                && metadata.content_range_end == size.checked_sub(1)
        }
        _ => false,
    }
}

struct SegmentTee {
    stream_id: u64,
    cursor: u64,
    total_size: u64,
    segment_bytes: u64,
    segment_index: u64,
    segment_start: u64,
    eligible: bool,
    bytes: Vec<u8>,
}

impl SegmentTee {
    fn new(stream_id: u64, first_offset: u64, total_size: u64, segment_bytes: u64) -> Self {
        let segment_index = first_offset / segment_bytes;
        let segment_start = segment_index * segment_bytes;
        Self {
            stream_id,
            cursor: first_offset,
            total_size,
            segment_bytes,
            segment_index,
            segment_start,
            eligible: first_offset == segment_start,
            bytes: Vec::new(),
        }
    }

    fn record(&mut self, _response_offset: u64, mut input: &[u8], cache: &Mutex<ByteLru>) {
        while !input.is_empty() && self.cursor < self.total_size {
            let segment_end_exclusive = self
                .segment_start
                .saturating_add(self.segment_bytes)
                .min(self.total_size);
            let room = usize::try_from(segment_end_exclusive.saturating_sub(self.cursor))
                .unwrap_or(usize::MAX)
                .min(input.len());
            if self.eligible {
                self.bytes.extend_from_slice(&input[..room]);
            }
            self.cursor = self.cursor.saturating_add(room as u64);
            input = &input[room..];
            if self.cursor == segment_end_exclusive {
                let expected = usize::try_from(segment_end_exclusive - self.segment_start).ok();
                if self.eligible && expected == Some(self.bytes.len()) {
                    let bytes = std::mem::take(&mut self.bytes);
                    let segment = Arc::new(Segment::new(
                        SegmentKey {
                            stream_id: self.stream_id,
                            segment_index: self.segment_index,
                        },
                        self.segment_start,
                        bytes,
                    ));
                    cache
                        .lock()
                        .unwrap_or_else(|poison| poison.into_inner())
                        .insert(segment);
                }
                self.segment_index = self.segment_index.saturating_add(1);
                self.segment_start = self.segment_index.saturating_mul(self.segment_bytes);
                self.eligible = true;
                self.bytes.clear();
            }
        }
    }
}

#[derive(Clone, Copy, Debug)]
enum ReadRequestError {
    Timeout,
    TooLarge,
    Malformed,
    Io,
}

fn read_request(
    socket: &mut TcpStream,
    max_header_bytes: usize,
) -> Result<Option<Request>, ReadRequestError> {
    let mut bytes = Vec::with_capacity(max_header_bytes.min(4096));
    let mut byte = [0u8; 1];
    loop {
        match socket.read(&mut byte) {
            Ok(0) => return Ok(None),
            Ok(_) => bytes.push(byte[0]),
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                return Err(ReadRequestError::Timeout);
            }
            Err(_) => return Err(ReadRequestError::Io),
        }
        match parse_request(&bytes, max_header_bytes) {
            Ok((request, _)) => return Ok(Some(request)),
            Err(HttpError::Incomplete) => continue,
            Err(HttpError::HeaderTooLarge { .. }) => return Err(ReadRequestError::TooLarge),
            Err(_) => return Err(ReadRequestError::Malformed),
        }
    }
}

fn route_token_from_target(target: &str) -> Option<String> {
    let path = target.split('?').next()?;
    let suffix = path.strip_prefix("/stream/")?;
    let token = suffix.split('/').next()?;
    if token.is_empty() {
        return None;
    }
    percent_decode(token)
}

fn percent_decode(value: &str) -> Option<String> {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            let high = hex(bytes.get(index + 1).copied()?)?;
            let low = hex(bytes.get(index + 2).copied()?)?;
            decoded.push(high << 4 | low);
            index += 3;
        } else {
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(decoded).ok()
}

fn hex(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn validate_route_token(route_token: &str) -> Result<(), ProxyError> {
    if route_token.is_empty()
        || route_token
            .bytes()
            .any(|byte| byte <= b' ' || matches!(byte, b'/' | b'?' | b'#' | b'%' | 0x7f))
    {
        return Err(ProxyError::InvalidArgument(
            "route token must be a non-empty URL path segment".to_string(),
        ));
    }
    Ok(())
}

fn sanitize_mime(mime: &str) -> String {
    if mime.is_empty()
        || mime
            .bytes()
            .any(|byte| byte < b' ' || byte == 0x7f || matches!(byte, b'\r' | b'\n'))
    {
        "application/octet-stream".to_string()
    } else {
        mime.to_string()
    }
}

fn join_all(threads: &Mutex<Vec<JoinHandle<()>>>) {
    let threads = threads
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .drain(..)
        .collect::<Vec<_>>();
    for thread in threads {
        let _ = thread.join();
    }
}

fn reap_finished(threads: &Mutex<Vec<JoinHandle<()>>>) {
    let finished = {
        let mut threads = threads.lock().unwrap_or_else(|poison| poison.into_inner());
        let mut finished = Vec::new();
        let mut index = 0;
        while index < threads.len() {
            if threads[index].is_finished() {
                finished.push(threads.swap_remove(index));
            } else {
                index += 1;
            }
        }
        finished
    };
    for thread in finished {
        let _ = thread.join();
    }
}

fn json_option(value: Option<&str>) -> String {
    match value {
        None => "null".to_string(),
        Some(value) => format!("\"{}\"", json_escape(value)),
    }
}

fn json_escape(value: &str) -> String {
    let mut result = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            character if character < ' ' => {
                use std::fmt::Write as _;
                let _ = write!(result, "\\u{:04x}", character as u32);
            }
            character => result.push(character),
        }
    }
    result
}
