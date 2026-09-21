package eu.inqudium.legatium.common

import io.micrometer.core.instrument.MeterRegistry

/** The count of the counter [name] carries under [tags] - the one registry read every meter assertion makes. */
internal fun MeterRegistry.count(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).counter().count()
