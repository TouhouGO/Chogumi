package io.github.touhougo.chogumi.core.common.bbcode

/**
 * Bangumi 表情的**全部映射语义**，唯一真源。
 *
 * ## 家族与公式（全部经线上实测，不是推断）
 *
 * 证据：`https://bgm.tv/group/topic/1` 的真实 HTML（`smileid` 与
 * `alt="(bgmNN)"` 的对应），加对 `https://lain.bgm.tv/img/smiles/` 的逐号探测。
 *
 * | 家族 | code 范围 | 路径 | 实测 |
 * |---|---|---|---|
 * | 经典 | `bgm01..23` | `bgm/{NN}.png`，**11/23 例外是 .gif** | 01..23 全部存在 |
 * | 电视 | `bgm24..125` | `tv/{code-23}.gif` | 文件 01..102 连续，103+ 不存在 |
 * | tv_vs | `bgm200..238` | `tv_vs/bgm_{code}.png` | 全 png，39 个 |
 * | tv_500 | `bgm500..529` | `tv_500/bgm_{code}.{ext}` | **ext 不统一**，见 [TV500_GIF] |
 * | musume | `musume_NN` | `musume/musume_{NN}.gif` | 01..118，**缺 97/98** |
 * | blake | `blake_NN` | `blake/blake_{NN}.gif` | 01..118，无缺号 |
 * | 颜文字 | `=A=` 等 16 个（[KAOMOJI]） | `{n}.gif`（smiles 根目录） | 1..16 |
 *
 * 颜文字没有 code 前缀：正文里就是字面的 `(=w=)`，网页把它画成 `smiles/2.gif`（实测 `bgm.tv/group/topic/87`
 * 的回复 443850，原文 `(=w=)`，HTML `<img src="/img/smiles/2.gif" smileid="2" alt="=w=">`）。
 * 它们区分大小写（`=w=` 是 2，`=W=` 是 7），所以按原文精确匹配，不做小写归一。
 *
 * 编辑器表情面板的分组、顺序与 musume / blake 的中文名照 next.bgm.tv 的表情面板（`docs/design/smiley-picker-groups.md`）。
 *
 * ## code 与文件名不是一回事
 *
 * 这是最容易搞错的地方：电视家族的 `code` 是 **24..125**，而文件名是
 * **01..102**（`code-23`）。经典家族则 code 与文件名同号。两者规则不同，
 * 不能用一个公式套。
 */
object SmileyCatalog {

    // ---------------------------------------------------------------------
    // 家族定义
    // ---------------------------------------------------------------------

    /** 表情家族，按 next.bgm.tv 表情面板的页签顺序排（编辑器的表情面板照这个顺序列出）。 */
    enum class Family { TV, TV_VS, TV_500, CLASSIC, MUSUME, BLAKE, KAOMOJI }

    /**
     * 表情的渲染档位。**由官方页面给的 CSS 类决定**，不是排版偏好，也不是素材像素。
     *
     * 真源是 bgm.tv 线上 HTML 的 img class + 官方 `bangumi.min.css`：
     * - [SMALL] 走官方 `.smile`：`image-rendering:pixelated`，显示 20-21px（bgm124/125 素材 42px 也在此档）；
     * - [LARGE] 走官方 `.smile-dynamic` / `.smile-musume` / `.smile-blake`：
     *   `image-rendering:auto` 且 `max-width:55px`，原生 240px。
     *
     * 一刀切尺寸会同时毁掉两头：小表情被拉糊（失去像素硬边），
     * 大动态 GIF 被压成一个点（动态效果完全看不见）。
     */
    enum class Scale { SMALL, LARGE }

    /**
     * 一条表情的全部解析结果。
     *
     * @param code 标准 code，如 `bgm38`
     * @param family 所属家族
     * @param remotePath 相对路径，如 `tv/15.gif`。**绝对 URL 由调用方从这一处拼接**，
     *   不得在别处另拼
     */
    data class SmileyEntry(
        val code: String,
        val family: Family,
        val remotePath: String,
    )

