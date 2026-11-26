use crate::{engine, or_throw, str_arg};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jint, jstring},
};
use reqwest::{Client, Method, Proxy, StatusCode, header::HeaderValue, redirect::Policy};
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
        .user_agent("Discord RU Native Android/0.2.1")
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

enum ApiError {
    Expired,
    Notice(String),
}

impl From<&str> for ApiError {
    fn from(value: &str) -> Self {
        Self::Notice(value.into())
    }
}

impl From<String> for ApiError {
    fn from(value: String) -> Self {
        Self::Notice(value)
    }
}

fn auth_reply(operation: &str, status: StatusCode, body: &Value) -> Result<Value, ApiError> {
    if body.get("captcha_key").is_some() || body.get("captcha_sitekey").is_some() {
        return Err(
            "Discord потребовал CAPTCHA. Эта сборка не может продолжить такой вход.".into(),
        );
    }
    let code = body["code"].as_u64();
    match code {
        Some(70007) => return Err("Discord запросил подтверждение телефона. Подтвердите вход в официальном приложении Discord и повторите попытку.".into()),
        Some(70008) => return Err("Подтвердите попытку входа по письму Discord, затем попробуйте снова.".into()),
        Some(60008 | 60009) => return Err("Код подтверждения неверен или устарел.".into()),
        _ => {}
    }
    if !status.is_success() {
        return Err(
            if operation == "login" && matches!(status.as_u16(), 400 | 401) {
                "Discord отклонил вход. Проверьте электронную почту и пароль."
            } else if status.as_u16() == 429 {
                "Discord ограничил число попыток. Попробуйте позже."
            } else if status.as_u16() == 403 {
                "Discord запретил вход для этого аккаунта или подключения."
            } else {
                "Discord не принял подтверждение входа."
            }
            .into(),
        );
    }
    if body["required_actions"]
        .as_array()
        .is_some_and(|actions| !actions.is_empty())
    {
        return Err("Discord требует дополнительного действия для аккаунта. Выполните его в официальном приложении Discord.".into());
    }
    if operation == "sms_send" && body["phone"].is_string() {
        return Ok(json!({"state": "sms_sent"}));
    }
    if body["mfa"] == true {
        let ticket = body["ticket"]
            .as_str()
            .filter(|value| !value.is_empty())
            .ok_or("Discord не вернул данные для двухфакторного входа.")?;
        let totp = body["totp"].as_bool().unwrap_or(false);
        let sms = body["sms"].as_bool().unwrap_or(false);
        let backup = body["backup"].as_bool().unwrap_or(false);
        if !(totp || sms || backup) {
            return Err("Этот способ подтверждения входа пока не поддерживается.".into());
        }
        return Ok(
            json!({"state": "mfa", "ticket": ticket, "totp": totp, "sms": sms, "backup": backup}),
        );
    }
    let token = body["token"]
        .as_str()
        .filter(|value| !value.is_empty())
        .ok_or("Discord не завершил вход.")?;
    Ok(json!({"state": "ready", "token": token}))
}

