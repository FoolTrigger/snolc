use std::ffi::{c_char, CStr, CString};
use std::path::Path;
use std::sync::Mutex;

use crate::deployment::Deployment;
use crate::engine::{Engine, EngineHandle, Host};
use crate::events::Event;

pub type ProtectCallback = unsafe extern "C" fn(i32) -> i32;

static STOP_HANDLE: Mutex<Option<EngineHandle>> = Mutex::new(None);

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
        log_msg(6, "snolc_engine_start: config_path is null");
        return -1;
    }

    let path_str = match unsafe { CStr::from_ptr(config_path) }.to_str() {
        Ok(s) => s,
        Err(e) => {
            log_msg(6, &format!("snolc_engine_start: invalid UTF-8 path: {:?}", e));
            return -1;
        }
    };

    log_msg(4, &format!("snolc_engine_start: loading config from {}", path_str));
    let path = Path::new(path_str);

    let deployment = match Deployment::load(path) {
        Ok(d) => d,
        Err(e) => {
            log_msg(6, &format!("Deployment::load error: {:?}", e));
            return -2;
        }
    };

    let (config, modules) = deployment.into_parts();
    log_msg(4, &format!("Deployment loaded: {} modules", modules.len()));

    let validated = match Engine::validate(config, modules) {
        Ok(v) => v,
        Err(e) => {
            log_msg(6, &format!("Engine::validate error: {:?}", e));
            return -3;
        }
    };

    let host = NativeHost { protect_cb };
    let (mut engine, handle) = match Engine::build(validated, host) {
        Ok(pair) => pair,
        Err(e) => {
            log_msg(6, &format!("Engine::build error: {:?}", e));
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
            log_msg(6, &format!("Engine::run error: {:?}", e));
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
