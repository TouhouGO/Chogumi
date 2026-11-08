package io.github.touhougo.chogumi.core.model

/**
 * 作品封面的图片质量（设置 →「封面画质」）。只管作品封面；角色、人物、头像不跟随。
 *
 * Bangumi 图床对每张封面原图另有按宽缩放的几档（100 / 200 / 400 / 800 像素宽）。档位 → 取哪一档的规则
 * 只在 core:ui 的 `coverUrlFor` 里写一次。
 */
enum class CoverQuality {
    /** 按封面画出来的宽取够用的最小一档：一张小海报只下 400 像素宽的图。 */
    AUTO,

    /** 至少取 800 像素宽的那一档；比 800 还宽的封面取原图。 */
    LARGE,

    /** 一律取上传的原图：最清晰，流量与解码开销也最大。 */
    ORIGINAL,
    ;

    companion object {
        val DEFAULT = AUTO
    }
}
