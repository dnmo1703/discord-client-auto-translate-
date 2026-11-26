mod proxy;

use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jint, jstring},
};
use proxy::{Protocol, Relay, Route};
use reqwest::{Client, Url, redirect::Policy};
use serde::{Deserialize, Serialize};
use std::io;
use std::ptr;
use std::sync::{Arc, LazyLock, OnceLock};
use std::time::Duration;
use tokio::{runtime::Runtime, sync::RwLock};

struct Engine {
    runtime: Runtime,
    route: Arc<RwLock<Route>>,
    port: u16,
}

static ENGINE: OnceLock<io::Result<Engine>> = OnceLock::new();
static TRANSLATOR: LazyLock<Result<Client, reqwest::Error>> = LazyLock::new(|| {
    Client::builder()
        .https_only(true)
        .no_proxy()
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(20))
        .build()
});

fn engine() -> Result<&'static Engine, String> {
    ENGINE
        .get_or_init(|| {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()?;
            let route = Arc::new(RwLock::new(Route {
                enabled: true,
                relay: None,
            }));
            let (port, _) = runtime.block_on(proxy::listen(Arc::clone(&route)))?;
            Ok(Engine {
                runtime,
                route,
                port,
            })
        })
        .as_ref()
        .map_err(|_| "Не удалось запустить локальный прокси.".into())
}

fn str_arg(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(String::from)
        .map_err(|_| "Не удалось прочитать настройки.".into())
}

fn relay_args(
    env: &mut JNIEnv,
    protocol: JString,
    host: JString,
    port: jint,
    username: JString,
    password: JString,
) -> Result<Option<Relay>, String> {
    let host = str_arg(env, &host)?;
    if host.is_empty() {
        return Ok(None);
    }
    let protocol = Protocol::from_name(&str_arg(env, &protocol)?).map_err(|e| e.to_string())?;
    let port = u16::try_from(port)
        .ok()
        .filter(|n| *n > 0)
        .ok_or("Неверный порт.")?;
    Ok(Some(Relay {
        protocol,
        host,
        port,
        username: str_arg(env, &username)?,
        password: str_arg(env, &password)?,
    }))
}

fn or_throw<T>(env: &mut JNIEnv, result: Result<T, String>, fallback: T) -> T {
    match result {
        Ok(value) => value,
        Err(message) => {
            if matches!(env.exception_check(), Ok(false)) {
                let _ = env.throw_new("java/io/IOException", message);
            }
            fallback
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_LoopbackProxy_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
) -> jint {
    or_throw(&mut env, engine().map(|engine| i32::from(engine.port)), 0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_LoopbackProxy_nativeConfigure(
    mut env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
    protocol: JString,
    host: JString,
    port: jint,
    username: JString,
    password: JString,
) {
    let result = relay_args(&mut env, protocol, host, port, username, password).and_then(|relay| {
        let engine = engine()?;
        *engine.route.blocking_write() = Route {
            enabled: enabled != 0,
            relay,
        };
        Ok(())
    });
    or_throw(&mut env, result, ());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_LoopbackProxy_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    protocol: JString,
    host: JString,
    port: jint,
    username: JString,
    password: JString,
) {
    let result = relay_args(&mut env, protocol, host, port, username, password).and_then(|relay| {
        engine()?
            .runtime
            .block_on(proxy::check(relay.as_ref()))
            .map_err(|e| e.to_string())
    });
    or_throw(&mut env, result, ());
}

#[derive(Serialize)]
struct TranslationRequest<'a> {
    q: &'a str,
    source: &'static str,
    target: &'static str,
    format: &'static str,
    #[serde(skip_serializing_if = "str::is_empty")]
    api_key: &'a str,
}

#[derive(Deserialize)]
struct TranslationResponse {
    #[serde(rename = "translatedText")]
    text: String,
}

async fn translate(endpoint: &str, api_key: &str, text: &str) -> Result<String, String> {
    if text.trim().is_empty() || text.len() > 40_000 {
        return Err("Нет текста для перевода.".into());
    }
    let url = Url::parse(endpoint).map_err(|_| "Неверный адрес сервера перевода.")?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("Укажите HTTPS-адрес сервера без параметров входа.".into());
    }
    let client = TRANSLATOR
        .as_ref()
        .map_err(|_| "Не удалось запустить перевод.")?;
    let request = TranslationRequest {
        q: text,
        source: "auto",
        target: "ru",
        format: "text",
        api_key,
    };
    let mut response = client
        .post(url)
        .json(&request)
        .send()
        .await
        .map_err(|_| "Сервер перевода недоступен.")?;
    if !response.status().is_success() {
        return match response.status().as_u16() {
            401 | 403 => Err("Сервер отклонил запрос. Проверьте ключ API.".into()),
            429 => Err("Сервер ограничил число запросов. Попробуйте позже.".into()),
            _ => Err(format!(
                "Сервер перевода вернул HTTP {}.",
                response.status().as_u16()
            )),
        };
    }
    let mut data = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "Не удалось получить перевод.")?
    {
        if data.len() + chunk.len() > 256 * 1024 {
            return Err("Ответ сервера перевода слишком большой.".into());
        }
        data.extend_from_slice(&chunk);
    }
    let parsed: TranslationResponse =
        serde_json::from_slice(&data).map_err(|_| "Сервер вернул ответ в неизвестном формате.")?;
    if parsed.text.trim().is_empty() {
        return Err("Сервер вернул пустой перевод.".into());
    }
    Ok(parsed.text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_discordru_TranslatorToRussian_nativeTranslate(
    mut env: JNIEnv,
    _class: JClass,
    endpoint: JString,
    key: JString,
    text: JString,
) -> jstring {
    let result = (|| {
        let endpoint = str_arg(&mut env, &endpoint)?;
        let key = str_arg(&mut env, &key)?;
        let text = str_arg(&mut env, &text)?;
        let translated = engine()?
            .runtime
            .block_on(translate(&endpoint, &key, &text))?;
        env.new_string(translated)
            .map(JString::into_raw)
            .map_err(|_| "Не удалось показать перевод.".into())
    })();
    or_throw(&mut env, result, ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn libretranslate_uses_russian_and_does_not_require_a_key() {
        let request = TranslationRequest {
            q: "Hello",
            source: "auto",
            target: "ru",
            format: "text",
            api_key: "",
        };
        let json = serde_json::to_value(request).unwrap();
        assert_eq!(json["q"], "Hello");
        assert_eq!(json["source"], "auto");
        assert_eq!(json["target"], "ru");
        assert!(json.get("api_key").is_none());

        let response: TranslationResponse =
            serde_json::from_str(r#"{"translatedText":"Привет"}"#).unwrap();
        assert_eq!(response.text, "Привет");
    }
}
