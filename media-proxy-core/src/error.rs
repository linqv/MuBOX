use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, Eq, PartialEq)]
pub enum ProxyError {
    InvalidArgument(String),
    Io(String),
    Transport(String),
    Closed,
    NotFound,
}

impl Display for ProxyError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidArgument(message) => write!(formatter, "invalid argument: {message}"),
            Self::Io(message) => write!(formatter, "I/O error: {message}"),
            Self::Transport(message) => write!(formatter, "transport error: {message}"),
            Self::Closed => formatter.write_str("proxy or stream is closed"),
            Self::NotFound => formatter.write_str("native handle not found"),
        }
    }
}

impl std::error::Error for ProxyError {}

impl From<std::io::Error> for ProxyError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error.to_string())
    }
}

impl From<jni::errors::Error> for ProxyError {
    fn from(error: jni::errors::Error) -> Self {
        Self::Transport(error.to_string())
    }
}
