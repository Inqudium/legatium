package eu.inqudium.legatium.common

/**
 * The body captures of one exchange, one per direction, chosen from the configuration - ONE rule for
 * both twins (ADR-0003), while the capture TYPE stays each twin's own ([of] takes the constructor): the
 * concurrency model differs per stack, the rule does not. A direction gets a capture buffering up to
 * [ClientLoggingProperties.maxBodyBytes] when its [BodyLogMode] captures, a COUNT-ONLY capture (limit 0)
 * when the body is merely measured, and none when it is neither logged nor measured.
 */
internal class BodyCaptures<C : Any> private constructor(
    val request: C?,
    val response: C?,
) {
    companion object {
        /** The captures [properties] ask for, built by [newCapture] from the byte limit each one gets. */
        fun <C : Any> of(
            properties: ClientLoggingProperties,
            newCapture: (maxBytes: Int) -> C,
        ): BodyCaptures<C> =
            BodyCaptures(
                request = captureFor(properties.logRequestBody, properties.measureRequestBodySize, properties.maxBodyBytes, newCapture),
                response = captureFor(properties.logResponseBody, properties.measureResponseBodySize, properties.maxBodyBytes, newCapture),
            )

        /**
         * One direction's capture: buffering up to [maxBytes] when the body is logged in ANY mode
         * ([BodyLogMode.captures]), count-only when merely [measured], none otherwise.
         */
        private fun <C : Any> captureFor(
            mode: BodyLogMode,
            measured: Boolean,
            maxBytes: Int,
            newCapture: (Int) -> C,
        ): C? =
            when {
                mode.captures -> newCapture(maxBytes)
                measured -> newCapture(0)
                else -> null
            }
    }
}
