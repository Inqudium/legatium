package eu.inqudium.legatium.common

import io.micrometer.core.instrument.MeterRegistry

/** The count of the counter [name] carries under [tags] - the one registry read every counter assertion makes. */
internal fun MeterRegistry.count(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).counter().count()

/** The value of the gauge [name] carries under [tags] - the one registry read every gauge assertion makes. */
internal fun MeterRegistry.gaugeValue(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).gauge().value()
