package eu.inqudium.legatium.common

/**
 * The walk over a failure's CAUSE GRAPH that every by-name classification of a failed call shares:
 * [Timeouts] and the blocking twin's cancellation recognition decide the same exchange one after the
 * other, so they must see the same throwables. Only the PREDICATE is theirs - the names they match -
 * while the walk itself exists once: the throwable, every cause, and every SUPPRESSED exception,
 * recursively (Reactor's composite errors from `zip`/`merge`/`when` carry their components as
 * suppressed, not as the cause), each throwable visited once even in a cyclic graph. The walk over a
 * link's class hierarchy exists once as well ([hasType]).
 */
internal object CauseGraph {
    /** True when [predicate] holds for [throwable] or any throwable reachable from it. Null has no graph. */
    fun any(
        throwable: Throwable?,
        predicate: (Throwable) -> Boolean,
    ): Boolean {
        // A cause graph is finite in practice, but a cycle is legal; the visited set bounds the walk.
        val visited = HashSet<Throwable>()
        val pending = ArrayDeque<Throwable>()
        throwable?.let(pending::add)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            if (predicate(current)) {
                return true
            }
            current.cause?.let(pending::add)
            current.suppressed.forEach(pending::add)
        }
        return false
    }

    /** True when [predicate] holds for [type] or one of its superclasses. */
    fun hasType(
        type: Class<*>,
        predicate: (Class<*>) -> Boolean,
    ): Boolean = generateSequence(type) { it.superclass }.any(predicate)

    /** True when [type] or one of its superclasses has a fully qualified name in [names]. */
    fun hasTypeNamed(
        type: Class<*>,
        names: Set<String>,
    ): Boolean = hasType(type) { it.name in names }
}
