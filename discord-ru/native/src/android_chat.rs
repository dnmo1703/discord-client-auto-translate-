use crate::{engine, or_throw, str_arg};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jint, jstring},
};
use reqwest::{Client, Method, Proxy, redirect::Policy};
use serde_json::{Value, json};
use std::{
    ptr,
    sync::{LazyLock, Mutex},
    time::Duration,
};

const API: &str = "https://discord.com/api/v10";
static HTTP: LazyLock<Mutex<Option<(i32, i32, Client)>>> = LazyLock::new(|| Mutex::new(None));

fn client(port: i32, revision: i32) -> Result<Client, String> {
    let mut cache = HTTP.lock().map_err(|_| "Сеть недоступна.")?;
    if let Some((saved_port, saved_revision, http)) = &*cache {
        if (port, revision) == (*saved_port, *saved_revision) {
            return Ok(http.clone());
        }
    }
    let mut builder = Client::builder()
        .no_proxy()
        .https_only(true)
        .redirect(Policy::none())
        .user_agent(
            "DiscordBot (https://github.com/dnmo1703/discord-client-auto-translate-, 0.1.0)",
        )
        .connect_timeout(Duration::from_secs(12))
        .timeout(Duration::from_secs(22));
    if (1..=65535).contains(&port) {
        builder = builder.proxy(
            Proxy::https(format!("http://127.0.0.1:{port}"))
                .map_err(|_| "Неверные настройки локального прокси.")?,
        );
    }
    let http = builder.build().map_err(|_| "Не удалось запустить сеть.")?;
    *cache = Some((port, revision, http.clone()));
    Ok(http)
}

fn snowflake(value: &str) -> Result<&str, String> {
    value
        .parse::<u64>()
        .ok()
        .filter(|id| *id > 0 && value.bytes().all(|byte| byte.is_ascii_digit()))
        .map(|_| value)
        .ok_or_else(|| "Неверный идентификатор Discord.".into())
}

async fn request(
    http: Client,
    operation: &str,
    token: &str,
    id: &str,
    value: &str,
) -> Result<Value, String> {
    let token = token.trim();
    if operation != "check"
        && (token.is_empty()
            || token.len() > 512
            || token.bytes().any(|byte| byte.is_ascii_whitespace()))
    {
        return Err("Введите токен вашего бота.".into());
    }
    let (method, path, body) = match operation {
        "check" => (Method::GET, "/gateway".into(), None),
        "account" => (Method::GET, "/users/@me".into(), None),
        "guilds" => (Method::GET, "/users/@me/guilds?limit=200".into(), None),
        "channels" => (
            Method::GET,
            format!("/guilds/{}/channels", snowflake(id)?),
            None,
        ),
        "messages" => {
            let mut path = format!("/channels/{}/messages?limit=40", snowflake(id)?);
            if !value.is_empty() {
                path.push_str(&format!("&before={}", snowflake(value)?));
            }
            (Method::GET, path, None)
        }
        "send" => {
            if value.trim().is_empty() || value.chars().count() > 2000 {
                return Err("Сообщение должно содержать от 1 до 2000 символов.".into());
            }
            (
                Method::POST,
                format!("/channels/{}/messages", snowflake(id)?),
                Some(json!({"content": value, "allowed_mentions": {"parse": []}})),
            )
        }
        _ => return Err("Неизвестная операция.".into()),
    };
    let mut req = http.request(method, format!("{API}{path}"));
    if operation != "check" {
        req = req.header("Authorization", format!("Bot {token}"));
    }
    if let Some(body) = body {
        req = req.json(&body);
    }
    let response = req.send().await.map_err(|error| {
        if error.is_timeout() {
            "Discord не ответил вовремя."
        } else {
            "Не удалось подключиться к Discord. Проверьте сеть и настройки прокси."
        }
    })?;
    match response.status().as_u16() {
        200..=299 => {}
        401 => return Err("Discord отклонил токен бота.".into()),
        403 => return Err("У бота нет разрешения на это действие.".into()),
        404 => return Err("Канал или сервер недоступен.".into()),
        429 => return Err("Discord ограничил частоту запросов. Попробуйте позже.".into()),
        status => return Err(format!("Discord вернул HTTP {status}.")),
    }
    // Cap the response before parsing it.
    let mut response = response;
    let mut data = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "Связь с Discord прервалась.")?
    {
        if data.len() + chunk.len() > 4 * 1024 * 1024 {
            return Err("Ответ Discord слишком большой.".into());
        }
        data.extend_from_slice(&chunk);
    }
    let decoded: Value =
        serde_json::from_slice(&data).map_err(|_| "Discord вернул неизвестный формат.")?;
    if operation == "account" && decoded["bot"].as_bool() != Some(true) {
        return Err("Нужен токен приложения Discord с ботом.".into());
    }
    if matches!(operation, "guilds" | "channels" | "messages") && !decoded.is_array() {
        return Err("Discord вернул неизвестный формат.".into());
    }
    Ok(decoded)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_AndroidCore_discord(
    mut env: JNIEnv,
    _class: JClass,
    operation: JString,
    token: JString,
    id: JString,
    value: JString,
    port: jint,
    revision: jint,
) -> jstring {
    let result = (|| {
        let operation = str_arg(&mut env, &operation)?;
        let token = str_arg(&mut env, &token)?;
        let id = str_arg(&mut env, &id)?;
        let value = str_arg(&mut env, &value)?;
        let http = client(port, revision)?;
        let result = engine()?
            .runtime
            .block_on(request(http, &operation, &token, &id, &value))?;
        env.new_string(result.to_string())
            .map(JString::into_raw)
            .map_err(|_| "Не удалось показать ответ Discord.".into())
    })();
    or_throw(&mut env, result, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_AndroidCore_translate(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let result = (|| {
        let text = str_arg(&mut env, &value)?;
        let result = engine()?
            .runtime
            .block_on(crate::translate("", "", &text))?;
        env.new_string(result)
            .map(JString::into_raw)
            .map_err(|_| "Не удалось показать перевод.".into())
    })();
    or_throw(&mut env, result, ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::snowflake;

    #[test]
    fn route_parameters_only_accept_positive_decimal_snowflakes() {
        assert_eq!(
            snowflake("18446744073709551615").unwrap(),
            "18446744073709551615"
        );
        for input in [
            "0",
            "",
            "-1",
            "+1",
            "1/2",
            "1?before=2",
            "18446744073709551616",
        ] {
            assert!(snowflake(input).is_err(), "{input}");
        }
    }
}
