package eu.inqudium.legatium.common

/**
 * The logical name of a client - the value of `adapter_name` ([ClientLogField.NAME]) and the `name` tag
 * of the body meters - as the host application declares it: a request attribute the host sets ONCE per
 * client builder (`defaultRequest { it.attribute(ATTRIBUTE, "billing") }` on a `RestClient` or a
 * `WebClient`), which every request of that client then carries and both twins read at wiring time
 * (ADR-0009).
 *
 * An attribute rather than a header, a property or a URI rule: a header would go on the wire and change
 * the request the module only observes; a property is per application, and the point is telling several
 * clients of ONE application apart; a rule on the URI is what breaks behind a sidecar in the first
 * place.
 */
internal object AdapterName {
    /**
     * The request attribute both twins read the client's name from - ONE string on both stacks, so a
     * host that carries both jars names its clients with one constant.
     */
    const val ATTRIBUTE = "eu.inqudium.legatium.adapterName"

    /**
     * The name an attribute value yields: the string itself, or null when the attribute is absent, not
     * a string, or blank - a blank name would be an empty bucket that only looks like a client.
     */
    fun of(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }
}
