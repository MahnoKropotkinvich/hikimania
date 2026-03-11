import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup

// Web tools for LLM agent — search and fetch, read-only.

private val log = KotlinLogging.logger("WebSearch")

private val webClient = HttpClient(CIO) {
    engine {
        requestTimeout = 15_000
    }
    followRedirects = true
}

private const val MAX_CONTENT_LENGTH = 2000 // chars returned to LLM

// Tool definitions for Claude tool use
val webTools = listOf(
    ToolDef(
        name = "web_search",
        description = "Search the web using DuckDuckGo. Returns top results with titles, URLs, and snippets.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        },
    ),
    ToolDef(
        name = "web_fetch",
        description = "Fetch a web page and extract its main text content. Returns plain text, truncated to ${MAX_CONTENT_LENGTH} chars.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to fetch")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
        },
    ),
)

// Execute a web tool by name
suspend fun executeWebTool(name: String, input: JsonObject): String = when (name) {
    "web_search" -> webSearch(input["query"]?.toString()?.trim('"') ?: "")
    "web_fetch" -> webFetch(input["url"]?.toString()?.trim('"') ?: "")
    else -> "Unknown tool: $name"
}

// DuckDuckGo HTML search — no API key needed
private suspend fun webSearch(query: String): String {
    if (query.isBlank()) return "Error: empty query"
    log.info { "Web search: $query" }

    val html = webClient.get("https://html.duckduckgo.com/html/") {
        parameter("q", query)
        header("User-Agent", "Mozilla/5.0 (compatible; Hikimania/1.0)")
    }.bodyAsText()

    val doc = Jsoup.parse(html)
    val results = doc.select(".result__body").take(5).mapIndexed { i, el ->
        val title = el.select(".result__a").text()
        val snippet = el.select(".result__snippet").text()
        val url = el.select(".result__a").attr("href")
        "${i + 1}. $title\n   $url\n   $snippet"
    }

    return if (results.isEmpty()) {
        "No results found for: $query"
    } else {
        results.joinToString("\n\n").also {
            log.info { "Search returned ${results.size} results" }
        }
    }
}

// Fetch a URL and extract main text content
private suspend fun webFetch(url: String): String {
    if (url.isBlank()) return "Error: empty URL"
    log.info { "Web fetch: $url" }

    val html = webClient.get(url) {
        header("User-Agent", "Mozilla/5.0 (compatible; Hikimania/1.0)")
    }.bodyAsText()

    val doc = Jsoup.parse(html)
    // Remove noise
    doc.select("script, style, nav, header, footer, iframe, noscript").remove()

    // Try article/main content first, fall back to body
    val content = doc.select("article, main, .content, .post, #content").text()
        .ifBlank { doc.body()?.text() ?: "" }

    return content.take(MAX_CONTENT_LENGTH).also {
        log.info { "Fetched ${it.length} chars from $url" }
    }
}
