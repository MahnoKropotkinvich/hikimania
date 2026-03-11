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
import kotlinx.serialization.json.*

// x-algorithm equivalent: Phoenix Scorer — understands user preferences and scores/generates content

private val log = KotlinLogging.logger("LLM")

private const val MAX_RETRIES = 3
private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000)
private const val MAX_TOOL_ROUNDS = 3

private val llmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(llmJson)
    }
}

// --- Simple message (for multi-turn chat, profile generation, etc.) ---
@Serializable
data class ClaudeMessage(val role: String, val content: String)

// --- Tool definitions ---
@Serializable
data class ToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

// --- Rich content blocks (for tool use flow) ---
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,          // tool_use block
    val name: String? = null,        // tool_use block
    val input: JsonObject? = null,   // tool_use block
    @SerialName("tool_use_id") val toolUseId: String? = null,  // tool_result block
    val content: String? = null,     // tool_result block (reuses 'content' but as result text)
)

// --- Request/Response for simple chat ---
@Serializable
data class ClaudeRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val system: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 1024,
)

// --- Request for tool use chat (messages as raw JsonArray) ---
@Serializable
data class ToolChatRequest(
    val model: String,
    val messages: JsonArray,
    val system: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val tools: List<ToolDef>? = null,
)

@Serializable
data class ClaudeResponse(
    val content: List<ContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
)

// --- Simple chat (no tools) ---

suspend fun claudeAsk(system: String, userMessage: String): String =
    claudeChat(system, listOf(ClaudeMessage("user", userMessage)))

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
            log.warn { "API call failed, attempt ${attempt + 1}/$MAX_RETRIES: ${e::class.simpleName}: ${e.message}" }
            if (attempt < MAX_RETRIES - 1) {
                delay(RETRY_DELAYS[attempt])
            } else {
                throw e
            }
        }
    }
    throw Exception("API call failed after $MAX_RETRIES attempts")
}

// --- Tool use chat: multi-round loop ---
// Returns the final text reply. Thinking tags are stripped by caller.

suspend fun claudeToolChat(
    system: String,
    initialUserMessage: String,
    tools: List<ToolDef>,
    toolExecutor: suspend (name: String, input: JsonObject) -> String,
): String {
    val messages = mutableListOf<JsonElement>(
        buildJsonObject {
            put("role", "user")
            put("content", initialUserMessage)
        }
    )

    repeat(MAX_TOOL_ROUNDS) { round ->
        val resp = callWithRetry(system, JsonArray(messages), tools)
        val textParts = resp.content.filter { it.type == "text" }.mapNotNull { it.text }
        val toolUses = resp.content.filter { it.type == "tool_use" }

        if (resp.stopReason != "tool_use" || toolUses.isEmpty()) {
            return textParts.joinToString("\n")
        }

        // Add assistant response with content blocks
        messages.add(buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonArray {
                resp.content.forEach { block ->
                    add(contentBlockToJson(block))
                }
            })
        })

        // Execute tools and add results
        val toolResults = buildJsonArray {
            toolUses.forEach { tool ->
                val name = tool.name ?: "unknown"
                val input = tool.input ?: buildJsonObject {}
                log.info { "Tool call [round ${round + 1}]: $name($input)" }
                val result = try {
                    toolExecutor(name, input)
                } catch (e: Exception) {
                    log.warn { "Tool $name failed: ${e::class.simpleName}: ${e.message}" }
                    "Error: ${e.message}"
                }
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", tool.id ?: "")
                    put("content", result)
                })
            }
        }

        messages.add(buildJsonObject {
            put("role", "user")
            put("content", toolResults)
        })
    }

    // Exhausted tool rounds — final call without tools
    val finalResp = callWithRetry(system, JsonArray(messages), tools = null)
    return finalResp.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
}

private fun contentBlockToJson(block: ContentBlock): JsonObject = buildJsonObject {
    put("type", block.type)
    when (block.type) {
        "text" -> put("text", block.text ?: "")
        "tool_use" -> {
            put("id", block.id ?: "")
            put("name", block.name ?: "")
            put("input", block.input ?: buildJsonObject {})
        }
    }
}

private suspend fun callWithRetry(
    system: String,
    messages: JsonArray,
    tools: List<ToolDef>?,
): ClaudeResponse {
    repeat(MAX_RETRIES) { attempt ->
        try {
            val response = httpClient.post("${config.claudeAPIUrl}/v1/messages") {
                header("x-api-key", config.claudeAPIKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(ToolChatRequest(
                    model = config.claudeModel,
                    system = system,
                    messages = messages,
                    tools = tools,
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

            return response.body<ClaudeResponse>()
        } catch (e: Exception) {
            if (e.message?.contains("after $MAX_RETRIES attempts") == true) throw e
            log.warn { "API call failed, attempt ${attempt + 1}/$MAX_RETRIES: ${e::class.simpleName}: ${e.message}" }
            if (attempt < MAX_RETRIES - 1) {
                delay(RETRY_DELAYS[attempt])
            } else {
                throw e
            }
        }
    }
    throw Exception("API call failed after $MAX_RETRIES attempts")
}

// --- Thinking tag stripper ---
private val thinkingPattern = Regex("""<thinking>[\s\S]*?</thinking>\s*""")

fun stripThinking(text: String): String = text.replace(thinkingPattern, "").trim()

// --- Business functions ---

suspend fun generateReply(record: UserRecord, message: String, groupContext: List<String>): String {
    val profile = record.profile.ifBlank { "暂无画像，请正常回复。" }
    val context = groupContext.takeLast(10)
        .joinToString("\n") { "- $it" }
        .ifEmpty { "（无近期群聊记录）" }

    val system = """你是一个VTuber风格的QQ群聊机器人，名叫Hikimania，是女性。

规则：
1. 你可以先在<thinking></thinking>标签内思考（不超过300字），思考内容不会被用户看到。
2. 如果需要查询信息，在思考阶段使用提供的工具。
3. <thinking>标签之外的内容就是你的最终回复，必须是中文。
4. 最终回复要简洁有趣，不超过150字。不要过度使用emoji。性格有些腹黑。
5. 绝对不要输出英文、不要解释你的思考过程、不要道歉。
6. 直接回复，不要加任何前缀或元描述。

当前用户画像：$profile

近期群聊记录：
$context"""

    val userMsg = """用户昵称：${record.nickname}
用户说：$message

请回复："""

    val raw = claudeToolChat(system, userMsg, webTools, ::executeWebTool)
    val reply = stripThinking(raw)
    // Fallback: if tool loop returned empty (proxy doesn't support tools, etc.)
    if (reply.isBlank()) {
        log.warn { "Tool chat returned empty, falling back to simple chat" }
        return stripThinking(claudeAsk(system, userMsg))
    }
    return reply
}

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

suspend fun generatePrivateReply(messages: List<ClaudeMessage>): String = claudeChat(
    system = """你是一个VTuber风格的QQ机器人，名叫Hikimania，是女性。
现在有人私聊你。保持活泼、有趣，性格有些腹黑。
不要过度使用emoji。回复要简洁，通常不超过100字。""",
    messages = messages,
)