    /** 电视家族 code 下界。`bgm24` -> `tv/01.gif`。 */
    private const val TV_CODE_MIN = 24

    /** 电视家族 code 上界。`bgm125` -> `tv/102.gif`。 */
    private const val TV_CODE_MAX = 125

    /** code 与文件名的偏移：`bgm38` -> `tv/15.gif`。 */
    private const val TV_FILE_OFFSET = 23

    private const val CLASSIC_CODE_MIN = 1
    private const val CLASSIC_CODE_MAX = 23

    private const val TV_VS_CODE_MIN = 200
    private const val TV_VS_CODE_MAX = 238

    private const val TV_500_CODE_MIN = 500
    private const val TV_500_CODE_MAX = 529

    /**
     * tv_500 家族里扩展名是 `.gif` 的 code；其余为 `.png`。
     *
     * **实测表**：500/501/505/515-519/521-523 共 11 个是 gif，当成 png 会 404。
     */
    private val TV500_GIF = setOf(500, 501, 505, 515, 516, 517, 518, 519, 521, 522, 523)

    /**
     * 经典家族里扩展名是 `.gif` 的 code；其余为 `.png`。
     * 实测：`bgm/11.gif` 与 `bgm/23.gif` 存在，同号的 `.png` 不存在。
     */
    private val CLASSIC_GIF = setOf(11, 23)

    /**
     * musume 家族里**缺失**的编号。
     *
     * 实测：`musume_97`、`musume_98` 在 CDN 上不存在（同段其余都 200）。
     * 范围判断必须带上这个缺口 —— 只用 `1..118` 会拼出 404 的地址。
     */
    private val MUSUME_MISSING = setOf(97, 98)

    /** musume、blake 两个家族的最大编号（实测 01..118）。 */
    private const val LARGE_FAMILY_MAX = 118

    /**
     * musume、blake 在面板里的顺序（编号）：按含义排，不按编号排。blake 多出的 [MUSUME_MISSING] 两个
     * 排在 117「垃圾桶(说教)」之后、77「钱」之前，musume 没有这两个，过滤掉即可。
     */
    private val LARGE_FAMILY_ORDER: List<Int> =
        (6..42) + listOf(100, 106, 108, 118) +
            (43..76) + listOf(101, 102, 103, 99, 107, 112, 109, 110, 111, 113, 114, 115, 116, 117) +
            MUSUME_MISSING.sorted() +
            (77..93) + listOf(104, 105, 94, 95, 96) +
            (1..5)

    /** musume、blake 的中文名，两个家族同号同名；下标 = 编号 - 1。 */
    private val LARGE_FAMILY_NAMES: List<String> = listOf(
        "Bits", "硬币", "喜欢", "点赞", "收藏", "叹号", "问号", "点头", "摇头", "紧张 1",
        "紧张 2", "惊吓", "汗", "生气", "庆祝", "自我安慰", "哭 1", "哭 2", "哭 3", "死亡",
        "头晕", "呆 1", "呆 2", "呆 3", "呆(贴纸) 1", "呆(贴纸) 2", "呆(贴纸) 3", "催眠", "笑", "笑(指)",
        "害羞 1", "害羞 2", "睡觉(普通)", "睡觉(准备阶段1)", "睡觉(准备阶段2)", "睡觉 (UU)", "闲置", "闲置 2", "说话", "说话 2",
        "期待", "唱歌", "到达", "到达(拿筷子)", "到达(拿勺子)", "摇铃", "敲头", "蛋糕", "抓拍(手机)", "抓拍(摄像机)",
        "驾驶", "喝(饮料杯)", "馋(筷子)", "馋(刀叉)", "撬棍 1", "撬棍 2", "撬棍 3", "坐牢 1", "坐牢 2", "刀 1",
        "刀 2", "荧光棒 1", "荧光棒 2", "冒泡 1", "冒泡 2", "爱心 1", "爱心 2", "爱心 3", "静音 1", "静音 2",
        "记录 1", "记录 2", "红包 1", "红包 2", "玫瑰", "情书", "钱", "要米", "摸头", "说教",
        "指", "吃", "画板", "反向点赞", "点赞", "行", "打字(普通)", "打字(生气)", "打字(恼怒)", "工作(普通)",
        "工作(生气)", "工作(小睡)", "工作(疲倦)", "Raid 1", "Raid 2", "舔舔", "得分(0分)", "得分(10分)", "Bug", "加油",
        "复活节", "枪", "扩音器", "带薪拉屎(简单模式)", "带薪拉屎(困难模式)", "加载", "喷剂", "停止工作", "墨镜", "像素墨镜",
        "墨镜拿下来", "拍蝇", "胶带", "垃圾桶", "垃圾桶(闲置)", "垃圾桶(说话)", "垃圾桶(说教)", "主意",
    )

