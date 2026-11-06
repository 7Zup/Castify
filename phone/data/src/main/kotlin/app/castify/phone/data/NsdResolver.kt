package app.castify.phone.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Before API 34, Android NSD resolves one service at a time for the whole app: every mDNS
 * discovery shares this queue. [resolve] with `priority` (Castify TVs) jumps ahead of the
 * others (Google Cast devices), so a busy Wi-Fi does not delay the TVs the user can play on.
 *
 * A resolve that fails or never answers ([RESOLVE_TIMEOUT_MS]) is retried a few times
 * instead of hiding the device until the app restarts; a late answer from a timed-out
 * resolve is ignored.
 */
class NsdResolver(context: Context) {

    val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private class Request(
        val info: NsdServiceInfo,
        val priority: Boolean,
        /** False once the discovery that asked is closed: the request is dropped. */
        val wanted: () -> Boolean,
        val onResolved: (NsdServiceInfo) -> Unit,
        val attempt: Int = 0,
    )

    private val first = ConcurrentLinkedQueue<Request>()
    private val later = ConcurrentLinkedQueue<Request>()
    private val resolving = AtomicBoolean(false)
    /** Each resolve gets a number: only the current one may finish. */
    private val current = AtomicInteger(0)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timeout: ScheduledFuture<*>? = null

    fun resolve(info: NsdServiceInfo, priority: Boolean, wanted: () -> Boolean, onResolved: (NsdServiceInfo) -> Unit) {
        enqueue(Request(info, priority, wanted, onResolved))
    }

    private fun enqueue(request: Request) {
        (if (request.priority) first else later).add(request)
        next()
    }

    private fun next() {
        if (!resolving.compareAndSet(false, true)) return
        var request: Request
        do {
            request = first.poll() ?: later.poll() ?: run { resolving.set(false); return }
        } while (!request.wanted())

        val id = current.incrementAndGet()
        fun done() {
            if (!current.compareAndSet(id, id + 1)) return
            timeout?.cancel(false)
            resolving.set(false); next()
        }

        timeout = timer.schedule({
            if (current.get() == id) {
                Log.w(TAG, "Resolve timed out for ${request.info.serviceName}")
                retryLater(request); done()
            }
        }, RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        @Suppress("DEPRECATION")
        nsd.resolveService(request.info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (current.get() != id) return
                if (request.wanted()) {
                    runCatching { request.onResolved(resolved) }.onFailure { Log.w(TAG, "Resolve handler failed: ${it.message}") }
                }
                done()
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${failed.serviceName}: $errorCode")
                if (current.get() != id) return
                retryLater(request); done()
            }
        })
    }

    private fun retryLater(request: Request) {
        val attempt = request.attempt + 1
        if (attempt > MAX_ATTEMPTS || !request.wanted()) return
        timer.schedule({
            enqueue(Request(request.info, request.priority, request.wanted, request.onResolved, attempt))
        }, RETRY_MS * attempt, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val TAG = "Castify"
        const val MAX_ATTEMPTS = 3
        const val RETRY_MS = 1_000L
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
