package com.yufish.autocount

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One worker owns a session until its cleanup finishes, even after cancellation. */
class SessionGate {
    private val occupied = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    fun begin(): Boolean {
        if (!occupied.compareAndSet(false, true)) return false
        generation.incrementAndGet()
        active.set(true)
        return true
    }

    fun cancel() { active.set(false); generation.incrementAndGet() }
    fun finish() { active.set(false); occupied.set(false) }
    fun busy() = occupied.get()
    fun active() = active.get()
    fun token() = generation.get()
    fun accepts(token: Long) = active.get() && token == generation.get()
}
