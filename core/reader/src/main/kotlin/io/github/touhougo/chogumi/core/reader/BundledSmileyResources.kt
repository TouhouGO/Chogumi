package io.github.touhougo.chogumi.core.reader

import androidx.annotation.DrawableRes
import io.github.touhougo.chogumi.core.res.R

/**
 * 内置进 APK 的表情：code → drawable，**哪些表情内置只在这里声明**。
 * 恰好是 12 个可选 reaction——点赞是核心交互，断网也要能显示；其余家族全部按需下载（`SmileyAssetStore`）。
 * 与可选集一致由 `BundledSmileyTest` 钉住。
 */
object BundledSmileyResources {

    /** 12 个内置 reaction 的 code → drawable。资源在 `core:res/res/drawable-nodpi/`。 */
    val map: Map<String, Int> = mapOf(
        "bgm67" to R.drawable.smiley_bgm67,
        "bgm63" to R.drawable.smiley_bgm63,
        "bgm38" to R.drawable.smiley_bgm38,
        "bgm124" to R.drawable.smiley_bgm124,
        "bgm46" to R.drawable.smiley_bgm46,
        "bgm106" to R.drawable.smiley_bgm106,
        "bgm88" to R.drawable.smiley_bgm88,
        "bgm64" to R.drawable.smiley_bgm64,
        "bgm125" to R.drawable.smiley_bgm125,
        "bgm72" to R.drawable.smiley_bgm72,
        "bgm69" to R.drawable.smiley_bgm69,
        "bgm74" to R.drawable.smiley_bgm74,
    )

    /** 非内置（或不可识别）返回 null，调用方据此转走按需下载路径。 */
    @DrawableRes
    fun resIdOf(code: String): Int? = map[code]
}
