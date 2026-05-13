use std::cmp::Ordering;

#[derive(Debug, PartialEq, Eq)]
enum Token {
    Text(String),
    Number(u64),
}

pub fn compare(a: &str, b: &str) -> Ordering {
    let left = tokenize(a);
    let right = tokenize(b);
    left.iter().cmp(right.iter())
}

fn tokenize(value: &str) -> Vec<Token> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut in_digits: Option<bool> = None;

    for ch in value.chars() {
        let is_digit = ch.is_ascii_digit();
        if in_digits == Some(is_digit) || in_digits.is_none() {
            current.push(ch);
            in_digits = Some(is_digit);
        } else {
            tokens.push(token_from(&current, in_digits.unwrap()));
            current.clear();
            current.push(ch);
            in_digits = Some(is_digit);
        }
    }
    if let Some(kind) = in_digits {
        tokens.push(token_from(&current, kind));
    }
    tokens
}

fn token_from(value: &str, is_digit: bool) -> Token {
    if is_digit {
        Token::Number(value.parse().unwrap_or(0))
    } else {
        Token::Text(value.to_lowercase())
    }
}

impl Ord for Token {
    fn cmp(&self, other: &Self) -> Ordering {
        match (self, other) {
            (Token::Text(left), Token::Text(right)) => left.cmp(right),
            (Token::Number(left), Token::Number(right)) => left.cmp(right),
            (Token::Number(_), Token::Text(_)) => Ordering::Less,
            (Token::Text(_), Token::Number(_)) => Ordering::Greater,
        }
    }
}

impl PartialOrd for Token {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[cfg(test)]
mod tests {
    use super::compare;

    #[test]
    fn sorts_numbers_by_numeric_value() {
        let mut names = vec!["10.jpg", "1.jpg", "2.jpg"];

        names.sort_by(|left, right| compare(left, right));

        assert_eq!(vec!["1.jpg", "2.jpg", "10.jpg"], names);
    }
}
