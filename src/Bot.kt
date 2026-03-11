import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch

// x-algorithm equivalent: Home Mixer — orchestration layer

private val log = KotlinLogging.logger("Bot")

// CQ code parsers
private val atPattern = Regex("""\[CQ:at,qq=(\d+)]""")
private val replyPattern = Regex("""\[CQ:reply,id=(-?\d+)]""")

// Recent group messages for context window (in-memory, no need to persist)
private val groupContext = mutableMapOf<Long, BoundedList<String>>()
private const val CONTEXT_WINDOW = 20

// Private chat conversation history (multi-turn)
private val privateHistory = mutableMapOf<Long, BoundedList<ClaudeMessage>>()
private const val PRIVATE_HISTORY_SIZE = 20 // 10 turns of user+assistant

fun addToContext(groupId: Long, nickname: String, text: String) {
    groupContext.getOrPut(groupId) { BoundedList(CONTEXT_WINDOW) }.add("$nickname: $text")
}

fun getContext(groupId: Long): List<String> =
    groupContext[groupId]?.toList() ?: emptyList()

suspend fun runBot() {
    log.info { "Hikimania starting..." }
    runNapcat { event ->
        val userId = event.userId
        val nickname = event.sender.card.ifBlank { event.sender.nickname }
        val text = event.rawMessage.trim()

        if (text.isBlank()) return@runNapcat

        // Bot's own messages: only record in group context, skip everything else
        if (userId == config.botQQ) {
            if (event.messageType == "group") {
                addToContext(event.groupId, "Hikimania", text)
            }
            return@runNapcat
        }

        // --- Private chat: multi-turn conversation ---
        if (event.messageType == "private") {
            log.debug { "[PM] $nickname: $text" }
            launch {
                val history = privateHistory.getOrPut(userId) { BoundedList(PRIVATE_HISTORY_SIZE) }
                history.add(ClaudeMessage("user", text))

                log.info { "Generating private reply for $nickname ($userId)" }
                val reply = generatePrivateReply(history.toList())

                history.add(ClaudeMessage("assistant", reply))
                sendPrivateMsg(userId, reply)
            }
            return@runNapcat
        }

        if (event.messageType != "group") return@runNapcat

        val groupId = event.groupId

        log.debug { "[$groupId] $nickname: $text" }

        // Ingest message into profile actor
        addToContext(groupId, nickname, text)
        profileChannel.send(Ingest(groupId, userId, nickname, text))

        // Detect mentions: [CQ:at,qq=xxx] (excluding bot itself)
        atPattern.findAll(text)
            .map { it.groupValues[1].toLong() }
            .filter { it != config.botQQ && it != userId }
            .forEach { targetId ->
                profileChannel.send(Mention(groupId, targetId, nickname, text))
            }

        // Detect reply: [CQ:reply,id=xxx] — resolve target via API
        replyPattern.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { msgId ->
            launch {
                getMsgSenderId(msgId)
                    ?.takeIf { it != config.botQQ && it != userId }
                    ?.let { targetId ->
                        profileChannel.send(Mention(groupId, targetId, nickname, text))
                    }
            }
        }

        // Reply if bot is @-mentioned
        val atTag = "[CQ:at,qq=${config.botQQ}]"
        if (text.contains(atTag)) {
            val cleanText = text.replace(atTag, "").trim()
                .replace(replyPattern, "").trim()
            launch {
                log.info { "Generating reply for $nickname in group $groupId" }
                val record = getRecord(groupId, userId)
                    ?: UserRecord(userId, groupId, nickname)
                val reply = generateReply(record, cleanText, getContext(groupId))
                sendGroupMsg(groupId, "[CQ:at,qq=$userId] $reply")

                // Record bot's reply in context + as mention on the user (attitude memory)
                addToContext(groupId, "Hikimania", reply)
                profileChannel.send(Mention(groupId, userId, "Hikimania", reply))
            }
        }
    }
}
