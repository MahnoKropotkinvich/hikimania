import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rocksdb.Options
import org.rocksdb.RocksDB
import java.io.File

// Channel-based actor for user profile management.
// Single coroutine owns all mutable state — no locks, no ConcurrentHashMap.

private val log = KotlinLogging.logger("UserProfile")

// --- Persistence format (RocksDB) ---
@Serializable
data class UserRecord(
    val userId: Long,
    val groupId: Long,
    val nickname: String,
    val messages: List<String> = emptyList(),
    val mentions: List<String> = emptyList(),  // what others say about/to this user
    val profile: String = "",
)

// --- Messages the actor can receive ---
sealed interface ProfileMsg

data class Ingest(
    val groupId: Long,
    val userId: Long,
    val nickname: String,
    val text: String,
) : ProfileMsg

// Someone mentioned/replied to a user — store as external perception
data class Mention(
    val groupId: Long,
    val targetUserId: Long,
    val fromNickname: String,
    val text: String,
) : ProfileMsg

data class GetRecord(
    val groupId: Long,
    val userId: Long,
    val response: CompletableDeferred<UserRecord?>,
) : ProfileMsg

data class ProfileGenerated(
    val key: String,
    val nickname: String,
    val profile: String,
) : ProfileMsg

data object Flush : ProfileMsg

// --- The channel (public, used by Bot and main) ---
val profileChannel = Channel<ProfileMsg>(Channel.BUFFERED)

// --- Actor internals (all private, single-threaded access) ---
private val json = Json { ignoreUnknownKeys = true }

private val rocks: RocksDB by lazy {
    RocksDB.loadLibrary()
    val dir = File(config.rocksDBPath)
    dir.mkdirs()
    val opts = Options().setCreateIfMissing(true)
    RocksDB.open(opts, dir.absolutePath).also {
        log.info { "RocksDB opened at ${dir.absolutePath}" }
    }
}

private fun dbKey(groupId: Long, userId: Long) = "$groupId:$userId".toByteArray()
private fun cacheKey(groupId: Long, userId: Long) = "$groupId:$userId"

// Launch the profile actor. Call once from main.
fun CoroutineScope.launchProfileActor() {
    // State owned exclusively by this coroutine
    val cache = mutableMapOf<String, UserRecord>()
    val messageBuffers = mutableMapOf<String, BoundedList<String>>()
    val mentionBuffers = mutableMapOf<String, BoundedList<String>>()
    val pendingCounts = mutableMapOf<String, Int>()
    val dirtyKeys = mutableSetOf<String>()

    fun loadFromDb(groupId: Long, userId: Long): UserRecord? {
        val key = cacheKey(groupId, userId)
        cache[key]?.let { return it }
        val raw = rocks.get(dbKey(groupId, userId)) ?: return null
        val record = try {
            json.decodeFromString<UserRecord>(String(raw))
        } catch (e: Exception) {
            log.warn { "Corrupt record for $key, discarding: ${e.message}" }
            rocks.delete(dbKey(groupId, userId))
            return null
        }
        cache[key] = record
        messageBuffers[key] = BoundedList(config.maxHistoryPerUser, record.messages)
        mentionBuffers[key] = BoundedList(config.maxHistoryPerUser, record.mentions)
        return record
    }

    fun flushDirty() {
        if (dirtyKeys.isEmpty()) return
        val keys = dirtyKeys.toList()
        dirtyKeys.clear()
        var count = 0
        for (key in keys) {
            val record = cache[key] ?: continue
            val buf = messageBuffers[key]
            // Sync buffer state back to record for persistence
            val toSave = record.copy(
                messages = messageBuffers[key]?.toList() ?: record.messages,
                mentions = mentionBuffers[key]?.toList() ?: record.mentions,
            )
            val parts = key.split(":")
            rocks.put(dbKey(parts[0].toLong(), parts[1].toLong()), json.encodeToString(toSave).toByteArray())
            count++
        }
        if (count > 0) log.info { "Flushed $count records to RocksDB" }
    }

    // Main actor coroutine — SupervisorJob so child failures (e.g. profile generation)
    // don't kill the actor loop
    launch(Dispatchers.IO + SupervisorJob()) {
        log.info { "Profile actor started" }

        // Periodic flush ticker
        val flushJob = launch {
            while (isActive) {
                delay(300_000)
                profileChannel.send(Flush)
            }
        }

        for (msg in profileChannel) {
            when (msg) {
                is Ingest -> {
                    val key = cacheKey(msg.groupId, msg.userId)
                    loadFromDb(msg.groupId, msg.userId)

                    val existing = cache[key]
                        ?: UserRecord(msg.userId, msg.groupId, msg.nickname)

                    // Add message to bounded buffer
                    val buf = messageBuffers.getOrPut(key) {
                        BoundedList(config.maxHistoryPerUser, existing.messages)
                    }
                    buf.add(msg.text)

                    cache[key] = existing.copy(nickname = msg.nickname)
                    dirtyKeys.add(key)

                    // Profile update decision
                    val pending = (pendingCounts[key] ?: 0) + 1
                    if (pending >= config.profileUpdateEveryN) {
                        pendingCounts[key] = 0
                        val record = cache[key]!!.copy(
                            messages = buf.toList(),
                            mentions = mentionBuffers[key]?.toList() ?: emptyList(),
                        )
                        // Fire-and-forget: result comes back as ProfileGenerated
                        launch(botExceptionHandler) {
                            log.info { "Generating profile for ${record.nickname} (${record.userId})" }
                            val newProfile = generateUserProfile(record)
                            profileChannel.send(ProfileGenerated(key, record.nickname, newProfile))
                        }
                    } else {
                        pendingCounts[key] = pending
                    }
                }

                is ProfileGenerated -> {
                    cache[msg.key]?.let { record ->
                        cache[msg.key] = record.copy(profile = msg.profile)
                        dirtyKeys.add(msg.key)
                        log.info { "Profile updated for ${msg.nickname}: ${msg.profile.take(80)}..." }
                    }
                }

                is Mention -> {
                    val key = cacheKey(msg.groupId, msg.targetUserId)
                    loadFromDb(msg.groupId, msg.targetUserId)
                    // Ensure cache entry exists for this user
                    cache.getOrPut(key) { UserRecord(msg.targetUserId, msg.groupId, "") }
                    val buf = mentionBuffers.getOrPut(key) {
                        BoundedList(config.maxHistoryPerUser, cache[key]?.mentions ?: emptyList())
                    }
                    buf.add("${msg.fromNickname}: ${msg.text}")
                    dirtyKeys.add(key)
                }

                is GetRecord -> {
                    val record = loadFromDb(msg.groupId, msg.userId)
                    val key = cacheKey(msg.groupId, msg.userId)
                    val result = record?.let { r ->
                        r.copy(
                            messages = messageBuffers[key]?.toList() ?: r.messages,
                            mentions = mentionBuffers[key]?.toList() ?: r.mentions,
                        )
                    }
                    msg.response.complete(result)
                }

                is Flush -> flushDirty()
            }
        }

        flushJob.cancel()
    }
}

// Convenience: get a record from the actor (suspends until actor responds)
suspend fun getRecord(groupId: Long, userId: Long): UserRecord? {
    val deferred = CompletableDeferred<UserRecord?>()
    profileChannel.send(GetRecord(groupId, userId, deferred))
    return deferred.await()
}

// Synchronous flush for shutdown hook
fun flushAll() {
    // Send flush and block
    runBlocking {
        profileChannel.send(Flush)
        // Give the actor a moment to process
        delay(500)
    }
}
