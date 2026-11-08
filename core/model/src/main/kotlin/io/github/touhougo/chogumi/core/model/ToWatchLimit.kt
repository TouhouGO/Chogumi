package io.github.touhougo.chogumi.core.model

/**
 * 首页「待看」显示条数的**可选值与默认值的唯一声明**。
 *
 * **设计决定（非 M3）**：为什么是这四档、为什么默认 8——首页不是进度页，
 * 待看只要够「马上点得到」即可，铺满一屏反而盖住下面的区块；8 张卡片约合两屏横滑，
 * 5/12/16 给偏紧与偏松的用户各留一档。
 *
 * 设置页的选项与读盘时的 coerce（磁盘值不在 [OPTIONS] 里 → [DEFAULT]）都从这里取，
 * 不得各写一份——否则改了档位而磁盘上的旧值没人认，会静默退回某个写死的数字。
 */
object ToWatchLimit {
    /** 可选档位，顺序即设置页选项顺序。 */
    val OPTIONS: List<Int> = listOf(5, 8, 12, 16)

    /** 默认档位。 */
    const val DEFAULT: Int = 8

    /** 把任意磁盘值收敛到 [OPTIONS] 中的一档；不在选项里（含缺省）一律 [DEFAULT]。 */
    fun coerceOr(value: Int?): Int = value?.takeIf { it in OPTIONS } ?: DEFAULT
}
