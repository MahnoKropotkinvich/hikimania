import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// x-algorithm equivalent: Phoenix Scorer — understands user preferences and scores/generates content

private val log = KotlinLogging.logger("LLM")

private const val MAX_RETRIES = 3
private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000)

private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

@Serializable
data class ClaudeMessage(val role: String, val content: String)

@Serializable
data class ClaudeRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val system: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 1024,
)

@Serializable
data class ContentBlock(val type: String, val text: String = "")

@Serializable
data class ClaudeResponse(val content: List<ContentBlock>)

suspend fun claudeAsk(system: String, userMessage: String): String =
    claudeChat(system, listOf(ClaudeMessage("user", userMessage)))

// Multi-turn conversation support with retry
suspend fun claudeChat(system: String, messages: List<ClaudeMessage>): String {
    repeat(MAX_RETRIES) { attempt ->
        try {
            val response = httpClient.post("${config.claudeAPIUrl}/v1/messages") {
                header("x-api-key", config.claudeAPIKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(ClaudeRequest(
                    model = config.claudeModel,
                    system = system,
                    messages = messages,
                ))
            }

            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.bodyAsText()
                log.warn { "API error (${response.status}), attempt ${attempt + 1}/$MAX_RETRIES: $errorBody" }
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAYS[attempt])
                    return@repeat
                }
                throw Exception("API returned ${response.status} after $MAX_RETRIES attempts")
            }

            return response.body<ClaudeResponse>().content.firstOrNull()?.text ?: ""
        } catch (e: Exception) {
            if (e.message?.contains("after $MAX_RETRIES attempts") == true) throw e
            log.warn { "API call failed, attempt ${attempt + 1}/$MAX_RETRIES: ${e.message}" }
            if (attempt < MAX_RETRIES - 1) {
                delay(RETRY_DELAYS[attempt])
            } else {
                throw e
            }
        }
    }
    throw Exception("API call failed after $MAX_RETRIES attempts") // unreachable
}

// Generate/update a user preference profile from their recent messages.
// x-algorithm equivalent: Phoenix retrieval — building user embedding from action sequence.
suspend fun generateUserProfile(record: UserRecord): String {
    val history = record.messages.takeLast(config.profileUpdateEveryN)
        .joinToString("\n") { "- $it" }
    val existing = record.profile.ifBlank { "（暂无）" }
    val mentionsText = record.mentions.takeLast(config.profileUpdateEveryN)
        .joinToString("\n") { "- $it" }
        .ifEmpty { "（暂无）" }

    return claudeAsk(
        system = """你是一个群聊观察者，负责分析群友的消息来理解他们的兴趣和性格。
请根据提供的最新消息和群友对该用户的评价，更新用户的兴趣画像。
输出格式：简短的自然语言描述，包括兴趣爱好、常用语气、话题偏好、群内形象等。不超过200字。""",
        userMessage = """用户昵称：${record.nickname}
当前画像：$existing
最新消息：
$history

群友对该用户的评价/回复：
$mentionsText

请输出更新后的画像："""
    )
}

// Generate a reply when the bot is @-mentioned.
// x-algorithm equivalent: Phoenix ranking — scoring and selecting best response for this user.
suspend fun generateReply(record: UserRecord, atMessage: String, groupContext: List<String>): String {
    val profile = record.profile.ifBlank { "暂无画像，请正常回复。" }
    val context = groupContext.takeLast(10)
        .joinToString("\n") { "- $it" }
        .ifEmpty { "（无近期群聊记录）" }

    return claudeAsk(
        system = """你是一个QQ群里的VTuber风格机器人，名叫Hikimania，是女性。
你了解每个群友的喜好，会根据对方的兴趣和性格来调整你的回复风格和内容。
保持活泼、有趣，偶尔用群友熟悉的话题或梗来拉近距离，性格有些腹黑。
不要过度使用emoji。回复要简洁，通常不超过100字。""",
        userMessage = """正在和你说话的群友画像：
$profile

近期群聊记录：
$context

群友对你说：$atMessage

请回复："""
    )
}

// Generate a reply for private/direct messages with conversation history.
suspend fun generatePrivateReply(messages: List<ClaudeMessage>): String = claudeChat(
    system = """你是一个VTuber风格的QQ机器人，名叫Hikimania，是女性。
现在有人私聊你。保持活泼、有趣，性格有些腹黑。
不要过度使用emoji。回复要简洁，通常不超过100字。""",
    messages = messages,
)
