package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog

/**
 * 一个表情该从哪里取图。**三处渲染点共用这一份判定**。
 *
 * 时间线正文、BBCode 正文、reaction 行都需要「内置优先、否则按需下载」，
 * 三处各写一遍必然分叉，故收在这里。
 */
sealed interface SmileySource {
    /** 内置资源：图片经 [BundledSmileyResources.resIdOf] 由 [code] 取得。 */
    data class Bundled(val code: String) : SmileySource

    /**
     * 需要按需下载。
     *
     * [remotePath] 是 Catalog 给的**相对路径**（如 `tv/15.gif`）。
     * 绝对 URL 由 `SmileyAssetStore` 从这一个点拼接并校验 ——
     * 本层不拼 URL。
     */
    data class OnDemand(val code: String, val remotePath: String) : SmileySource
}

/**
 * 判定某个 code 的取图路径；不可解析返回 null（调用方显示原文）。
 *
 * 判定顺序即优先级：
 * 1. 内置（12 个 reaction）—— 断网也要能显示核心交互
 * 2. 按需（其余六家族）—— 有 Catalog 给的路径就能取
 * 3. 不可解析 —— 越界或语法不合法，退回原文
 */
fun smileySourceOf(code: String): SmileySource? {
    if (BundledSmileyResources.resIdOf(code) != null) return SmileySource.Bundled(code)
    val entry = SmileyCatalog.entryOf(code) ?: return null
    return SmileySource.OnDemand(entry.code, entry.remotePath)
}

/**
 * 判定一个表情 code 是否可解析为有效资源。
 *
 * 全仓唯一的表情可解析判定纯函数（单一真源）。
 * 支持带括号 `(bgm38)` 与不带括号 `bgm38`。
 * 不可解析（越界、未知家族、普通括号）返回 null。
 */
fun resolvableSmileyCode(code: String): String? {
    val normalized = SmileyCatalog.detect(code) ?: return null
    return normalized.takeIf { SmileyCatalog.remoteUrl(it) != null }
}
