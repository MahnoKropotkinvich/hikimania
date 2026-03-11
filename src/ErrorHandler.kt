import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.network.sockets.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

// Unified coroutine exception handler — child coroutine failures
// are logged by type without killing the parent scope.

private val log = KotlinLogging.logger("ErrorHandler")

val botExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    when (throwable) {
        // Network / DNS / timeout — already retried in claudeChat, just log
        is ConnectTimeoutException, is ConnectException, is UnresolvedAddressException ->
            log.warn { "Network error: ${throwable::class.simpleName}: ${throwable.message}" }

        // Serialization bug — something is wrong with our code
        is SerializationException ->
            log.error(throwable) { "Serialization error (bug?)" }

        // Everything else
        else ->
            log.error(throwable) { "${throwable::class.simpleName}: ${throwable.message}" }
    }
}
