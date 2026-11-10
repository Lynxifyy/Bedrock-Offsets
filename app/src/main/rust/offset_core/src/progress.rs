//! Progress callbacks from the engine into Kotlin.
//!
//! The engine holds the JVM and a global reference to the Kotlin callback. When
//! a scan reports progress the callback is invoked on the thread that reports
//! it, attaching to the JVM first when that thread is a Rayon worker.
//!
//! Attaching is what the JNI specification requires before a native thread may
//! touch Java objects. The thread is attached for the duration of the call and
//! detached again, because a permanent attachment per worker would keep the JVM
//! from exiting. A callback that throws is swallowed: a failed progress update
//! must never abort a scan that is otherwise succeeding.

use std::sync::Arc;

use jni::objects::{GlobalRef, JValue};
use jni::{JNIEnv, JavaVM};

use crate::scanner::ProgressSink;

/// Method the callback object must expose.
const CALLBACK_METHOD: &str = "onProgress";

/// Method signature: `(ILjava/lang/String;)V`.
const CALLBACK_SIGNATURE: &str = "(ILjava/lang/String;)V";

/// A Kotlin `ProgressCallback` the engine can call from any thread.
pub struct JniProgressSink {
    vm: JavaVM,
    callback: GlobalRef,
}

impl JniProgressSink {
    /// Wraps `callback` so the engine can report progress to it.
    ///
    /// # Errors
    ///
    /// Returns a message when the JVM handle or the global reference cannot be
    /// created, which means the callback cannot be reached at all.
    pub fn new(env: &mut JNIEnv, callback: &jni::objects::JObject) -> Result<Arc<Self>, String> {
        let vm = env
            .get_java_vm()
            .map_err(|error| format!("cannot reach the JVM: {error}"))?;
        let callback = env
            .new_global_ref(callback)
            .map_err(|error| format!("cannot retain the callback: {error}"))?;
        Ok(Arc::new(Self { vm, callback }))
    }

    /// Invokes `onProgress(percent, message)` on the current thread.
    fn invoke(&self, percent: u32, message: &str) {
        // A Rayon worker is not attached to the JVM, so attach it first. On the
        // thread that entered from Java this is a no-op that does not detach.
        let Ok(mut guard) = self.vm.attach_current_thread() else {
            return;
        };
        let env: &mut JNIEnv = &mut guard;

        let Ok(text) = env.new_string(message) else {
            return;
        };
        let result = env.call_method(
            self.callback.as_obj(),
            CALLBACK_METHOD,
            CALLBACK_SIGNATURE,
            &[JValue::Int(percent.min(100) as i32), JValue::Object(&text)],
        );

        if result.is_err() || env.exception_check().unwrap_or(false) {
            // The callback threw. Clearing the pending exception is mandatory:
            // returning to Java with one pending is undefined behaviour.
            let _ = env.exception_clear();
        }
    }
}

impl ProgressSink for JniProgressSink {
    fn report(&self, percent: u32, message: &str) {
        self.invoke(percent, message);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_callback_signature_matches_the_kotlin_interface() {
        // `(I, String) -> void`; a mismatch would abort the JVM at call time.
        assert_eq!(CALLBACK_SIGNATURE, "(ILjava/lang/String;)V");
        assert_eq!(CALLBACK_METHOD, "onProgress");
    }
}
