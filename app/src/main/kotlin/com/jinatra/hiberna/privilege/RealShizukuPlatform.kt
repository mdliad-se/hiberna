package com.jinatra.hiberna.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.jinatra.hiberna.shell.ShellExit
import com.jinatra.hiberna.shell.ShellResult
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * The only class in this app that touches the Shizuku SDK.
 *
 * See [ShizukuPlatform] for the threading contract: every member here blocks
 * on binder.
 */
internal class RealShizukuPlatform(
    private val context: Context,
    private val requestCode: Int = PERMISSION_REQUEST_CODE,
) : ShizukuPlatform {

    private var binderReceived: Shizuku.OnBinderReceivedListener? = null
    private var binderDead: Shizuku.OnBinderDeadListener? = null
    private var permissionResult: Shizuku.OnRequestPermissionResultListener? = null

    override val isInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            // A PackageManager that throws anything else is not evidence of
            // absence; saying "not installed" here would send a Shizuku user
            // to the install screen.
            logError("package lookup for $SHIZUKU_PACKAGE failed", e)
            false
        }

    override val isBinderAlive: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

    override fun checkSelfPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    override fun requestPermission() {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            logError("requestPermission failed", e)
        }
    }

    override fun addStateListener(onChanged: () -> Unit) {
        removeStateListener()
        val received = Shizuku.OnBinderReceivedListener { onChanged() }
        val dead = Shizuku.OnBinderDeadListener { onChanged() }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> onChanged() }
        try {
            // Sticky: if the provider already handed this process a live
            // binder before the gate was built - the common case when Shizuku
            // is already running at launch - a plain listener would never fire
            // and the gate would sit at CHECKING forever.
            Shizuku.addBinderReceivedListenerSticky(received)
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
        } catch (e: Exception) {
            logError("could not register Shizuku state listeners", e)
        }
        // Stored even if one registration threw, so removeStateListener still
        // unwinds whatever did get registered.
        binderReceived = received
        binderDead = dead
        permissionResult = permission
    }

    override fun removeStateListener() {
        try {
            binderReceived?.let { Shizuku.removeBinderReceivedListener(it) }
            binderDead?.let { Shizuku.removeBinderDeadListener(it) }
            permissionResult?.let { Shizuku.removeRequestPermissionResultListener(it) }
        } catch (e: Exception) {
            logError("could not unregister Shizuku state listeners", e)
        }
        binderReceived = null
        binderDead = null
        permissionResult = null
    }

    /**
     * Runs [command] as the shell user and returns its result.
     *
     * Failure modes are separated by [ShellExit] sentinel codes rather than a
     * single `-1`, because "Shizuku is not running" and "R8 ate the reflected
     * method" are indistinguishable to a user and unrelated bugs to fix.
     *
     * The process is destroyed on every path. Note that
     * [ShizukuRemoteProcess]'s constructor also registers the wrapper in a
     * private static cache the SDK only clears when the binder dies;
     * [ShizukuRemoteProcess.destroy] releases the remote process but that
     * wrapper reference is retained SDK-side and cannot be removed from here.
     */
    override fun exec(command: List<String>): ShellResult {
        val method = newProcess ?: return ShellResult(
            ShellExit.REFLECTION_UNAVAILABLE,
            "",
            "Shizuku.newProcess could not be resolved; this build cannot run privileged commands",
        )

        // Kept as the raw reflection result, not cast yet: if invoke()
        // actually spawned a remote process but the cast below throws, the
        // process must still be destroyed. Casting inside the try/finally
        // below - rather than out here - is what makes that reachable.
        val rawProcess = try {
            method.invoke(null, command.toTypedArray(), null, null)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return interrupted(command)
        } catch (e: Exception) {
            logError("could not start: ${command.joinToString(" ")}", e)
            return ShellResult(ShellExit.EXEC_FAILED, "", "shizuku exec failed: ${diagnose(e)}")
        }

        try {
            val process = rawProcess as ShizukuRemoteProcess

            // Both pipes are drained concurrently. Reading stdout to EOF first
            // deadlocks any command that fills the stderr pipe buffer before
            // closing stdout, and `cmd` subcommands do exactly that on error.
            val stdout = StreamDrain(process.inputStream, "stdout").also { it.start() }
            val stderr = StreamDrain(process.errorStream, "stderr").also { it.start() }

            // java.lang.Process.waitFor(long, TimeUnit) is NOT overridden by
            // ShizukuRemoteProcess, and the JDK default polls exitValue(),
            // which this wrapper throws over binder. waitForTimeout is the
            // only bounded wait available - but the bound is enforced
            // *remotely*: the timeout value is forwarded over binder and
            // system_server is the one that reports back once it elapses. If
            // the binder transaction itself wedges, rather than the command
            // merely taking too long, there is no client-side bound here and
            // this call can block indefinitely.
            val exited = process.waitForTimeout(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) {
                logError("timed out after ${EXEC_TIMEOUT_MS}ms: ${command.joinToString(" ")}", null)
                return ShellResult(
                    ShellExit.TIMED_OUT,
                    "",
                    "shizuku exec timed out after ${EXEC_TIMEOUT_MS}ms and was destroyed",
                )
            }

            stdout.join(DRAIN_JOIN_MS)
            stderr.join(DRAIN_JOIN_MS)

            // A join() timing out with no exception is a silent success
            // look-alike: ShellResult(0, "", "") parses downstream as "ran
            // fine, nothing restricted". A drain thread still alive after its
            // join window must downgrade the result rather than pass through
            // whatever partial text it read.
            val stuckDrains = listOfNotNull(
                "stdout".takeIf { stdout.isAlive },
                "stderr".takeIf { stderr.isAlive },
            )
            if (stuckDrains.isNotEmpty()) {
                logError(
                    "drain thread(s) still running ${DRAIN_JOIN_MS}ms after $stuckDrains: " +
                        command.joinToString(" "),
                    null,
                )
                return ShellResult(
                    ShellExit.EXEC_FAILED,
                    "",
                    "shizuku exec: $stuckDrains read timed out after ${DRAIN_JOIN_MS}ms; " +
                        "output may be incomplete",
                )
            }

            val exit = process.waitFor()
            val drainFailure = listOfNotNull(stdout.failureNote(), stderr.failureNote())
            return ShellResult(
                exit,
                stdout.text.trim(),
                (stderr.text.trim() + drainFailure.joinToString("") { "\n$it" }).trim(),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return interrupted(command)
        } catch (e: Exception) {
            logError("failed while running: ${command.joinToString(" ")}", e)
            return ShellResult(ShellExit.EXEC_FAILED, "", "shizuku exec failed: ${diagnose(e)}")
        } finally {
            try {
                (rawProcess as? Process)?.destroy()
            } catch (e: Exception) {
                logError("destroy failed", e)
            }
        }
    }

    private fun interrupted(command: List<String>): ShellResult {
        logError("interrupted while running: ${command.joinToString(" ")}", null)
        return ShellResult(ShellExit.INTERRUPTED, "", "shizuku exec interrupted")
    }

    internal companion object {
        /** The Shizuku manager app. Sui users will not have it; that is fine. */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        const val PERMISSION_REQUEST_CODE = 4242

        /**
         * Ten seconds. The commands hiberna sends (`cmd appops`, `cmd
         * deviceidle`, `cmd netpolicy`) answer in tens of milliseconds on a
         * warm device; the slow case is a system_server busy behind a package
         * scan or a doze transition, which is seconds, not tens of seconds.
         * Ten gives roughly two orders of magnitude of headroom over the
         * normal case while still failing fast enough that a wedged service
         * shows up as an error the user can act on rather than a permanently
         * spinning row.
         */
        const val EXEC_TIMEOUT_MS = 10_000L

        /** Reader threads are daemons, so this only bounds the happy path. */
        const val DRAIN_JOIN_MS = 2_000L

        private const val TAG = "HibernaShizuku"

        /**
         * Resolved once per process, not once per command. `newProcess` is
         * private, so nothing but this reflection proves it survived R8; if it
         * did not, every command must fail with
         * [ShellExit.REFLECTION_UNAVAILABLE] rather than a null message.
         *
         * [com.jinatra.hiberna.assertRealBackend] turns this into a hard
         * startup failure in release builds, where minification is the only
         * way it can break.
         */
        private val newProcessMethod: Result<Method> by lazy {
            try {
                Result.success(
                    Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    ).apply { isAccessible = true },
                )
            } catch (e: Exception) {
                logError("Shizuku.newProcess is missing - minified build is broken", e)
                Result.failure(e)
            }
        }

        private val newProcess: Method? get() = newProcessMethod.getOrNull()

        /** Whether the privileged entry point resolves in this build. */
        val isPrivilegedEntryPointResolvable: Boolean get() = newProcessMethod.isSuccess

        /**
         * `InvocationTargetException.message` is null, which is how "shizuku
         * exec failed: null" reached the review. Unwrap and name the type.
         */
        private fun diagnose(t: Throwable): String {
            val root = (t as? InvocationTargetException)?.targetException ?: t
            return "${root.javaClass.name}: ${root.message ?: "(no message)"}"
        }

        /**
         * `android.util.Log` is a throwing stub under plain JVM unit tests, so
         * logging must never be the thing that fails a privileged call.
         */
        private fun logError(message: String, t: Throwable?) {
            try {
                if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
            } catch (e: Throwable) {
                // Deliberately ignored: diagnostics are not worth a crash.
            }
        }
    }
}

/**
 * Reads one pipe to EOF on its own daemon thread. Daemon so a stuck remote
 * process can never keep the app's JVM alive.
 */
private class StreamDrain(
    private val stream: InputStream,
    private val label: String,
) : Thread("hiberna-drain-$label") {

    @Volatile
    var text: String = ""
        private set

    @Volatile
    private var failure: Exception? = null

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            text = stream.bufferedReader().use { it.readText() }
        } catch (e: InterruptedException) {
            currentThread().interrupt()
            failure = e
        } catch (e: Exception) {
            failure = e
        }
    }

    /** A read error must be visible, not silently turn into empty output. */
    fun failureNote(): String? =
        failure?.let { "[$label read failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}]" }
}