    /** 一个颜文字：[text] 是括号里的原文，[width] 是素材的像素宽（next.bgm.tv 面板的数据）。 */
    private class Kaomoji(val text: String, val width: Int)

    /** 16 个颜文字，第 n 个（从 1 起）的图是 `smiles/{n}.gif`。 */
    private val KAOMOJI: List<Kaomoji> = listOf(
        Kaomoji("=A=", 23), Kaomoji("=w=", 23), Kaomoji("-w=", 23), Kaomoji("S_S", 29),
        Kaomoji("=v=", 25), Kaomoji("@_@", 27), Kaomoji("=W=", 27), Kaomoji("TAT", 33),
        Kaomoji("T_T", 23), Kaomoji("='=", 26), Kaomoji("=3=", 25), Kaomoji("= ='", 24),
        Kaomoji("=///=", 21), Kaomoji("=.,=", 20), Kaomoji(":P", 28), Kaomoji("LOL", 27),
    )

    /** 颜文字原文 → 文件编号（1..16）。 */
    private val KAOMOJI_NUMBER: Map<String, Int> = KAOMOJI.withIndex().associate { (i, k) -> k.text to i + 1 }

    /** 小表情的标准素材宽（px）：颜文字的占位宽按它换算，见 [widthRatioOf]。 */
    private const val SMALL_SMILEY_PX = 21

    /**
     * 一个家族的全部表情 code，按面板顺序排（编辑器的表情面板逐家族列出）。范围与缺号同 [entryOf]，
     * 写法与网页插入的一致：经典、musume、blake 两位补零（`bgm01`、`musume_01`），颜文字是括号里的原文，其余是数字本身（`bgm38`）。
     */
    fun codesOf(family: Family): List<String> = when (family) {
        Family.CLASSIC -> (CLASSIC_CODE_MIN..CLASSIC_CODE_MAX).map { "bgm${pad2(it)}" }
        Family.TV -> (TV_CODE_MIN..TV_CODE_MAX).map { "bgm$it" }
        Family.TV_VS -> (TV_VS_CODE_MIN..TV_VS_CODE_MAX).map { "bgm$it" }
        Family.TV_500 -> (TV_500_CODE_MIN..TV_500_CODE_MAX).map { "bgm$it" }
        Family.MUSUME -> LARGE_FAMILY_ORDER.filterNot { it in MUSUME_MISSING }.map { "musume_${pad2(it)}" }
        Family.BLAKE -> LARGE_FAMILY_ORDER.map { "blake_${pad2(it)}" }
        Family.KAOMOJI -> KAOMOJI.map { it.text }
    }

    /** 表情的中文名（musume、blake 有，与网页图片的 `alt` 一致）；没有名字或不可解析返回 null。 */
    fun nameOf(code: String): String? {
        val entry = entryOf(code) ?: return null
        return when (entry.family) {
            Family.MUSUME, Family.BLAKE -> LARGE_FAMILY_NAMES[entry.code.substringAfter('_').toInt() - 1]
            Family.CLASSIC, Family.TV, Family.TV_VS, Family.TV_500, Family.KAOMOJI -> null
        }
    }

