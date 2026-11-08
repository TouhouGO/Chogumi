package io.github.touhougo.chogumi.core.model

/**
 * 条目的年月（月份可能未知）。比较时先年后月，未知月份排在该年最前。
 */
data class SubjectYearMonth(val year: Int, val month: Int?) : Comparable<SubjectYearMonth> {
    override fun compareTo(other: SubjectYearMonth): Int =
        compareValuesBy(this, other, { it.year }, { it.month ?: 0 })
}

private val YearMonthPattern = Regex("""(?<!\d)(\d{4})\s*(?:年\s*(?:(\d{1,2})\s*月)?|[-/.]\s*(\d{1,2})(?!\d))""")

/**
 * 从文本里取第一个日期的年月（唯一解析处）。
 *
 * 兼容实测见过的写法：`2025-03-20`、`2025年12月10日`、`2018年5月`、`2007年`；文本可以夹在别的字段中间
 * （p1 SlimSubject 的 `info` 形如 `1话 / 2018年5月26日 / …`，2026-10-05 实测）。
 * 光秃秃的四位数（价格、话数）不算日期：年份后面必须跟「年」或日期分隔符。
 * 年份不在 1900..2999 或月份不在 1..12 时视为不是日期。
 */
fun parseSubjectYearMonth(text: String): SubjectYearMonth? =
    YearMonthPattern.findAll(text).firstNotNullOfOrNull { match ->
        val year = match.groupValues[1].toInt()
        val month = (match.groupValues[2].ifEmpty { match.groupValues[3] }).takeIf { it.isNotEmpty() }?.toInt()
        when {
            year !in 1900..2999 -> null
            month != null && month !in 1..12 -> null
            else -> SubjectYearMonth(year, month)
        }
    }

/** 作品列表（人物 / 角色作品、目录收录）排序需要的三个量。 */
interface WorkSortKey {
    val ratingScore: Double
    val ratingTotal: Int
    val date: SubjectYearMonth?
}

/** 作品列表的排序方式。 */
enum class MonoWorkSort { RATING, TIME }

/**
 * 评分人数不到这个数的作品在「按评分」里沉底（[sortByRating]，人物 / 角色作品与目录收录共用）。
 * 不然悠木碧排第一的是 1 人打 10 分的特典 CD。
 */
const val WORK_MIN_VOTES = 50

/**
 * 可选的排序方式（唯一判定）：公共评分关掉时不提供「按评分」。
 */
fun monoWorkSortOptions(publicRatings: Boolean): List<MonoWorkSort> =
    if (publicRatings) listOf(MonoWorkSort.RATING, MonoWorkSort.TIME) else listOf(MonoWorkSort.TIME)

/**
 * 默认排序：人物「参与作品」按评分，角色「出演作品」按时间（[monoTypeSpec]）；公共评分关掉时一律按时间。
 */
fun defaultMonoWorkSort(owner: MonoType, publicRatings: Boolean): MonoWorkSort =
    if (publicRatings) monoTypeSpec(owner).defaultSort else MonoWorkSort.TIME

/**
 * 人物 / 角色作品排序（唯一真源，详情页的前 5 部与「全部」子页共用）。
 *
 * - [MonoWorkSort.TIME]：新的在前；日期未知的沉底；同月保持输入顺序。
 * - [MonoWorkSort.RATING]：[sortByRating]。
 * - [publicRatings] 为 false 时无论请求什么都按时间（他人评分不可见，不能拿它排序）。
 *
 * 输入必须是**全量**列表：两个接口都没有排序参数，在已加载的一页上排结果是错的。
 */
fun <T : WorkSortKey> sortMonoWorks(items: List<T>, sort: MonoWorkSort, publicRatings: Boolean): List<T> {
    val effective = if (publicRatings) sort else MonoWorkSort.TIME
    return when (effective) {
        MonoWorkSort.TIME -> items.sortedWith(
            compareBy<T> { it.date == null }.thenByDescending { it.date },
        )
        MonoWorkSort.RATING -> sortByRating(items)
    }
}

/**
 * 「按评分」（唯一真源）：评分人数 ≥ [WORK_MIN_VOTES] 的按分数降序（同分人数多的在前）；
 * 不足的沉底，沉底部分按评分人数降序（同人数分高的在前）。稳定排序。
 *
 * 输入必须是**全量**列表：接口都没有排序参数，在已加载的一页上排结果是错的。
 * 公共评分开关由调用方先判（关掉时不提供「按评分」）。
 */
fun <T : WorkSortKey> sortByRating(items: List<T>): List<T> = items.sortedWith(
    compareBy<T> { it.ratingTotal < WORK_MIN_VOTES }
        .thenComparator { a, b ->
            if (a.ratingTotal >= WORK_MIN_VOTES) {
                compareValuesBy(b, a, { it.ratingScore }, { it.ratingTotal })
            } else {
                compareValuesBy(b, a, { it.ratingTotal }, { it.ratingScore })
            }
        },
)

/** 详情页海报横排最多露出几部（设计决定，not in M3：横排约两屏，再多就进「全部」）。 */
internal const val MONO_WORKS_PREVIEW_COUNT = 10

/**
 * 角色 / 人物详情页「出演 / 参与作品」海报横排露出哪几部、什么顺序（唯一判定）：
 * 先按默认排序（[defaultMonoWorkSort]：人物按评分、角色按时间），再把定位更主要的提前
 * （主角 > 配角 > 客串 > 闲角 > 旁白，同定位保持默认排序；人物的作品没有定位，这一步不改变顺序）。
 * 最多 [MONO_WORKS_PREVIEW_COUNT] 部。「全部」子页仍是完整列表与可切换的排序。
 */
fun monoWorksPreview(owner: MonoType, works: List<MonoWorkBo>, publicRatings: Boolean): List<MonoWorkBo> =
    sortMonoWorks(works, defaultMonoWorkSort(owner, publicRatings), publicRatings)
        .sortedWith(compareBy(nullsLast()) { it.role })
        .take(MONO_WORKS_PREVIEW_COUNT)
