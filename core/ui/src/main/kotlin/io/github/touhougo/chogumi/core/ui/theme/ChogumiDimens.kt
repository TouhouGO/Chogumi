package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 多个页面或组件共用的尺寸 token。只属于一个组件的尺寸留在组件旁；
 * 圆角取 `MaterialTheme.shapes`，海拔取 M3 级别，都不在这里另存。
 */
object ChogumiDimens {
    /** 页面左右边距 */
    val ScreenPadding = 16.dp

    /** 探索货架横排里条目海报卡的宽度（网格里的海报卡铺满一格，不用它）。 */
    val CoverWidth = 108.dp

    /** 作品封面宽高比（3:4 竖版） */
    const val CoverRatio = 0.75f

    /** 列表行里作品小缩略图的宽度（角色人物页的作品行、目录的条目行；设计决定，not in M3）。 */
    val ListThumbWidth = 48.dp

    /** 最小触控目标 */
    val MinTouchTarget = 48.dp

    /** chip / 页签之间 */
    val ChipSpacing = 8.dp

    // ─────────────────────────────────────────────────────────────
    // EP 按钮
    // 数值来源：设计原型里 EP 按钮的规则。
    // 圆角一律取 M3 形状刻度上的值（tokens.md），不另造中间档。
    // ─────────────────────────────────────────────────────────────

    // EP 按钮的高度、图标边长与图标文字间距直接取官方 ButtonDefaults（= M3 ButtonSmall：
    // 高 40dp、图标 20dp、间距 8dp），触控区由官方 Button 补到 48dp，这里不另存副本。

    /**
     * EP 按钮带字形态（「+ 第 12 集」「3 天后」「标为看过」）的宽度下限，短文案（「明天」）不至于缩成一小块。
     * **设计决定（非 M3）**：沿用原型 `.epbtn` 的 `min-width:88px`。推进一集的文字里数字按三位占位，
     * 由按钮的标签格按当前字号实测，见 [io.github.touhougo.chogumi.core.ui.composables.EpButton]。
     * 只画「+」的形态是 M3 宽款图标按钮，不用这个下限。
     */
    val EpButtonMinWidth = 88.dp

    /** EP 按钮左右内边距：取 M3 ButtonSmall 的 LeadingSpace / TrailingSpace（component-tokens.md）。 */
    val EpButtonHorizontalPadding = 16.dp

    /** 待提交倒计时细条的高度（EP 按钮、海报的待提交胶囊）：画在容器底边、被容器的形状裁切。**设计决定（非 M3）**：沿用原型 `.epbtn .drain` 的 3px。 */
    val CommitBarHeight = 3.dp
}