    /**
     * 表情占位的宽高比（宽 / 高）。颜文字的素材宽窄不一（20–33px），按官方像素宽对标准小表情的 21px 换算，
     * 至少是方格；其余都画在方格里（1）。不可解析返回 1。
     */
    fun widthRatioOf(code: String): Float {
        val number = detectBare(code)?.let { KAOMOJI_NUMBER[it] } ?: return 1f
        return maxOf(1f, KAOMOJI[number - 1].width / SMALL_SMILEY_PX.toFloat())
    }

    // ---------------------------------------------------------------------
    // reaction value -> code
    // ---------------------------------------------------------------------

    /** reaction value -> code；未知 value 返回 null。 */
    fun codeOfReaction(value: Int): String? = REACTION_VALUE_TO_CODE[value]

    /** 表情写法（带不带括号都行）-> reaction value；不是贴贴用的表情返回 null。与 [codeOfReaction] 同一张表。 */
    fun reactionOfCode(code: String): Int? = detect(code)?.let { REACTION_CODE_TO_VALUE[it] }

    private val REACTION_CODE_TO_VALUE: Map<String, Int> by lazy { REACTION_VALUE_TO_CODE.entries.associate { (value, code) -> code to value } }

    private val REACTION_VALUE_TO_CODE: Map<Int, String> = mapOf(
        0 to "bgm67",    // 点赞/爱心 -> tv/44.gif
        79 to "bgm63",   // 闪闪 -> tv/40.gif
        54 to "bgm38",   // 吐血 -> tv/15.gif
        140 to "bgm124", // 庆祝 -> tv/101.gif
        62 to "bgm46",   // 疑惑 -> tv/23.gif
        122 to "bgm106", // 哭哭 -> tv/83.gif
        104 to "bgm88",  // 点赞 -> tv/65.gif
        80 to "bgm64",   // 害羞 -> tv/41.gif
        141 to "bgm125", // 睡觉 -> tv/102.gif
        88 to "bgm72",   // 开心 -> tv/49.gif
        85 to "bgm69",   // 思考 -> tv/46.gif
        90 to "bgm74",   // 调皮 -> tv/51.gif

        // 隐藏表情（服务端 lib/like.ts 的 HIDDEN_REACTIONS）：不在可选集里，但正文与贴贴里会出现
        53 to "bgm37",
        92 to "bgm76",
        118 to "bgm102",
        60 to "bgm44",
        128 to "bgm112",
        47 to "bgm31",
        68 to "bgm52",
        137 to "bgm121",
        76 to "bgm60",
        132 to "bgm116",
    )

    // ---------------------------------------------------------------------
    // code 识别与解析
    // ---------------------------------------------------------------------

    /**
     * 识别表情写法并返回归一后的 code（不含括号）；不是表情时返回 null。
     *
     * 认三种前缀（`bgm`、`musume_`、`blake_`）与 [KAOMOJI] 的 16 个原文。BBCode 解析（经 [detectBare]）与动态正文切分都调这里，判定只有这一处。
     *
     * 注意：只做**语法**识别，不校验数值是否落在真实存在的范围内 ——
     * 那由 [entryOf] 决定。这样 `(bgm999)` 会被识别成表情节点，
     * 但解析不出地址，界面按原文显示。
     */
    fun detect(raw: String): String? {
        val trimmed = raw.trim()
        val inner = if (trimmed.startsWith("(") && trimmed.endsWith(")") && trimmed.length > 2) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return detectBare(inner)
    }

    /** 不带括号的形态；见 [detect]。颜文字区分大小写，先按原文匹配。 */
    internal fun detectBare(code: String): String? {
        if (code in KAOMOJI_NUMBER) return code
        val lower = code.lowercase()
        return when {
            isDigitsAfter(lower, "bgm") -> lower
            isDigitsAfter(lower, "musume_") -> lower
            isDigitsAfter(lower, "blake_") -> lower
            else -> null
        }
    }

