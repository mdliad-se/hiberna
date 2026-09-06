package com.jinatra.hiberna.privilege

import com.jinatra.hiberna.shell.ShellResult

/**
 * Test double for [ShizukuPlatform]. Lives in `src/test` on purpose: a fake
 * privilege platform in `src/main` would ship in the release APK and could
 * satisfy the release boot assertion, which is the one thing that assertion
 * exists to prevent.
 *
 * All state is mutable so tests can move the platform *after* construction and
 * assert a transition, rather than re-asserting a value the constructor
 * already saw.
 */
internal class FakeShizukuPlatform(
    var binderAlive: Boolean = true,
    var permissionGranted: Boolean = true,
    var installed: Boolean = true,
) : ShizukuPlatform {

    var scriptedResult: ShellResult = ShellResult(0, "", "")
    var requestCount: Int = 0
        private set
    val executed = mutableListOf<List<String>>()

    /**
     * Per-command results, keyed by a substring match against the joined
     * argv - the same convention [com.jinatra.hiberna.shell.FakeShellBackend.script]
     * uses. Added for Task 14's wiring tests, which need `appops`,
     * `deviceidle` and `netpolicy` to each answer differently in one test
     * rather than sharing a single [scriptedResult] for every command. A
     * command matching no [script] entry falls back to [scriptedResult], so
     * every pre-existing test that only ever sets that one field keeps
     * working unchanged.
     */
    private val scripted = linkedMapOf<String, ShellResult>()

    fun script(match: String, result: ShellResult) {
        scripted[match] = result
    }

    /**
     * Opt-in: when set, [requestPermission] throws instead of returning
     * normally, matching the documented Shizuku SDK behaviour of throwing
     * when the binder is dead. Off by default so every other test keeps
     * exercising the non-throwing path.
     */
    var requestPermissionThrows: Boolean = false

    private var listener: (() -> Unit)? = null
    val hasListener: Boolean get() = listener != null

    /**
     * Names of the threads that reached a would-be-IPC member. The real
     * platform blocks on binder here, so tests use this to prove the calls do
     * not happen on the caller's dispatcher.
     */
    val observedThreads = mutableListOf<String>()

    private fun <T> observing(value: T): T {
        observedThreads += Thread.currentThread().name
        return value
    }

    /** Simulates a binder-received / binder-dead / permission-result callback. */
    fun emitStateChanged() {
        listener?.invoke()
    }

    override val isInstalled: Boolean get() = observing(installed)
    override val isBinderAlive: Boolean get() = observing(binderAlive)
    override fun checkSelfPermission(): Boolean = observing(permissionGranted)
    override fun requestPermission() {
        observing(Unit)
        requestCount++
        if (requestPermissionThrows) throw IllegalStateException("binder is dead")
    }

    /**
     * Matches [RealShizukuPlatform]: a second registration replaces the first
     * rather than throwing. See the threading/registration note on
     * [ShizukuPlatform.addStateListener].
     */
    override fun addStateListener(onChanged: () -> Unit) {
        listener = onChanged
    }

    override fun removeStateListener() { listener = null }

    override fun exec(command: List<String>): ShellResult {
        observedThreads += Thread.currentThread().name
        executed += command
        val joined = command.joinToString(" ")
        val hit = scripted.entries.firstOrNull { joined.contains(it.key) }
        return hit?.value ?: scriptedResult
    }
}
