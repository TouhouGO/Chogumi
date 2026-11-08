package io.github.touhougo.chogumi.core.model

/**
 * 阅读字号档位（设置 →「阅读字号」）：帖子正文与楼层正文跟随它，卡片短正文、头部信息不变。
 *
 * 每一档对应 M3 字阶里的一档（CI 禁止写死字号，档位只能从字阶里取）。档位 → 字阶的映射只在
 * core:reader 的 `ReaderTypography` 里写一次。M3 的常规字重正文只有 14（bodyMedium）、16（bodyLarge），
 * 再往上是 22（titleLarge），所以只有三档。系统「字体大小」仍会叠加在上面。
 */
enum class ReadingTextSize {
    SMALL,
    STANDARD,
    LARGE,
    ;

    companion object {
        val DEFAULT = STANDARD
    }
}