    /** 前缀之后必须有数字，且至少一个 —— `bgm` 单独出现不是表情。 */
    private fun isDigitsAfter(value: String, prefix: String): Boolean =
        value.startsWith(prefix) &&
            value.length > prefix.length &&
            value.substring(prefix.length).all { it.isDigit() }

    /** `bgm38` -> 一条解析结果；无法解析返回 null。 */
    fun entryOf(code: String): SmileyEntry? {
        val normalized = detectBare(code) ?: return null
        KAOMOJI_NUMBER[normalized]?.let { return SmileyEntry(normalized, Family.KAOMOJI, "$it.gif") }
        return when {
            normalized.startsWith("bgm") -> {
                val id = normalized.substring(3).toIntOrNull() ?: return null
                bgmEntry(normalized, id)
            }
            normalized.startsWith("musume_") -> {
                val n = normalized.substring(7).toIntOrNull() ?: return null
                // 实测 01..118 存在，**缺 97、98**；119+ 不存在。
                // 缺口是实测发现的：范围不是连续区间，不能只用上下界判断。
                if (n !in 1..LARGE_FAMILY_MAX || n in MUSUME_MISSING) null
                else SmileyEntry(normalized, Family.MUSUME, "musume/musume_${pad2(n)}.gif")
            }
            normalized.startsWith("blake_") -> {
                val n = normalized.substring(6).toIntOrNull() ?: return null
                // 实测 01..118 存在，无缺号
                if (n !in 1..LARGE_FAMILY_MAX) null
                else SmileyEntry(normalized, Family.BLAKE, "blake/blake_${pad2(n)}.gif")
            }
            else -> null
        }
    }

    private fun bgmEntry(normalized: String, id: Int): SmileyEntry? = when (id) {
        in CLASSIC_CODE_MIN..CLASSIC_CODE_MAX -> {
            val ext = if (id in CLASSIC_GIF) "gif" else "png"
            SmileyEntry(normalized, Family.CLASSIC, "bgm/${pad2(id)}.$ext")
        }
        in TV_CODE_MIN..TV_CODE_MAX ->
            SmileyEntry(normalized, Family.TV, "tv/${pad2(id - TV_FILE_OFFSET)}.gif")
        in TV_VS_CODE_MIN..TV_VS_CODE_MAX ->
            SmileyEntry(normalized, Family.TV_VS, "tv_vs/bgm_$id.png")
        in TV_500_CODE_MIN..TV_500_CODE_MAX -> {
            val ext = if (id in TV500_GIF) "gif" else "png"
            SmileyEntry(normalized, Family.TV_500, "tv_500/bgm_$id.$ext")
        }
        else -> null
    }

    /** `bgm38` -> `https://lain.bgm.tv/img/smiles/tv/15.gif`；不可解析返回 null。 */
    fun remoteUrl(code: String): String? = entryOf(code)?.let { REMOTE_PREFIX + it.remotePath }

    /**
     * 该表情应归入哪个渲染档位；不可解析返回 null。
     *
     * 判定依据是**官方页面给这个表情用的 CSS 类**（线上 HTML 实测），不是素材像素：
     *
     * | 家族 | 原生 | 官方类 | 档位 |
     * |---|---|---|---|
     * | CLASSIC | 20px | `.smile` | [Scale.SMALL] |
     * | TV 常规（bgm24-123） | 21px | `.smile` | [Scale.SMALL] |
     * | TV 末尾（bgm124/125） | 42px | `.smile`，`width="21"` | [Scale.SMALL] |
     * | TV_VS | 21px | `.smile` | [Scale.SMALL] |
     * | TV_500 | 21px | `.smile` | [Scale.SMALL] |
     * | MUSUME | 240px | `.smile-musume` | [Scale.LARGE] |
     * | BLAKE | 240px | `.smile-blake` | [Scale.LARGE] |
     * | KAOMOJI | 20-33 × 9-27px | `.smile` | [Scale.SMALL] |
     */
    fun scaleOf(code: String): Scale? = entryOf(code)?.let { scaleOf(it.family) }

