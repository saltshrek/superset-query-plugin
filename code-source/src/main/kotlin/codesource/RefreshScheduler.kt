package codesource

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Refreshes [source] now, then every [interval]. With a zero interval it only
 * retries until the first refresh succeeds (every [retryInterval]), then stops.
 */
class RefreshScheduler(
    private val source: GitCodeSource,
    private val interval: Duration,
    private val retryInterval: Duration = 30.seconds,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RefreshScheduler::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "refresh").apply { isDaemon = true } }

    fun start() {
        executor.execute(::run)
    }

    private fun run() {
        try {
            source.refresh()
        } catch (e: RefreshFailedException) {
            log.warn("Refresh failed: {}", e.message)
        }
        val next = when {
            interval.isPositive() -> interval
            !source.isReady -> retryInterval
            else -> return
        }
        executor.schedule(::run, next.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
