package codesource

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Collects what the service logs, at the levels it's configured to log, until
 * closed. Includes exception messages, since they're logged too.
 */
class LogCapture : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        root.addAppender(appender)
    }

    val messages: List<String>
        get() = appender.list.toList().flatMap { event ->
            buildList {
                add(event.formattedMessage)
                var t = event.throwableProxy
                while (t != null) {
                    add(t.message ?: "")
                    t = t.cause
                }
            }
        }

    override fun close() {
        root.detachAppender(appender)
    }
}
