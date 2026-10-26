package dev.kaff.app.domain.repository

/** Wall clock for history timestamps and a monotonic clock for durations and timers. */
interface Clock {
    fun wallMs(): Long
    fun elapsedMs(): Long
}
