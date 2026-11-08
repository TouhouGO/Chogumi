package io.github.touhougo.chogumi.core.common.bbcode

/**
 * BBCode `[img]` 的 URL 归一。
 *
 * ## 为什么必须归一而不是直接当 URL 用
 *
 * p1 上传的图片返回的是**相对路径**（`d6/c4/xxx.jpg`）。无 scheme 的字符串
 * 在很多加载器里"解析成功"但下载不到东西——媒体视图于是永远是空框，
 * 原始路径反而以文本形式泄漏到界面上（实测反馈 470676）。
 *
 * 前缀 `lain.bgm.tv/pic/photo/l/` 是实测可用的那个（2026-09-12 验证：
 * 该前缀返回真实 JPEG，`/photo/` 不带 `/pic` 不行）。
 */
object BangumiUrls {

    private const val PHOTO_PREFIX = "https://lain.bgm.tv/pic/photo/l/"
    /** 站内相对路径（官方 web 会产出 `/img/smiles/...`）补全成的协议与域。 */
    private const val SITE = "https://lain.bgm.tv"

    private val SITE_HOSTS = setOf("lain.bgm.tv", "bgm.tv", "www.bgm.tv", "bangumi.tv", "www.bangumi.tv")

    private fun upgradedSiteImage(raw: String): String? {
        val rest = raw.removePrefix("http://")
        val host = rest.substringBefore('/').substringBefore(':').lowercase()
        return if (host in SITE_HOSTS) "https://$rest" else null
    }

    /** 把 `[img]` 的值归一成可加载的绝对 URL；无法识别时返回 null。 */
    fun resolveImageUrl(raw: String): String? {
        if (raw.isEmpty()) return null
        // 协议相对（//lain.bgm.tv/...）补 https，与条目封面一致
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("https://")) return raw
        // 站内图片的老帖里还有 http://：App 不允许明文，站内域名改走 https，别的站点保持原样（加载会被拒）
        if (raw.startsWith("http://")) return upgradedSiteImage(raw) ?: raw
        // 站点相对路径（官方 web 会产出 /img/smiles/... 这类）
        if (raw.startsWith("/")) return SITE + raw
        // 其余按 bangumi 图片存储键处理（两级十六进制目录 + 文件名）
        return PHOTO_PREFIX + raw
    }
}

