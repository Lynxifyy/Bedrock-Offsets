package com.chimerateam.offsetextractor.data.native

/**
 * Raised by the native engine when an operation cannot be completed.
 *
 * The message is formatted as `kind: detail`, where `kind` is one of
 * `missing_so`, `invalid_apk`, `invalid_descriptor`, `storage_full`,
 * `out_of_memory`, `io_error`, `invalid_elf`, `unsupported_arch`,
 * `missing_section`, `invalid_pattern`, `cancelled` or `unknown`.
 *
 * @property kind machine-readable failure classification.
 * @property detail human-readable description.
 */
class EngineFailure(
    val kind: String,
    val detail: String
) : RuntimeException("$kind: $detail") {

    /**
     * Builds a failure from the single message the JVM constructs.
     *
     * JNI `ThrowNew` always resolves `<init>(Ljava/lang/String;)V`, so this is
     * the constructor the native engine's `throw_new` reaches. Without it the
     * JVM raises a `NoSuchMethodError` instead and the intended failure is lost.
     *
     * @param message the `kind: detail` text produced by the engine.
     */
    constructor(message: String) : this(
        kind = kindOf(message),
        detail = detailOf(message)
    )

    companion object {
        /**
         * Parses a raw `kind: detail` message.
         *
         * @param raw message thrown across the JNI boundary.
         * @return the parsed kind and detail; `unknown` when unparseable.
         */
        fun parse(raw: String?): EngineFailure = EngineFailure(raw.orEmpty())

        /** Extracts the kind from a `kind: detail` message. */
        private fun kindOf(raw: String): String =
            if (raw.indexOf(':') <= 0) "unknown" else raw.substringBefore(':').trim()

        /** Extracts the detail from a `kind: detail` message. */
        private fun detailOf(raw: String): String = when {
            raw.indexOf(':') <= 0 -> raw.ifEmpty { "native failure" }
            else -> raw.substringAfter(':').trim()
        }
    }
}
