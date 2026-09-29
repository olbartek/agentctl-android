package io.github.olbartek.agentctl.examples.agentshop.models

import io.github.olbartek.agentctl.MockBackend

/**
 * Backend failures scheduled at launch, for UI tests.
 *
 * A UI test cannot type `mock orders.fetchOrders network` the way a script can, so it passes a launch extra instead:
 * the spec `orders.fetchOrders#2=network` makes the *second* call to `orders.fetchOrders` fail with `network`. The
 * call index is what lets a fault land on the call a scenario meant. Headless runs and the agent bridge use `mock`
 * and leave this empty.
 *
 * Malformed specs are ignored, as the reference ignores them: no `=`, no `#<n>`, an index that is not a number, or
 * an empty method.
 */
class ScheduledFaults(specs: List<String> = emptyList()) {
    private val lock = Any()
    private val plan = mutableMapOf<String, MutableMap<Int, String>>()
    private val counts = mutableMapOf<String, Int>()

    init {
        for (spec in specs) {
            // Swift's `split`, which drops empty pieces: `a#1=` has no code and `=x` no target.
            val parts = spec.split("=", limit = 2).filter { it.isNotEmpty() }
            if (parts.size != 2) continue
            val target = parts[0].split("#").filter { it.isNotEmpty() }
            if (target.size != 2) continue
            val index = target[1].toIntOrNull() ?: continue
            plan.getOrPut(target[0]) { mutableMapOf() }[index] = parts[1]
        }
    }

    /** Counts one call to `method` and returns the error code scheduled for it, if any. */
    fun next(method: String): String? = synchronized(lock) {
        val count = (counts[method] ?: 0) + 1
        counts[method] = count
        plan[method]?.get(count)
    }

    companion object {
        /** The launch argument (or intent extra) a UI test passes, once per spec: `-mock-fault <method>#<n>=<code>`. */
        const val ARGUMENT: String = "mock-fault"

        /** Reads specs the reference's way, from `-mock-fault <spec>` pairs in a command line. */
        fun fromArguments(arguments: List<String>): ScheduledFaults {
            val specs = mutableListOf<String>()
            val iterator = arguments.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() != "-$ARGUMENT" || !iterator.hasNext()) continue
                specs.add(iterator.next())
            }
            return ScheduledFaults(specs)
        }
    }
}

/**
 * The one shim every AgentShop client method goes through: [call] arms a fault scheduled for this call (see
 * [ScheduledFaults]), then hands over to AgentCtl's [MockBackend.call], which logs the call, waits the mock latency
 * and throws a fault armed by `mock`.
 */
class ShopCalls(val mocks: MockBackend, val scheduled: ScheduledFaults = ScheduledFaults()) {
    suspend fun <T> call(name: String, error: (String) -> Throwable, body: suspend () -> T): T {
        scheduled.next(name)?.let { code -> mocks.faults.set(name, code) }
        return mocks.call(name, error, body)
    }

    /** A call that is logged like every mock call, but has no latency and cannot fail: the session's. */
    fun <T> logged(name: String, body: () -> T): T {
        mocks.log.begin(name)
        try {
            return body()
        } finally {
            mocks.log.end(name)
        }
    }
}
