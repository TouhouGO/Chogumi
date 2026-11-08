package io.github.touhougo.chogumi.core.data.db.mapper

private val htmlEntity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

/**
 * 服务端转义文本仅解码一层；保留 BBCode 为文本，不渲染任意 HTML。
 *
 * 昵称、签名、正文都会经过服务端 HTML 转义（`&#39;`、`&quot;` 等），
 * 直接上屏会露出实体本身。时间线与用户资料共用同一套解码，
 * 因此放在这里而不是埋在某个 mapper 内部。
 */
internal fun String.unescapeHtmlEntities() = htmlEntity.replace(this) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
        else -> {
            val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
    }
}
