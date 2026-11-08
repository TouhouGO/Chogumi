package io.github.touhougo.chogumi.core.model

/**
 * 「明确成人向」标签判定 —— **全仓唯一的纯判定函数**。
 *
 * ## 用途
 *
 * 标签目录缓存（`TagCatalogRepository`）在 NSFW 开关关闭时，用它作为
 * [io.github.touhougo.chogumi.core.data.repository.NsfwFilter] 的过滤谓词，把成人向标签
 * 从查找结果里剔除。开关打开时不过滤（调用方负责）。
 *
 * **缓存内容与开关无关**：各类型前 5000 个标签照存不误，本函数只在**查找结果**这一层生效。
 *
 * ## 为什么靠词表
 *
 * 契约 `SubjectTag` 只有 `{name, count}`，服务端**没有**标签级 NSFW 字段
 * （`tools/openapi/raw/p1/openapi.json` 的 `SubjectTag`；
 * 2026-09-30 实测）。条目级的 `nsfw` 是**作品**的，
 * 要靠「标签命中多少部 NSFW 作品」反推，当前上游不提供。因此只能维护词表。
 *
 * ## 判定口径（精度优先）
 *
 * **宁缺勿滥**：有歧义的词一律不收（中文语境里 `猎奇`/`鬼畜`/`熟女`/`魅魔`/`里`
 * 都常用于正常向作品，2026-09-30 实测）。误杀正常标签的代价高于漏掉个别成人向标签。
 * 同理，`BL`/`耽美`/`性转`/`女装`/`触手`/`痴汉` **不收** —— 它们是题材/角色设定，
 * 不是成人向判定（`BL` 尤甚：实测 `ufotable`/`BLEACH` 等正常标签都含 `bl`）。
 *
 * ## 匹配规则（三档，不区分大小写、先 trim）
 *
 * 三档的存在都是**实测逼出来的**（2026-09-30 扫五个类型目录）：
 * - [EXACT_NAMES]：整名相等才命中。作为子串会误伤真实标签
 *   （`LapinTrack` 含 `ntr`、`调教咖啡厅` 含 `调教`、`成人童话` 含 `成人`），故只认整名。
 * - [CONTAINS_FRAGMENTS]：子串命中。实测无假阳性，且能覆盖
 *   `3D里番`/`HentaiAnime`/`成年コミック` 这类组合写法。
 * - [TOKEN_NAMES]：**按非字母数字切词**后任一词相等才命中。用来抓 `ero`
 *   这类裸词：`ERO-RPG`/`ERO-GAL`/`ero` 命中，而 `ZERO-G`/`Keroro`/`Xenoblade`
 *   切出的词是 `zero`/`keroro`/`xenoblade`，不会被误伤（子串规则做不到这点）。
 *
 * ## 覆盖范围（2026-09-30 实测）
 *
 * 扫过五个类型目录（动画前 5000、其余各前 1000）：
 * 动画 32 / 书籍 16 / 音乐 1 / 游戏 19 / 三次元 1 条命中，人工逐条复核后**全部为真正的成人向词，无假阳性**
 * （2026-09-30 人工复核）。
 * 其中 `成年コミック`（书籍第 6）、`拔作`（游戏第 4）、`本子`（书籍第 49）、
 * `18+`/`無修`/`eroge`/`抜きゲー` 等词也在表里：书籍 / 游戏的高频成人向词不能只按动画的词表查。
 *
 * @param name 标签原文（可为任意大小写、可带首尾空白）。
 */
fun isExplicitAdultTag(name: String): Boolean {
    val normalized = name.trim().lowercase()
    if (normalized in EXACT_NAMES) return true
    if (CONTAINS_FRAGMENTS.any { it in normalized }) return true
    return normalized.split(NON_ALNUM).any { it in TOKEN_NAMES }
}

/** 切词用：非字母、非数字的连续片段都算分隔符。 */
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

/**
 * 整名相等的成人向标签（小写）。
 *
 * - 年龄分级：`r18`/`r-18`/`r18g`/`r-18g`/`18禁`/`18x`/`18+`；
 * - 性内容题材：`ntr`/`寝取`/`调教`/`凌辱`/`淫乱`；
 * - 性暗示题材：`卖肉`/`肉番`；
 * - 直白成人向：`成人`/`成人向`/`成人漫画`/`成人动画`；
 * - 直白色情：`色情`/`涩情`；
 * - 作品/商品类型：`本子`（同人本）；`無修`/`无修`（无码缩写）；
 * - 游戏用语：`eroge`（`EROGE`/`Eroge` 归一后）。
 *
 * 注：`エロゲー` 不在此列 —— 它被子串片段 `エロ` 覆盖，重复列举属死条目。
 */
private val EXACT_NAMES = setOf(
    "r18", "r-18", "r18g", "r-18g", "18禁", "18x", "18+",
    "ntr", "寝取", "调教", "凌辱", "淫乱",
    "卖肉", "肉番",
    "成人", "成人向", "成人漫画", "成人动画",
    "色情", "涩情",
    "本子", "無修", "无修",
    "eroge",
)

/**
 * 子串命中的成人向片段（小写）。
 *
 * - `里番` 覆盖 `3D里番`/`泡面里番`/`里番未评分` 等 14 种写法；
 * - `エロ` 为日文工口（同时覆盖 `エロゲー`）；
 * - `無修正`/`无修正` 为无码全称；`工口` 为中文工口；`hentai` 覆盖 `3DHentai`/`HentaiAnime`；
 * - `成年コミック` 为日文「成年漫画」（书籍高频）；
 * - `拔作`/`抜きゲ` 为日文/中文的成人游戏用语（`抜きゲー`）。
 */
private val CONTAINS_FRAGMENTS = listOf(
    "里番", "エロ", "無修正", "无修正", "工口", "hentai",
    "成年コミック", "拔作", "抜きゲ",
)

/**
 * 切词后**整词**相等的成人向词（小写）。
 *
 * 只有 `ero` 一个：它必须以词出现（`ERO-RPG`/`ERO-GAL`/`ERO-ADV`/`ero`），
 * 作为子串会误伤 `ZERO-G`/`Keroro`/`OneRoom` 等正常标签。
 */
private val TOKEN_NAMES = setOf("ero")
