package io.github.touhougo.chogumi.core.model

/**
 * 目录写入的频率上限：每个账号每 5 分钟最多 [INDEX_EDITS_PER_WINDOW] 次（建、改、删目录，收录、移出、改一条收录，
 * 服务端 `LimitAction.IndexEdit`）。一次拖动排序要改的条数超过它，就不可能在一个窗口里写完。
 */
const val INDEX_EDITS_PER_WINDOW = 15

/** 收录排序值的取值范围：服务端列是 `mediumint(8) unsigned`。 */
private const val MIN_ORDER = 0L
private const val MAX_ORDER = 16_777_215L

/** 拖动后有空隙可用时，新写的排序值之间至少拉开多少（设计决定）：下次挪进它们之间只要改一条。 */
private const val ORDER_GAP = 1024L

/**
 * 一条收录（[relatedId]）要写的排序值：[order] 是新值，[previous] 是原值（一次拖动没写完时写回它），
 * [comment] 是它现在的评语（接口要求两项一起写）。
 */
data class IndexOrderChange(val relatedId: Int, val order: Int, val previous: Int, val comment: String)

/**
 * 一次拖动排序的结果：[entries] 是新顺序、带新排序值的整列收录（写成功后服务端排出来就是它），
 * [changes] 是要写的那几条（排序值变了的），按新顺序排。
 */
data class IndexReorder(val entries: List<IndexEntryBo>, val changes: List<IndexOrderChange>)

/**
 * 把目录一个类别的收录 [entries]（全量）排成 [ids] 的顺序，要改哪几条的排序值——目录排序的唯一模型。
 *
 * 服务端按 `(order, id)` 升序排同一类别的收录：`order` 是非负整数，可以重复，新收录的是 0；同值时收录行 id 小的在前。
 * 改顺序只能逐条改 `order`，而目录写入有频率上限（[INDEX_EDITS_PER_WINDOW]），所以这里找**改动条数最少**的一组值：
 * 尽量多的收录保留原值（它们按新顺序本来就排得开，中间也放得下夹在它们之间的那些），其余的落进前后两个保留项之间；
 * 空隙够时均匀铺开、排在末尾的按 [ORDER_GAP] 拉开，空隙不够时一个挨一个（同值靠 id 分先后）。
 *
 * [ids] 必须是 [entries] 全部 id 的一个排列。
 */
fun indexReorderOf(entries: List<IndexEntryBo>, ids: List<Int>): IndexReorder {
    val byId = entries.associateBy { it.id }
    require(ids.size == entries.size && ids.toSet() == byId.keys) { "ids 必须是 entries 的一个排列" }
    val sequence = ids.map { byId.getValue(it) }
    val keys = sequence.map { SortKey(it.order.toLong(), it.id) }
    val kept = keptSlots(keys)
    val orders = LongArray(keys.size) { keys[it].order }
    kept.zipWithNext { lower, upper ->
        val between = (lower + 1 until upper).map { it - 1 }
        val filled = fill(
            lower = keys.getOrNull(lower - 1),
            upper = keys.getOrNull(upper - 1),
            ids = between.map { keys[it].id },
        )
        between.forEachIndexed { i, position -> orders[position] = filled[i] }
    }
    val reordered = sequence.mapIndexed { i, entry -> entry.copy(order = orders[i].toInt()) }
    val changes = reordered.zip(sequence).filter { (new, old) -> new.order != old.order }
        .map { (new, old) -> IndexOrderChange(new.id, new.order, old.order, old.comment) }
    return IndexReorder(reordered, changes)
}

/** 服务端的排序键：先比 [order]，同值比收录行 [id]。 */
private data class SortKey(val order: Long, val id: Int) : Comparable<SortKey> {
    override fun compareTo(other: SortKey): Int = compareValuesBy(this, other, SortKey::order, SortKey::id)

    /** 紧跟在这个键后面、收录行 id 为 [id] 的最小的键。 */
    fun next(id: Int): SortKey = if (id > this.id) SortKey(order, id) else SortKey(order + 1, id)
}

/** 列表开头之前的位置：紧跟它的第一条可以取最小的排序值。 */
private val START = SortKey(MIN_ORDER, Int.MIN_VALUE)

/**
 * 保留原值的那几条在新顺序里的位置（下标加一；0 与 `keys.size + 1` 是开头、末尾两个哨兵，也在结果里），越多越好。
 *
 * 从保留项 i 到下一个保留项 j 可行，当且仅当把夹在中间的几条一条挨一条地放在 i 后面（[SortKey.next]，每条取能取的最小键）
 * 之后，j 的原键仍排在后面；走到末尾时，这些中间项也必须落在 [MIN_ORDER]..[MAX_ORDER] 内。从每个 i 往后扫一遍
 * 就得到所有可行的 j，整体 O(n²)。
 */
private fun keptSlots(keys: List<SortKey>): List<Int> {
    val end = keys.size + 1
    val best = IntArray(end + 1) { -1 }
    val previous = IntArray(end + 1) { -1 }
    best[0] = 0
    for (from in 0 until end) {
        if (best[from] < 0) continue
        var placed = if (from == 0) START else keys[from - 1]
        for (to in from + 1..end) {
            val feasible = if (to == end) placed.order <= MAX_ORDER else keys[to - 1] > placed
            if (feasible) {
                val score = best[from] + if (to == end) 0 else 1
                if (score > best[to]) {
                    best[to] = score
                    previous[to] = from
                }
            }
            if (to == end) break
            placed = placed.next(keys[to - 1].id)
        }
    }
    return generateSequence(end) { slot -> previous[slot].takeIf { slot > 0 } }.toList().asReversed()
}

/**
 * 给夹在两个保留项 [lower]、[upper] 之间的几条（收录行 id 依次是 [ids]）定新的排序值。null 的 [lower] 是列表开头，
 * null 的 [upper] 是列表末尾。
 */
private fun fill(lower: SortKey?, upper: SortKey?, ids: List<Int>): List<Long> {
    val count = ids.size
    if (count == 0) return emptyList()
    // 开头没有保留项时，第一条可以取到最小值
    val floor = lower?.order ?: (MIN_ORDER - 1)
    if (upper == null) {
        val base = lower?.order ?: (MIN_ORDER - ORDER_GAP)
        if (base + ORDER_GAP * count <= MAX_ORDER) return List(count) { base + ORDER_GAP * (it + 1) }
    } else {
        val span = upper.order - floor
        if (span > count) return List(count) { floor + span * (it + 1) / (count + 1) }
    }
    var placed = lower ?: START
    return ids.map { id -> placed.next(id).also { placed = it }.order }
}
