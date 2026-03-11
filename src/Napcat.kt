import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// OneBot 11 WebSocket client — the "Thunder" ingestion layer for QQ messages

private val log = KotlinLogging.logger("Napcat")

@Serializable
data class OneBotSender(
    @SerialName("user_id") val userId: Long = 0,
    val nickname: String = "",
    val card: String = "",       // group card/alias, prefer over nickname if set
)

@Serializable
data class OneBotEvent(
    @SerialName("post_type") val postType: String = "",
    @SerialName("message_type") val messageType: String = "",
    @SerialName("sub_type") val subType: String = "",
    @SerialName("message_id") val messageId: Long = 0,
    @SerialName("group_id") val groupId: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("raw_message") val rawMessage: String = "",
    val sender: OneBotSender = OneBotSender(),
    // heartbeat / meta fields
    @SerialName("meta_event_type") val metaEventType: String = "",
)

private val wsClient = HttpClient(CIO) {
    install(WebSockets)
}

private var wsSendChannel: SendChannel<Frame>? = null
private val echoCounter = AtomicLong(0)
private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

// Rate limiter: sliding window, max N messages per minute
private const val RATE_LIMIT = 10
private const val RATE_WINDOW_MS = 60_000L
private val sendTimestamps = ArrayDeque<Long>()

private suspend fun rateLimitedSend(payload: String) {
    val ch = wsSendChannel ?: return
    val now = System.currentTimeMillis()

    // Evict timestamps outside the window
    while (sendTimestamps.isNotEmpty() && now - sendTimestamps.first() > RATE_WINDOW_MS) {
        sendTimestamps.removeFirst()
    }

    // If at limit, wait until the oldest timestamp expires
    if (sendTimestamps.size >= RATE_LIMIT) {
        val waitMs = RATE_WINDOW_MS - (now - sendTimestamps.first())
        if (waitMs > 0) {
            log.info { "Rate limit hit, waiting ${waitMs}ms" }
            delay(waitMs)
            // Re-evict after waiting
            val afterWait = System.currentTimeMillis()
            while (sendTimestamps.isNotEmpty() && afterWait - sendTimestamps.first() > RATE_WINDOW_MS) {
                sendTimestamps.removeFirst()
            }
        }
    }

    sendTimestamps.addLast(System.currentTimeMillis())
    ch.send(Frame.Text(payload))
}

suspend fun sendGroupMsg(groupId: Long, message: String) {
    val payload = buildJsonObject {
        put("action", "send_group_msg")
        put("params", buildJsonObject {
            put("group_id", groupId)
            put("message", message)
        })
    }
    rateLimitedSend(Json.encodeToString(payload))
}

suspend fun sendPrivateMsg(userId: Long, message: String) {
    val payload = buildJsonObject {
        put("action", "send_private_msg")
        put("params", buildJsonObject {
            put("user_id", userId)
            put("message", message)
        })
    }
    rateLimitedSend(Json.encodeToString(payload))
}

// Send an OneBot API action and wait for the response.
suspend fun callOneBotApi(action: String, params: JsonObject): JsonObject? {
    val ch = wsSendChannel ?: return null
    val echo = "req_${echoCounter.incrementAndGet()}"
    val deferred = CompletableDeferred<JsonObject>()
    pendingRequests[echo] = deferred
    val payload = buildJsonObject {
        put("action", action)
        put("params", params)
        put("echo", echo)
    }
    ch.send(Frame.Text(Json.encodeToString(payload)))
    return try {
        deferred.await()
    } finally {
        pendingRequests.remove(echo)
    }
}

// Get a message by its message_id. Returns the sender's user_id, or null on failure.
suspend fun getMsgSenderId(messageId: Long): Long? {
    val params = buildJsonObject { put("message_id", messageId) }
    val resp = callOneBotApi("get_msg", params) ?: return null
    return resp["data"]?.jsonObject?.get("sender")?.jsonObject?.get("user_id")?.jsonPrimitive?.longOrNull
}

// Connect and run the WebSocket loop with automatic reconnection.
// Never returns unless cancelled.
suspend fun runNapcat(onEvent: suspend CoroutineScope.(OneBotEvent) -> Unit) {
    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = if (config.napcatToken.isNotEmpty()) {
        "${config.napcatWSUrl}?access_token=${config.napcatToken}"
    } else {
        config.napcatWSUrl
    }

    while (true) {
        try {
            log.info { "Connecting to NapCat at ${config.napcatWSUrl}..." }
            wsClient.webSocket(url) {
                wsSendChannel = outgoing
                log.info { "Connected to NapCat WebSocket" }

                // Supervisor scope: child coroutine failures (e.g. API errors)
                // don't kill the WebSocket connection
                val eventScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + botExceptionHandler)

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val obj = jsonParser.parseToJsonElement(text).jsonObject

                    when {
                        obj.containsKey("echo") ->
                            obj["echo"]?.jsonPrimitive?.contentOrNull?.let {
                                pendingRequests.remove(it)?.complete(obj)
                            }
                        obj["post_type"]?.jsonPrimitive?.contentOrNull == "message" ->
                            eventScope.onEvent(jsonParser.decodeFromString<OneBotEvent>(text))
                    }
                }

                eventScope.coroutineContext[Job]?.cancelAndJoin()
            }
            // WebSocket closed normally
            log.warn { "WebSocket closed. Reconnecting in 5s..." }
        } catch (e: Exception) {
            log.error(e) { "WebSocket error. Reconnecting in 5s..." }
        }
        wsSendChannel = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        delay(5_000)
    }
}
