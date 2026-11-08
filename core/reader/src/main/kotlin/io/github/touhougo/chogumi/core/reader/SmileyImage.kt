package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.common.bbcode.smileyTextOf
import io.github.touhougo.chogumi.core.model.SmileyFiles

/**
 * 按需表情的本地文件（app 壳提供数据层的实现，渲染层按需取用非内置表情）。
 *
 * 用 `CompositionLocal` 而不是让 Composable 直接 `koinInject`：渲染层不该
 * 知道 DI 的存在——能力由外层提供，渲染层只管用。
 *
 * 默认 null 表示"没有按需下载能力"（预览、单元测试、或未接线的环境），
 * 此时非内置表情退回显示原文，而不是崩溃。
 */
val LocalSmileyFiles = staticCompositionLocalOf<SmileyFiles?> { null }

/**
 * 表情两档渲染尺寸。**由 [SmileyCatalog.Scale] 决定**，不是排版偏好。
 *
 * 与官方 `bangumi.min.css` 对齐：
 * - 小表情（`.smile`）原生 20-21px；
 * - 大表情（`.smile-dynamic`）官方给 `max-width:55px`。
 *   原生素材 240px（musume/blake）在此档，
 *   由 `ContentScale.Fit` 等比缩放，不拉伸变形。
 *
 * 尺寸只有一条规则：行内按字号走 [emOf]（没有单发放大，表情在哪都一样大，与网页一致）。
 */
object SmileySizes {

    /** 行内表情左右各留的间隙（em）：占位宽 = 图宽 + 2 × 间隙，图片只画在自己的框里（方格，颜文字更宽），不被挤小。 */
    const val INLINE_GAP_EM = 0.12f

    /**
     * 行内嵌入时的高度（em）。`Placeholder` 只接受 em，
     * 这恰好让表情随阅读字号同比缩放，与文字的相对大小恒定。
     *
     * 小表情 1.5em：正文 14sp 下约 21dp，即原生 20-21px 的本来大小（官方 12px 字配 20px 表情，约 1.67em；
     * 再大会把行高撑开太多）。1.25em 只有 17.5dp，图片再被 20dp 尺寸 + 左右内边距挤进去，实际只剩约 13dp
     * （再小就看不清）。大表情 3.4em ≈ 48dp，与官方 `max-width:55px` 同档。
     */
    fun emOf(code: String): androidx.compose.ui.unit.TextUnit = when (SmileyCatalog.scaleOf(code)) {
        SmileyCatalog.Scale.LARGE -> 3.4f.em
        SmileyCatalog.Scale.SMALL, null -> 1.5f.em
    }

    /**
     * 行内嵌入时的垂直对齐。
     *
     * 真源：bgm.tv 官方对**大动态表情**设 `vertical-align:bottom`
     * （`img.smile-dynamic` 规则）——注意这是对齐**行**底而非文字底。
     * 必须映射到 [PlaceholderVerticalAlign.Bottom]：它会让该行行高被撑高，
     * 表情与文字底边齐平且**不溢出**。
     *
     * 不用 [PlaceholderVerticalAlign.TextBottom]（对齐文字底）：文字底不改变
     * 行高，3.4em 高的表情会从基线向上凸出、遮挡上一行。
     */
    fun verticalAlignOf(code: String): PlaceholderVerticalAlign = when (SmileyCatalog.scaleOf(code)) {
        SmileyCatalog.Scale.LARGE -> PlaceholderVerticalAlign.Bottom
        SmileyCatalog.Scale.SMALL, null -> PlaceholderVerticalAlign.TextCenter
    }

    /**
     * 行内包含大动态表情时的排版样式适配。
     *
     * 含有 LARGE 档位表情时，Compose 固定行高
     * 会把 3.4em 占位符挤出自身行、遮挡上一行文字。
     * 此时必须将 lineHeight 释放为 Unspecified，由占位符自然撑开行高。
     */
    fun inlineTextStyleOf(style: TextStyle, codes: Collection<String>): TextStyle {
        val hasLarge = codes.any { SmileyCatalog.scaleOf(it) == SmileyCatalog.Scale.LARGE }
        return if (hasLarge) style.copy(lineHeight = TextUnit.Unspecified) else style
    }
}

