package eu.inqudium.legatium.common

/**
 * The scope that restores nothing on close - the ONE spelling of the no-op `AutoCloseable` for every
 * restorer and emitter of both twins (a restorer without a context to restore, a restoration that failed
 * and was reported), so a reader meets the same name wherever "nothing to undo" is meant.
 */
internal object NoOpScope : AutoCloseable {
    override fun close() = Unit
}
