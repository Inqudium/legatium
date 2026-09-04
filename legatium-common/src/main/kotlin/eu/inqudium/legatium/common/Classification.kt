package eu.inqudium.legatium.common

import org.slf4j.event.Level

/**
 * What an exchange classifies to at emission: the SLF4J level (severity), the [ClientOutcome] (the
 * semantic - the field a dashboard splits by), and the cause to attach, if any. Severity and semantic
 * are decoupled on purpose: a 5xx answer is WARN with `failure`, a call that threw is ERROR with
 * `failure`, a timeout is WARN with its own outcome. One shape for both twins' emitters.
 */
internal class Classification(
    val level: Level,
    val outcome: ClientOutcome,
    val cause: Throwable?,
)
