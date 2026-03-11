import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main() = runBlocking {
    if (!runHealthCheck()) {
        exitProcess(1)
    }

    // Launch profile actor (RocksDB + profile generation)
    launchProfileActor()

    // Flush dirty records on shutdown (Ctrl+C, SIGTERM, etc.)
    Runtime.getRuntime().addShutdownHook(Thread {
        flushAll()
    })

    runBot()
}
