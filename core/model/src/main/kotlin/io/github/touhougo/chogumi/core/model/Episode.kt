package io.github.touhougo.chogumi.core.model

/** 章节领域模型（type：0 本篇 1 SP 2 OP 3 ED） */
data class EpisodeBo(
    val id: Int,
    val subjectId: Int,
    val type: Int,
    override val name: String,
    override val nameCn: String,
    val sort: Double,
    val ep: Double? = null,
    val airdate: String = "",
    val comment: Int = 0,
    val desc: String = "",
    /** 碟号（音乐的曲目按碟分组）；0 表示未知或不分碟。 */
    val disc: Int = 0,
) : NamedEntity

/**
 * 一集的播出状态（[EpisodeTracking.airState]）。**全 App 唯一的「播没播」判定**。
 *
 * 「今天播出」是 [AIRED]——Bangumi 只有播出日期没有时刻，用户当天看完就要能标上。
 */
enum class AirState {
    /** 播出日不晚于今天：已播出，可标记。 */
    AIRED,

    /** 播出日严格晚于今天：未播出，不可标记。 */
    UNAIRED,

    /** 播出日缺失或不合法：无从判断。各调用方自行决定怎么处理（见 [EpisodeTracking.airState]）。 */
    UNKNOWN,
}

/**
 * 集类型（Bangumi `EpisodeType`）——**全项目唯一一份**，取值与接口逐一对齐。
 *
 * 依据 `tools/openapi/generated/p1.bundled.json` 的 `EpisodeType`：
 * `0 本篇 / 1 特别篇 / 2 OP / 3 ED / 4 预告、宣传、广告 / 5 MAD / 6 其他`。
 * 不做「未知值回落成 SP」那种兜底——接口没给的数字是脏数据，静默归到某一类
 * 会让它在「特别篇」列表里冒充正常数据；这里 [isKnown] 返回 false，由界面决定怎么显示。
 */
object EpisodeType {
    /** 本篇（正片）。 */
    const val NORMAL = 0

    /** 特别篇。 */
    const val SPECIAL = 1

    /** OP。 */
    const val OP = 2

    /** ED。 */
    const val ED = 3

    /** 预告／宣传／广告。 */
    const val PRE = 4

    /** MAD。 */
    const val MAD = 5

    /** 其他。 */
    const val OTHER = 6

    /** 全部合法取值，顺序即接口枚举顺序。 */
    val ALL: List<Int> = listOf(NORMAL, SPECIAL, OP, ED, PRE, MAD, OTHER)

    /** [type] 是否为正片。网格、进度、下一集都只认正片。 */
    fun isMain(type: Int): Boolean = type == NORMAL

    /**
     * 是否为接口已知的类型；不在 [ALL] 里（如 `99`）返回 false。
     *
     * 界面据此**只列出认识的集**——脏数据不冒充任何一类。
     */
    fun isKnown(type: Int): Boolean = type in ALL
}

/**
 * 集号显示格式——**全项目唯一一份**：整数显示成 `12`，小数显示成 `12.5`。
 *
 * 为什么不能直接 `sort.toInt()`：含分集的条目（如 12.5 话）会把集号截断成 12，
 * 用户点第 12.5 集却标到第 12 集。写入链路因此全程用 `Double`/`EpisodeBo`，
 * 只在**显示**这一步格式化。
 */
fun formatEpisodeSort(sort: Double): String =
    if (sort == sort.toLong().toDouble()) sort.toLong().toString() else sort.toString()
