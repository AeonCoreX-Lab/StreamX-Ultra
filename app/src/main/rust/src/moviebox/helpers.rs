//! Small deserialization helpers shared across `moviebox::types`.

use serde::{Deserialize, Deserializer};
use serde_json::Value;

/// MovieBox's `resolutions` field has been observed as both a JSON string
/// (`"360"`) and a bare number (`360`) across different endpoints/hosts.
/// Normalize both into a `u32`, defaulting to 0 (treated as "unknown/lowest")
/// on anything unparseable rather than failing the whole response.
pub fn de_resolution<'de, D>(deserializer: D) -> Result<u32, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Value::deserialize(deserializer)?;
    Ok(match v {
        Value::String(s) => s.trim_end_matches('p').parse::<u32>().unwrap_or(0),
        Value::Number(n) => n.as_u64().unwrap_or(0) as u32,
        _ => 0,
    })
}
