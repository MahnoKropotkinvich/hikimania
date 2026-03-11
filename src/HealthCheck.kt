import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger("HealthCheck")

suspend fun runHealthCheck(): Boolean = coroutineScope {
    log.info { "Running health checks..." }

    val claude = async { checkClaudeAPI() }
    val napcat = async { checkNapCat() }

    val claudeOk = claude.await()
    val napcatOk = napcat.await()

    if (claudeOk && napcatOk) {
        log.info { "All health checks passed" }
        true
    } else {
        log.error { "Health checks failed. Fix configuration before continuing." }
        false
    }
}

private suspend fun checkClaudeAPI(): Boolean {
    log.info { "Checking Claude API at ${config.claudeAPIUrl}..." }
    
    return try {
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = config.healthCheckTimeoutMs
            }
        }
        
        // Try a minimal API call to verify connectivity and auth
        val response = client.post("${config.claudeAPIUrl}/v1/messages") {
            header("x-api-key", config.claudeAPIKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"${config.claudeModel}","messages":[{"role":"user","content":"test"}],"max_tokens":1}""")
        }
        
        val statusOk = when (response.status) {
            HttpStatusCode.OK -> {
                log.info { "✓ Claude API is reachable and authenticated" }
                true
            }
            HttpStatusCode.Unauthorized -> {
                log.error { "✗ Claude API authentication failed. Check your CLAUDE_API_KEY" }
                false
            }
            else -> {
                val body = response.bodyAsText().take(200)
                log.warn { "⚠ Claude API returned ${response.status}. Response: $body" }
                true  // Consider it OK if not auth error
            }
        }
        
        client.close()
        statusOk
    } catch (e: Exception) {
        log.error(e) { "✗ Failed to connect to Claude API at ${config.claudeAPIUrl}" }
        false
    }
}

private suspend fun checkNapCat(): Boolean {
    log.info { "Checking NapCat WebSocket at ${config.napcatWSUrl}..." }
    
    return try {
        withTimeout(config.healthCheckTimeoutMs) {
            val client = HttpClient(CIO) {
                install(WebSockets)
                install(HttpTimeout) {
                    requestTimeoutMillis = config.healthCheckTimeoutMs
                }
            }
            
            client.webSocket(config.napcatWSUrl) {
                close()
            }
            
            client.close()
        }
        
        log.info { "✓ NapCat WebSocket is reachable" }
        true
    } catch (e: Exception) {
        log.error(e) { "✗ Failed to connect to NapCat at ${config.napcatWSUrl}" }
        log.error { "  Make sure NapCat is running and you've logged in via http://localhost:6099" }
        false
    }
}