async fn request(
    http: Client,
    operation: &str,
    token: &str,
    id: &str,
    value: &str,
) -> Result<Value, ApiError> {
    let auth = matches!(operation, "login" | "totp" | "sms" | "backup" | "sms_send");
    let mut header = None;
    if !auth && operation != "check" {
        if token.is_empty() {
            return Err(ApiError::Expired);
        }
        let mut credential = HeaderValue::from_str(token).map_err(|_| ApiError::Expired)?;
        credential.set_sensitive(true);
        header = Some(credential);
    }
    let (method, path, body) = match operation {
        "check" => (Method::GET, "/gateway".into(), None),
        "login" => {
            if id.is_empty() || value.is_empty() || id.len() > 320 || value.len() > 300 {
                return Err("Введите электронную почту или номер телефона и пароль.".into());
            }
            (
                Method::POST,
                "/auth/login".into(),
                Some(json!({"login": id, "password": value})),
            )
        }
        "totp" | "sms" | "backup" => {
            if id.is_empty() || id.len() > 4096 || value.is_empty() || value.len() > 32 {
                return Err("Введите действующий код подтверждения.".into());
            }
            (
                Method::POST,
                format!("/auth/mfa/{operation}"),
                Some(json!({"ticket": id, "code": value})),
            )
        }
        "sms_send" => {
            if id.is_empty() || id.len() > 4096 {
                return Err("Войдите в аккаунт, чтобы запросить код.".into());
            }
            (
                Method::POST,
                "/auth/mfa/sms/send".into(),
                Some(json!({"ticket": id})),
            )
        }
        "account" => (Method::GET, "/users/@me".into(), None),
        "guilds" => (Method::GET, "/users/@me/guilds?limit=200".into(), None),
        "private" => (Method::GET, "/users/@me/channels".into(), None),
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
                Some(json!({"content": value, "allowed_mentions": {"parse": ["users"]}})),
            )
        }
        _ => return Err("Неизвестная операция.".into()),
    };
    let mut req = http.request(method, format!("{API}{path}"));
    if let Some(credential) = header {
        req = req.header("Authorization", credential);
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
    let status = response.status();
    if !auth {
        match status.as_u16() {
            200..=299 => {}
            401 => return Err(ApiError::Expired),
            403 => return Err("У аккаунта нет доступа к этому действию.".into()),
            404 => return Err("Канал или сервер недоступен.".into()),
            429 => return Err("Discord ограничил частоту запросов. Попробуйте позже.".into()),
            code => return Err(format!("Discord вернул HTTP {code}.").into()),
        }
    }
    let body = read_response(response).await?;
    if auth {
        return auth_reply(operation, status, &body);
    }
    if matches!(operation, "guilds" | "private" | "channels" | "messages") && !body.is_array() {
        return Err("Discord вернул неизвестный формат.".into());
    }
    Ok(body)
}

async fn read_response(mut response: reqwest::Response) -> Result<Value, ApiError> {
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
    serde_json::from_slice(&data).map_err(|_| {
        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            "Discord ограничил частоту запросов. Попробуйте позже.".into()
        } else {
            "Discord вернул неизвестный формат.".into()
        }
    })
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
            .map_err(|_| ApiError::from("Не удалось показать ответ Discord."))
    })();
    match result {
        Ok(value) => value,
        Err(ApiError::Notice(message)) => or_throw(&mut env, Err(message), ptr::null_mut()),
        Err(ApiError::Expired) => {
            let _ = env.throw_new(
                "dev/discordru/SessionExpiredException",
                "Сеанс Discord завершён. Войдите снова.",
            );
            ptr::null_mut()
        }
    }
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
    use super::{ApiError, StatusCode, auth_reply, json, snowflake};

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

    #[test]
    fn login_only_accepts_a_completed_session_or_supported_second_factor() {
        let mfa = json!({"mfa": true, "ticket": "example", "totp": true});
        let Ok(result) = auth_reply("login", StatusCode::OK, &mfa) else {
            panic!("MFA required")
        };
        assert_eq!(
            result,
            json!({"state": "mfa", "ticket": "example", "totp": true, "sms": false, "backup": false})
        );

        let Ok(result) = auth_reply("totp", StatusCode::OK, &json!({"token": "example"})) else {
            panic!("expected authenticated session")
        };
        assert_eq!(result, json!({"state": "ready", "token": "example"}));

        for response in [
            json!({"token": "example", "captcha_key": ["captcha-required"]}),
            json!({"token": "example", "required_actions": ["VERIFY_EMAIL"]}),
            json!({"mfa": true, "ticket": "example", "webauthn": true}),
            json!({"suspended_user_token": "example"}),
        ] {
            assert!(matches!(
                auth_reply("login", StatusCode::OK, &response),
                Err(ApiError::Notice(_))
            ));
        }
    }
}
