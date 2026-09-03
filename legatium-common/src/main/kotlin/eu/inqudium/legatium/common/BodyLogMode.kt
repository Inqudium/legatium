package eu.inqudium.legatium.common

/**
 * When a captured body reaches the exchange line - per direction, the switch that decides log VOLUME
 * (ADR-0006).
 *
 * `always` means every body of every call. What an operator nearly always wants is "bodies only when
 * something went wrong": that cuts the volume by orders of magnitude and hits exactly the lines a body
 * is wanted for. The outcome is final before the line is written, so the response side simply decides
 * at emission. The request body flows BEFORE the outcome is known, so [ON_FAILURE] captures it exactly
 * like [ALWAYS] does (bounded by `max-body-bytes`) and discards it for a success: the capture is paid,
 * the output is saved - and the output is what burdens the log pipeline.
 *
 * "Not success" is the outcome vocabulary of the exchange line: `failure`, `timeout`, and on the
 * WebClient twin `cancelled`. A 4xx answer is a `success` outcome (the peer answered; the request was
 * wrong) and logs no body in [ON_FAILURE]; a 5xx is a `failure` and does.
 */
enum class BodyLogMode {
    /** Nothing is captured for logging; a size meter may still install a count-only capture. */
    NEVER,

    /** Captured on every call, logged only when the exchange's outcome is not `success`. */
    ON_FAILURE,

    /** Captured and logged on every call. */
    ALWAYS,
    ;

    /** Whether a bounded capture must be installed: the bytes are needed unless the mode is [NEVER]. */
    val captures: Boolean
        get() = this != NEVER

    /** Whether the captured body is written to the line of an exchange that [succeeded] (outcome `success`) or did not. */
    fun logs(succeeded: Boolean): Boolean =
        when (this) {
            NEVER -> false
            ON_FAILURE -> !succeeded
            ALWAYS -> true
        }
}