/**
 * 渲染一个表情：内置走 drawable，非内置按需下载，不可解析显示原文。
 *
 * **三处渲染点共用这一个**（时间线正文、BBCode 正文、reaction 行）。
 * 各写一遍必然分叉：同一件事写在两处，迟早只改了其中一处。
 *
 * ## 为什么非内置要异步取
 *
 * 首次遇到某表情时需要下载。**下载期间显示什么**是个取舍：
 * 这里选择先占位（保持布局不跳），而不是先显示原文再突然换成图
 * —— 后者会让整行文字跳动。
 *
 * ## 取不到时显示原文
 *
 * 下载失败、或环境未提供 [LocalSmileyFiles]（预览、测试）时，
 * 退回显示 `(bgm38)` 原文。让用户看到对方写了什么，比一片空白诚实；
 * 这也与 `BBCodeText` 对未知节点「原样显示」的既有原则一致。
 */
@Composable
fun SmileyImage(
    code: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    when (val source = remember(code) { smileySourceOf(code) }) {
        null -> SmileyFallbackText(code, modifier)
        is SmileySource.Bundled -> Image(
            painter = painterResource(BundledSmileyResources.resIdOf(source.code)!!),
            contentDescription = smileyLabelOf(source.code),
            modifier = modifier.smileyBox(source.code, size),
        )
        is SmileySource.OnDemand -> OnDemandSmiley(source, modifier, size)
    }
}

/**
 * 按需表情：先查本地，未命中则下载。
 *
 * 状态机只有三态（加载中 / 就绪 / 失败），失败后**不自动重试** ——
 * 断网时会变成死循环（进组合→失败→重组→再进组合），
 * 与 `loadMoreFooter` 的既有决策一致：由用户下次进入时自然重试。
 */
@Composable
private fun OnDemandSmiley(source: SmileySource.OnDemand, modifier: Modifier, size: Dp) {
    val store = LocalSmileyFiles.current
    if (store == null) {
        // 环境没有下载能力（预览/测试）：退回原文，不崩溃
        SmileyFallbackText(source.code, modifier)
        return
    }
    var file by remember(source.code) { mutableStateOf<java.io.File?>(null) }
    var failed by remember(source.code) { mutableStateOf(false) }

    LaunchedEffect(source.code) {
        if (failed) return@LaunchedEffect
        val url = SmileyCatalog.remoteUrl(source.code) ?: run { failed = true; return@LaunchedEffect }
        val result = store.ensure(url, source.remotePath)
        if (result != null) file = result else failed = true
    }

    val resolved = file
    when {
        resolved != null -> AsyncImage(
            model = resolved,
            contentDescription = smileyLabelOf(source.code),
            modifier = modifier.smileyBox(source.code, size),
            contentScale = ContentScale.Fit,
        )
        failed -> SmileyFallbackText(source.code, modifier)
        // 下载中：占位就是图的那个框，图到了尺寸不变（贴贴 chip、投票卡里也不跳）
        else -> Spacer(modifier.smileyBox(source.code, size))
    }
}

/** 表情画在高 [height]、宽按 [SmileyCatalog.widthRatioOf] 的框里（颜文字比方格宽），图在框里等比缩放。 */
private fun Modifier.smileyBox(code: String, height: Dp): Modifier =
    this.size(width = height * SmileyCatalog.widthRatioOf(code), height = height)

/** 读屏念的表情名：有中文名念名字（与网页图片的 `alt` 一致），否则念 code。 */
private fun smileyLabelOf(code: String): String = SmileyCatalog.nameOf(code) ?: code

/** 取不到图时的降级：显示 `(bgm38)` 原文。 */
@Composable
private fun SmileyFallbackText(code: String, modifier: Modifier) {
    Text(smileyTextOf(code), modifier, style = MaterialTheme.typography.bodyMedium)
}
