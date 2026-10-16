use std::ffi::{c_char, CStr};
#[cfg(target_os = "android")]
use std::ffi::CString;
use std::path::Path;
use std::sync::Mutex;

use crate::deployment::Deployment;
use crate::engine::{Engine, EngineHandle, Host};
use crate::events::Event;

pub type ProtectCallback = unsafe extern "C" fn(i32) -> i32;

static STOP_HANDLE: Mutex<Option<EngineHandle>> = Mutex::new(None);
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

pub fn set_last_error(msg: &str) {
    if let Ok(mut lock) = LAST_ERROR.lock() {
        *lock = msg.to_string();
    }
}

pub fn get_last_error() -> String {
    LAST_ERROR.lock().map(|l| l.clone()).unwrap_or_default()
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn snolc_engine_get_last_error(buf: *mut c_char, max_len: usize) -> i32 {
    if buf.is_null() || max_len == 0 {
        return -1;
    }
    let err = get_last_error();
    let bytes = err.as_bytes();
    let copy_len = bytes.len().min(max_len - 1);
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf.cast(), copy_len);
        *buf.add(copy_len) = 0;
    }
    copy_len as i32
}

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
}

pub fn log_msg(prio: i32, msg: &str) {
    #[cfg(target_os = "android")]
    {
        if let (Ok(tag), Ok(text)) = (CString::new("snolc-engine"), CString::new(msg)) {
            unsafe {
                __android_log_write(prio, tag.as_ptr(), text.as_ptr());
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[snolc] (prio={prio}) {msg}");
    }
}

struct NativeHost {
    protect_cb: Option<ProtectCallback>,
}

impl Host for NativeHost {
    fn engine_event(&self, event: &Event) {
        log_msg(4, &format!("Engine event: {:?}", event));
        if let Event::ModuleError { instance, message } = event {
            set_last_error(&format!("{}: {}", instance, message));
        }
    }

    fn protect_socket(&self, socket: i64) -> bool {
        if let Some(cb) = self.protect_cb {
            let res = unsafe { cb(socket as i32) };
            log_msg(4, &format!("protect_socket fd={} -> {}", socket, res));
            res != 0
        } else {
            true
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn snolc_engine_start(
    config_path: *const c_char,
    protect_cb: Option<ProtectCallback>,
) -> i32 {
    if config_path.is_null() {
        let msg = "snolc_engine_start: config_path is null";
        log_msg(6, msg);
        set_last_error(msg);
        return -1;
    }

    let path_str = match unsafe { CStr::from_ptr(config_path) }.to_str() {
        Ok(s) => s,
        Err(e) => {
            let msg = format!("snolc_engine_start: invalid UTF-8 path: {:?}", e);
            log_msg(6, &msg);
            set_last_error(&msg);
            return -1;
        }
    };

    log_msg(4, &format!("snolc_engine_start: loading config from {}", path_str));
    let path = Path::new(path_str);

    let deployment = match Deployment::load(path) {
        Ok(d) => d,
        Err(e) => {
            let msg = format!("Deployment::load error: {:?}", e);
            log_msg(6, &msg);
            set_last_error(&msg);
            return -2;
        }
    };

    let (config, modules) = deployment.into_parts();
    log_msg(4, &format!("Deployment loaded: {} modules", modules.len()));

    let validated = match Engine::validate(config, modules) {
        Ok(v) => v,
        Err(e) => {
            let msg = format!("Engine::validate error: {:?}", e);
            log_msg(6, &msg);
            set_last_error(&msg);
            return -3;
        }
    };

    let host = NativeHost { protect_cb };
    let (mut engine, handle) = match Engine::build(validated, host) {
        Ok(pair) => pair,
        Err(e) => {
            let msg = format!("Engine::build error: {:?}", e);
            log_msg(6, &msg);
            set_last_error(&msg);
            return -4;
        }
    };

    if let Ok(mut lock) = STOP_HANDLE.lock() {
        *lock = Some(handle);
    }

    engine.set_idle_wait(true);
    log_msg(4, "snolc_engine_start: starting engine run loop");

    let result = engine.run();

    if let Ok(mut lock) = STOP_HANDLE.lock() {
        *lock = None;
    }

    match result {
        Ok(()) => {
            log_msg(4, "snolc_engine_start: engine stopped cleanly");
            0
        }
        Err(e) => {
            let last = get_last_error();
            let msg = if last.is_empty() {
                format!("Engine::run error: {:?}", e)
            } else {
                format!("Engine::run error: {:?} ({})", e, last)
            };
            log_msg(6, &msg);
            set_last_error(&msg);
            -5
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn snolc_engine_stop() {
    log_msg(4, "snolc_engine_stop: requesting shutdown");
    if let Ok(mut lock) = STOP_HANDLE.lock() {
        if let Some(handle) = lock.take() {
            let _ = handle.request_shutdown();
        }
    }
}
