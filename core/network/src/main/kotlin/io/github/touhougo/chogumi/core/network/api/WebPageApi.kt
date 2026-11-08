package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.network.model.web.StatsChartSet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * bgm.tv 网页（不是接口）上才有的数据。网页没有契约：只认页面里内嵌的那段 JSON，认不出就按坏响应
 * （[Failure.BadResponse]）处理，由使用方把依赖它的内容整块收起。地址由调用方给（core:model 的 `BgmWeb`）。
 * 请求不带登录：网页认的是 cookie，不认 OAuth 令牌。
 */
class WebPageApi(
    private val client: HttpClient,
) {

    /**
     * 作品「透视」页（`BgmWeb.subjectStats`）里的全部图，键是网页给每张图起的名字（`interest_type`、`airdate`…）。
     *
     * 没缓存的页面由服务端现算，算好之前每次请求都在 4 s 左右回 502，算好之后是缓存的 200。小作品紧接着重发就好，
     * 收藏多的作品要连着 502 一分多钟（2026-10-13 实测：400602 第 80 s 才回 200）。所以服务端错误隔
     * [COLD_PAGE_RETRY_DELAY_MILLIS] 重发，最多试 [COLD_PAGE_ATTEMPTS] 次（约三分钟），还不行照常抛出。
     */
    suspend fun getSubjectStatsCharts(url: String): Map<String, StatsChartSet> {
        var attempt = 1
        while (true) {
            val html = try {
                fetchText(url)
            } catch (e: ServerResponseException) {
                if (attempt == COLD_PAGE_ATTEMPTS) throw e
                attempt++
                delay(COLD_PAGE_RETRY_DELAY_MILLIS)
                continue
            }
            return parseStatsCharts(html)
        }
    }

    private suspend fun fetchText(url: String): String = client.get(url) { pinSessionToken(null) }.bodyAsText()

    private companion object {
        const val COLD_PAGE_RETRY_DELAY_MILLIS = 5_000L
        const val COLD_PAGE_ATTEMPTS = 20
    }
}

/** 透视页里放图表数据的那个脚本变量。 */
private const val CHART_SETS_VARIABLE = "CHART_SETS"

/** 图里每一行的分数字段；同一行其余字段是各组的人数。 */
private const val SCORE_FIELD = "title"

private const val SCORE_LEVELS = 10

private val pageJson = Json { ignoreUnknownKeys = true }

/**
 * 从透视页 HTML 取出 `CHART_SETS = {…}` 的对象并拆成 [StatsChartSet]。
 *
 * 页面里每张图形如 `{"data": [{"title": 10, "1": 47, "2": 3024, …}, …], …}`：一行一个分数，
 * `title` 是分数，其余键是组编号、值是人数；某组在某分数没有人时网页直接省略那个键，按 0 计。
 * 1–10 以外的分数行忽略。
 */
internal fun parseStatsCharts(html: String): Map<String, StatsChartSet> {
    val json = assignedObjectOf(html, CHART_SETS_VARIABLE) ?: throw FailureException(Failure.BadResponse)
    val charts = pageJson.parseToJsonElement(json) as? JsonObject ?: throw FailureException(Failure.BadResponse)
    return charts.mapNotNull { (name, chart) ->
        val rows = ((chart as? JsonObject)?.get("data") as? JsonArray) ?: return@mapNotNull null
        name to StatsChartSet(countsOf(rows))
    }.toMap()
}

private fun countsOf(rows: JsonArray): Map<String, List<Int>> {
    val counts = mutableMapOf<String, IntArray>()
    rows.forEach { row ->
        val fields = row as? JsonObject ?: return@forEach
        val score = fields[SCORE_FIELD].intOrNull() ?: return@forEach
        if (score !in 1..SCORE_LEVELS) return@forEach
        for ((group, value) in fields) {
            if (group == SCORE_FIELD) continue
            val count = value.intOrNull() ?: continue
            counts.getOrPut(group) { IntArray(SCORE_LEVELS) }[score - 1] = count
        }
    }
    return counts.mapValues { (_, levels) -> levels.toList() }
}

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

/**
 * 脚本里 `<name> = {…}` 赋值的对象原文：从等号后第一个 `{` 起按括号配对数到与之配对的 `}`，
 * 字符串里的括号与转义引号不计。找不到赋值或对象没有闭合返回 null。
 */
private fun assignedObjectOf(text: String, name: String): String? {
    var from = 0
    while (true) {
        val at = text.indexOf(name, from)
        if (at < 0) return null
        from = at + name.length
        val equals = text.indexOfFirstNonBlank(from)
        if (equals < 0 || text[equals] != '=') continue
        val start = text.indexOfFirstNonBlank(equals + 1)
        if (start < 0 || text[start] != '{') continue
        return text.balancedObjectFrom(start)
    }
}

private fun String.indexOfFirstNonBlank(from: Int): Int {
    for (i in from until length) if (!this[i].isWhitespace()) return i
    return -1
}

private fun String.balancedObjectFrom(start: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until length) {
        val c = this[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return substring(start, i + 1)
            }
        }
    }
    return null
}
