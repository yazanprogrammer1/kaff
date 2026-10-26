package dev.kaff.app.domain.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs a reducer: events are processed strictly one at a time, the new model is published, then
 * the effects run in order. Effects that take long launch their own coroutine and report back with
 * events, so the loop never blocks on a file transfer.
 */
class SessionRunner<M, E, F>(
    initial: M,
    private val scope: CoroutineScope,
    private val reduce: (M, E) -> Transition<M, F>,
    private val handle: suspend (F) -> Unit,
) {
    private val _model = MutableStateFlow(initial)
    val model: StateFlow<M> = _model.asStateFlow()

    private val events = Channel<E>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                val transition = reduce(_model.value, event)
                _model.value = transition.model
                transition.effects.forEach { handle(it) }
            }
        }
    }

    fun dispatch(event: E) {
        events.trySend(event)
    }
}

/** Named one-shot timers; starting a running timer restarts it. Safe to use from any thread. */
class SessionTimers<K>(private val scope: CoroutineScope, private val onFire: (K) -> Unit) {
    private val jobs = mutableMapOf<K, Job>()

    fun start(key: K, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            onFire(key)
        }
        synchronized(jobs) { jobs.put(key, job) }?.cancel()
    }

    fun cancel(key: K) {
        synchronized(jobs) { jobs.remove(key) }?.cancel()
    }
}