    /** 一个家族的渲染档位，见 [scaleOf]（编辑器面板据此选大格或小格）。 */
    fun scaleOf(family: Family): Scale = when (family) {
        Family.MUSUME, Family.BLAKE -> Scale.LARGE
        // bgm124/125 素材虽是 42px，官方页面仍按 `.smile` 缩到 21 宽显示（实测
        // bgm.tv/character/710 的 `<img … class="smile" alt="(bgm124)" width="21">`）；按素材像素归成大表情是错的
        Family.CLASSIC, Family.TV, Family.TV_VS, Family.TV_500, Family.KAOMOJI -> Scale.SMALL
    }


    /**
     * 表情 CDN 前缀。**绝对 URL 只在这里拼接**。
     */
    private const val REMOTE_PREFIX = "https://lain.bgm.tv/img/smiles/"

    // ---------------------------------------------------------------------
    // 反向识别：图片 URL -> code
    // ---------------------------------------------------------------------

    /**
     * 从正文里以内联图片形式出现的表情反推 code。
     *
     * 正文里表情可能写成 `[img]/img/smiles/tv/15.gif[/img]` 而非 `(bgm38)`，
     * 两种写法要归一到同一个 code。
     *
     * 支持全部家族，与 [entryOf] 的正向规则互为逆运算。
     */
    fun codeFromImageUrl(url: String): String? {
        val path = url.substringAfter("/img/smiles/", missingDelimiterValue = "")
        if (path.isEmpty()) return null
        // 去掉查询串（CDN 可能带 ? 参数）
        val clean = path.substringBefore('?').substringBefore('#')
        // 2.gif -> =w=（颜文字的图在 smiles 根目录）
        if ('/' !in clean) {
            val number = clean.removeSuffix(".gif").toIntOrNull() ?: return null
            return KAOMOJI.getOrNull(number - 1)?.text
        }
        val family = clean.substringBefore('/')
        val file = clean.substringAfter('/', missingDelimiterValue = "")
        if (file.isEmpty()) return null
        val stem = file.substringBeforeLast('.')
        val ext = file.substringAfterLast('.', missingDelimiterValue = "")

        return when (family) {
            // tv/15.gif -> bgm38
            "tv" -> stem.toIntOrNull()?.let { fileIndex ->
                val code = fileIndex + TV_FILE_OFFSET
                if (code in TV_CODE_MIN..TV_CODE_MAX) "bgm$code" else null
            }
            // bgm/01.png -> bgm01；gif 与 png 都接受
            "bgm" -> {
                val id = stem.toIntOrNull() ?: return null
                if (id in CLASSIC_CODE_MIN..CLASSIC_CODE_MAX && ext in setOf("png", "gif")) {
                    "bgm${pad2(id)}"
                } else null
            }
            // tv_vs/bgm_201.png -> bgm201
            "tv_vs" -> stem.removePrefix("bgm_").toIntOrNull()?.takeIf { it in TV_VS_CODE_MIN..TV_VS_CODE_MAX }?.let { "bgm$it" }
            // tv_500/bgm_501.gif -> bgm501
            "tv_500" -> stem.removePrefix("bgm_").toIntOrNull()?.takeIf { it in TV_500_CODE_MIN..TV_500_CODE_MAX }?.let { "bgm$it" }
            // musume/musume_01.gif -> musume_01
            "musume" -> stem.takeIf { it.startsWith("musume_") && isDigitsAfter(it, "musume_") }
            // blake/blake_01.gif -> blake_01
            "blake" -> stem.takeIf { it.startsWith("blake_") && isDigitsAfter(it, "blake_") }
            else -> null
        }
    }

    /** 两位补零。文件名一律两位，与 CDN 实际布局一致。 */
    private fun pad2(value: Int) = value.toString().padStart(2, '0')
}
