package io.github.touhougo.chogumi.core.model

/**
 * 一个已发布的版本：版本名、versionCode（比新旧只看它，预发布与正式版共用一套递增的版本号），安装包的文件名、下载地址与
 * SHA-256（小写十六进制，下载完对得上才交给系统安装），发布时写的更新说明（[releaseNotesTextOf] 处理过的纯文本；没写或没取到
 * 为 null），以及它在 GitHub 上是不是标成预发布（[prerelease]：只有开了「接收预发布版」才会遇到）。
 */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val apkFileName: String,
    val apkUrl: String,
    val apkSha256: String,
    val notes: String? = null,
    val prerelease: Boolean = false,
)

/**
 * 要不要提示更新、提示哪一版的唯一判定：自动检查开着（[enabled]），并且最新一版（[latest]，没取到为 null）比装着的
 * （[installedVersionCode]）新、也比用户忽略过的（[ignoredVersionCode]，「忽略」记下的那一版，没有为 null）新。
 * 忽略的是那一版以及更早的：之后再发的新版照常提示。
 */
fun updateOfferOf(enabled: Boolean, latest: AppRelease?, installedVersionCode: Int, ignoredVersionCode: Int?): AppRelease? =
    latest?.takeIf { enabled && it.versionCode > maxOf(installedVersionCode, ignoredVersionCode ?: 0) }

/** 自动检查更新的间隔：每次打开（冷启动）、每天、每周。存的是名字，认不出时回落 [DEFAULT]。 */
enum class UpdateCheckInterval(val millis: Long) {
    EVERY_LAUNCH(0L),
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
    ;

    companion object {
        val DEFAULT = DAILY
    }
}

/**
 * 这一刻该不该去查更新的唯一判定。时机只有两种：冷启动（[launch] 为 true）与 App 回到前台。每次打开只在冷启动时查；
 * 每天 / 每周在任何一个时机都查，只要离上次查到结果（[lastCheckedAt]，没有为 null）满了那么久。[now] 早于
 * [lastCheckedAt]（改过系统时间）也当作到点了，不让一次改钟把检查推迟到很久以后。
 */
fun updateCheckDue(interval: UpdateCheckInterval, launch: Boolean, lastCheckedAt: Long?, now: Long): Boolean = when (interval) {
    UpdateCheckInterval.EVERY_LAUNCH -> launch
    else -> lastCheckedAt == null || now < lastCheckedAt || now - lastCheckedAt >= interval.millis
}

/**
 * Release 说明（GitHub 上发布时写的 Markdown）→ 提示框里的纯文本：去掉标题、引用的标记与加粗记号，列表项一律以「- 」开头，
 * 连续空行并成一行。没写说明（空白）为 null，提示框就不画这一块。不是完整的 Markdown 渲染：说明是给人看的几行字。
 */
fun releaseNotesTextOf(markdown: String?): String? {
    if (markdown.isNullOrBlank()) return null
    return markdown.lineSequence()
        .map { line ->
            line.trimEnd()
                .replace(MARKDOWN_PREFIX, "")
                .replace(MARKDOWN_BULLET, "- ")
                .replace("**", "")
                .replace("__", "")
        }
        .joinToString("\n")
        .replace(BLANK_RUN, "\n\n")
        .trim()
        .ifEmpty { null }
}

/** 行首的标题（`#`）与引用（`>`）标记，可以叠着写。 */
private val MARKDOWN_PREFIX = Regex("^(\\s*(#{1,6}|>)\\s*)+")

/** 行首的 `*` / `+` 列表标记（`-` 原样留着）。 */
private val MARKDOWN_BULLET = Regex("^\\s*[*+]\\s+")

private val BLANK_RUN = Regex("\n{3,}")
