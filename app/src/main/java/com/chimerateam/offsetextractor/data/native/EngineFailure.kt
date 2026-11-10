package com.chimerateam.offsetextractor.data.native

/**
 * Raised by the native engine when an operation cannot be completed.
 *
 * The message is formatted as `kind: detail`, where `kind` is one of
 * `missing_so`, `invalid_apk`, `invalid_descriptor`, `storage_full`,
 * `io_error`, `invalid_elf`, `unsupported_arch`, `missing_section`,
 * `invalid_pattern`, `cancelled` or `unknown`.
 *
 * @property kind machine-readable failure classification.
 * @property detail human-readable description.
 */
class EngineFailure(
    val kind: String,
    val detail: String
) : RuntimeException("$kind: $detail") {

    /** Splits the raw message back into its kind and detail parts. */
    companion object {
        /**
         * Parses a raw `kind: detail` message.
         *
         * @param raw message thrown across the JNI boundary.
         * @return the parsed kind and detail; `unknown` when unparseable.
         */
        fun parse(raw: String?): EngineFailure {
            val text = raw.orEmpty()
            val separator = text.indexOf(':')
            return if (separator <= 0) {
                EngineFailure("unknown", text.ifEmpty { "native failure" })
            } else {
                EngineFailure(
                    kind = text.substring(0, separator).trim(),
                    detail = text.substring(separator + 1).trim()
                )
            }
        }
    }
}
