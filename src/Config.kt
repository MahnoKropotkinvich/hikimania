import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val napcatWSUrl: String,
    val napcatToken: String = "",
    val claudeAPIUrl: String,
    val claudeAPIKey: String,
    val claudeModel: String,
    val botQQ: Long,
    val rocksDBPath: String = "data/profiles",
    val profileUpdateEveryN: Int = 20,
    val maxHistoryPerUser: Int = 100,
    val healthCheckTimeoutMs: Long = 10_000,
)

val config: Config by lazy {
    System.getenv("CLAUDE_API_KEY")?.let { apiKey ->
        Config(
            napcatWSUrl   = System.getenv("NAPCAT_WS_URL")  ?: "ws://127.0.0.1:3001",
            napcatToken   = System.getenv("NAPCAT_TOKEN")   ?: "",
            claudeAPIUrl  = System.getenv("CLAUDE_API_URL")  ?: "https://api.anthropic.com",
            claudeAPIKey  = apiKey,
            claudeModel   = System.getenv("CLAUDE_MODEL")    ?: "claude-sonnet-4-6",
            botQQ         = System.getenv("BOT_QQ")?.toLongOrNull() ?: error("BOT_QQ env var required"),
            rocksDBPath   = System.getenv("ROCKS_DB_PATH")   ?: "data/profiles",
            healthCheckTimeoutMs = System.getenv("HEALTH_CHECK_TIMEOUT_MS")?.toLongOrNull() ?: 10_000,
        )
    } ?: error("Configuration required. Set CLAUDE_API_KEY and BOT_QQ environment variables.")
}
