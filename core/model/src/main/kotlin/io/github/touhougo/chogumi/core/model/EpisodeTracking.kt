package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE

/**
 * 追番状态推导（**纯函数，唯一判定点**）。
 *
 * 进度锚点只有两种：[EpisodeProgressTarget.Empty] 与带真实集 id 的 [EpisodeProgressTarget.Episode]。
 * `sort` 可合法为 0 或重复，因此空进度不借用任何数值哨兵；集引用以 id 定位，写入顺序以正片列表位置判定。
 *
 * ## 为什么需要它
 *
 * 服务端的条目级 `epStatus`（`interest.ep_status`）是「**看过的记录条数**」，
 * 不是「看到第几集」。历史记录稀疏时（如实际标过 {3,5,7} 而 `epStatus = 3`），
 * 把它当 sort 阈值用会推出错误的「下一集」——点第 8 集会去标第 4 集，
 * 或（更糟）算出「下一集是第 7 集」而第 7 集其实已经看过。
 *
 * 因此「下一集」「已播未看」等一切判定**只能由逐集状态推**，本文件是全 App 的唯一实现。
 *
 * ## 时间来源
 *
 * 涉及「今天」的函数一律**把今天当入参**（[java.time.LocalDate]），
 * 函数体内**不读系统时钟**——否则单测无法覆盖边界，且跨时区/跨零点行为不可控。
 * 调用方负责传入项目统一的「今天」（`AppTime.today`，按 `businessDateOf` 的 UTC+8 口径）。
 */
object EpisodeTracking {

    /** 收藏里的已看记录条数：只数看过的逐集记录，包含特别篇；与正片进度锚点分开。 */
    fun watchedRecordCount(states: Collection<EpisodeStatus>): Int = states.count { it == EpisodeStatus.DONE }

    /**
     * 下一集：**「看到这里」（[currentEpisode]）之后的那一集正片**；一集都没看过时是第一集，看到最后一集则 null。
     *
     * 进度只认「看到第 X 集」这一个锚点。X 之前的空洞（没看、想看、抛弃）只显示、不回头补：
     * 若下一集落进空洞，EP「＋」写的「看到那一集」会把 X 之后的「看过」清掉，首页「待看」也永远清不完。
     *
     * **不依赖集号从 1 开始**：第二季从 13 开始（或海贼王从 1000 开始）时，按正片列表的先后取，不假设 1..N 连续。
     */
    fun nextEpisode(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>): EpisodeBo? =
        afterCurrent(episodes, watchStates).firstOrNull()

    /**
     * 看到 [upTo] 这一集是不是看完了正片：它就是最后一集正片（一集都没看为 null，不算）。**「看完了」的唯一判定**：
     * 章节方格的「全部看完了」（[episodeSummary]）、点一格是不是看完的那一下（[tapOf]）、收藏面板的「进度到头了」都只问这里。
     * 按「看到第 X 集」算：X 之前的空洞（没看、想看、抛弃）不影响。
     */
    fun watchedAllMains(episodes: List<EpisodeBo>, upTo: EpisodeBo?): Boolean =
        upTo != null && upTo.id == mainEpisodes(episodes).lastOrNull()?.id

    /**
     * 「看到这里」之后的正片（按 sort 升序）；一集都没看过时是全部正片。
     * [nextEpisode] 与 [airedUnwatchedCount] 都从这里取，「X 之后」只在这一处判定。
     */
    private fun afterCurrent(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>): List<EpisodeBo> {
        val mains = mainEpisodes(episodes)
        val current = currentEpisode(episodes, watchStates) ?: return mains
        return mains.drop(mains.indexOfFirst { it.id == current.id } + 1)
    }

    /**
     * [target] 在正片列表 [mains]（[mainEpisodes] 的结果）里的位置——**进度目标定位的唯一判定**：空进度为 -1（第一集之前），
     * 看到某集按集 id 找（`sort` 可以是 0、也可以重复，不参与定位）。那一集不在列表里（服务端删了或换了）为 null：
     * 调用方按失败处理，不当成清空。
     */
    fun progressIndexOf(mains: List<EpisodeBo>, target: EpisodeProgressTarget): Int? = when (target) {
        EpisodeProgressTarget.Empty -> -1
        is EpisodeProgressTarget.Episode -> mains.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
    }

    /**
     * 「看到 [target]」之后每集正片应处的状态（按集 id）——**网络批量与本地写穿共用的唯一判定点**，点一格的计数与预览
     * （[tapOf]）也从同一条规则（[statesAfterCut]）出：
     *
     * - 目标集及其之前的正片看过；
     * - 之后的只把「看过」清为无记录，「想看」「抛弃」保持不动（它们本来就算未看，见 [nextEpisode]；清掉会白白丢用户标记、
     *   多占写入限额）。
     *
     * 按正片列表的先后比较，不假设集号从 1 开始；合法的 `sort = 0.0` 因而与空进度完全分开。[current] 里没有的集等同
     * [EpisodeStatus.NONE]。目标集不在 [episodes] 的正片里为 null（[progressIndexOf]）。
     */
    fun statesAfterWatchedUpTo(
        episodes: List<EpisodeBo>,
        current: Map<Int, EpisodeStatus>,
        target: EpisodeProgressTarget,
    ): Map<Int, EpisodeStatus>? {
        val mains = mainEpisodes(episodes)
        val cut = progressIndexOf(mains, target) ?: return null
        return statesAfterCut(mains, current, cut)
    }

    /** 看到正片列表第 [cut] 位（含它；-1 是一集都没看）之后每集正片的状态，规则见 [statesAfterWatchedUpTo]。 */
    private fun statesAfterCut(mains: List<EpisodeBo>, current: Map<Int, EpisodeStatus>, cut: Int): Map<Int, EpisodeStatus> =
        mains.withIndex().associate { (index, episode) ->
            val now = current[episode.id]
            episode.id to when {
                index <= cut -> EpisodeStatus.DONE
                now == EpisodeStatus.DONE -> EpisodeStatus.NONE
                else -> now ?: EpisodeStatus.NONE
            }
        }

    /**
     * 已播未看的**正片**集数：「看到这里」之后、`airdate <= today` 的正片有多少集。
     * 看到这里之前的空洞不算，锚点与 [nextEpisode] 相同。
     *
     * 用于首页「待看」区块：归属由 [homeSection] 限定为动画、三次元；完结作品也适用。
     * **播出日未知（[AirState.UNKNOWN]）不计入**——把没有播出日的集当成「已播」
     * 会让「待看」凭空多出条目；它们由 [nextEpisode] 负责（下一集不受播出日约束）。
     * 网格对 UNKNOWN 的处理不同（当已播出、可点），见 [airState] 的说明。
     */
    fun airedUnwatchedCount(
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
    ): Int = afterCurrent(episodes, watchStates).count { airState(it, today) == AirState.AIRED }

    /**
     * 「追得紧」最多积压集数。**设计决定（非 M3）**：阈值为 2 集。
     */
    private const val TO_WATCH_TIGHT_BACKLOG_LIMIT = 2

    /**
     * 「待看」的冷却天数。**设计决定（非 M3）**：相关时刻早于「今天 − 10 天」的 0 点即出局。
     *
     * 首页「待看」是快捷标记入口（见 [rankToWatch]），不是全量管理列表；
     * 久未碰过的条目占了位置也点不动，交给进度页。用的是场景表里的 H 条：
     * 相关时刻恰为第 10 天 00:00 仍在，早 1 毫秒即出局。
     */
    internal const val TO_WATCH_COLD_DAYS = 10

    /**
     * 待看排序的「最近相关时刻」：`max(lastActiveAt, freshAirAt)`，epoch 毫秒。
     *
     * - [lastActiveAt] 由调用方合并逐集「看过」更新时间与首页收藏快照更新时间；
     * - 仅当已播未看不超过 [TO_WATCH_TIGHT_BACKLOG_LIMIT] 集时，才把最新已播正片的
     *   播出日当天 00:00（[BANGUMI_ZONE]，与 `businessDateOf` 同口径）计为 freshAirAt。
     *
     * 传入的「今天」由调用方提供；此函数不读取系统时钟或外部状态。
     */
    fun toWatchRecency(
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
        lastActiveAt: Long,
    ): Long {
        val backlog = airedUnwatchedCount(episodes, watchStates, today)
        val freshAirAt = if (backlog <= TO_WATCH_TIGHT_BACKLOG_LIMIT) {
            lastAiredEpisode(episodes, today)?.airdate?.toLocalDateOrNull()
                ?.atStartOfDay(BANGUMI_ZONE)
                ?.toInstant()
                ?.toEpochMilli()
                ?: 0L
        } else {
            0L
        }
        return maxOf(lastActiveAt, freshAirAt)
    }

    /**
     * 待看排序**唯一的比较口径**：[ToWatchRank.relevantAt] 倒序 → [ToWatchRank.lastActiveAt] 倒序
     * → 积压数升序 → `subjectId` 升序。
     *
     * 第二键（最近看过时刻）是必须的：同一天多部新集播出时 `relevantAt` 全是同一个 0 点，
     * 没有它就只能拿 `subjectId` 当先后，谁最近看过谁反倒排后面。
     *
     * `private` 是刻意的——外界只能调 [rankToWatch]，不得自己写第二份比较规则。
     */
    private val TO_WATCH_ORDER: Comparator<ToWatchRank> =
        compareByDescending<ToWatchRank> { it.relevantAt }
            .thenByDescending { it.lastActiveAt }
            .thenBy { it.airedUnwatchedCount }
            .thenBy { it.subjectId }

    /**
     * 首页「待看」的**唯一入口**：冷却出局 + 排序，返回可以直接显示（截断前）的列表。
     *
     * ## 为什么收成一个函数
     *
     * 「哪些条目算数」（冷却）与「怎么排」是同一条产品规则的两半，分开放必然漂移：
     * 一处漏掉冷却，或另一处自己再写一个比较器，首页顺序就不再有一个可断言的口径。
     * 因此 [sectionsOf] 只准调它——它不自己过滤、不自己比较。
     *
     * ## 冷却（[TO_WATCH_COLD_DAYS]）
     *
     * `relevantAt` 早于「[today] − [TO_WATCH_COLD_DAYS] 天」的 00:00（[BANGUMI_ZONE]，与
     * `businessDateOf` 同口径）的条目出局。边界是闭区间：恰为那个 0 点仍算在，早 1 毫秒出局。
     * **今天由调用方传入，本函数不读系统时钟**（同 [toWatchRecency]）。
     *
     * ## 截断不在这里
     *
     * 取几条由 `HomeViewModel` 在**冻结顺序之后**做（`stableOrder` 之后再 `take`），
     * 这样标记一集不会把别的可见条目挤出去；改 limit 也只是改取多少，不动已冻结的次序。
     */
    fun rankToWatch(ranks: List<ToWatchRank>, today: java.time.LocalDate): List<ToWatchRank> {
        val cutoff = today.minusDays(TO_WATCH_COLD_DAYS.toLong())
            .atStartOfDay(BANGUMI_ZONE)
            .toInstant()
            .toEpochMilli()
        return ranks.filter { it.relevantAt >= cutoff }.sortedWith(TO_WATCH_ORDER)
    }

    /**
     * 「已播到」那一集：**已播出（[AirState.AIRED]）的正片里 sort 最大的一集**；没有则 null。
     *
     * 首页「待看」卡片的「已播到第 N 集」由它给出。播出日未知的集不算（与 [airedUnwatchedCount] 同口径）。
     */
    fun lastAiredEpisode(
        episodes: List<EpisodeBo>,
        today: java.time.LocalDate,
    ): EpisodeBo? = mainEpisodes(episodes).lastOrNull { airState(it, today) == AirState.AIRED }

    /**
     * 是否连载中。
     *
     * **定义**（设计决定，依据实测）：**存在播出日 ≥ 今天的正片**（即 [nextUpcomingEpisode] 非 null）
     * 即视为连载中。
     *
     * 为什么不用「已播集数 < 总集数」：Bangumi 的 `subject.eps`（总集数）对未完结条目
     * 常为已播出集数（或 0），拿它当分母会把「已完结但集数未知」误判成连载中。
     * 用「今天或未来还有播出日」判断，既不需要总集数，也对「播出日未知」自然保守——
     * 没有任何今天/未来播出正片的条目（含全部完结番、书籍、以及播出日缺失的条目）**不**算连载中。
     *
     * **为什么是「≥ 今天」而不是「> 今天」**：要求播出日**严格晚于**今天的话，
     * 于是「今天播最终话」的番在当天被踢出「待看」，掉进「继续」——可用户当天正要看这一集。
     * 今天播出的那一集也是「还没播完的连载」的一部分，故算连载中。
     * 注意 [airState] 的「今天播出算已播出」口径**不变**：今天这一集在按钮上仍可直接标记。
     *
     * 2026-09-29 实测（`airdate` 形如 `"2026-07-04"`，
     * 且服务端同一字段既驱动 `todayOnAir` 也驱动放送表）。
     */
    fun isOngoing(
        episodes: List<EpisodeBo>,
        today: java.time.LocalDate,
    ): Boolean = nextUpcomingEpisode(episodes, today) != null

    /**
     * 「下一个未来播出」的正片：按 `sort` 升序，第一个**播出日 ≥ 今天**的正片；没有则 null。
     *
     * 这是「追更时间表」的分组键，与 [isOngoing] **同一口径**（`isOngoing == (本函数 != null)`），
     * 也是时间表海报上「EP N」标签的来源。
     *
     * 与 [nextEpisode] 的区别：那个看**观看进度**（「看到这里」之后的那一集，播出日可能已过），
     * 这个只看**播出日**（今天及以后）。两者回答不同问题，各自在所在场景里是唯一判定点。
     * 播出日未知（[AirState.UNKNOWN]）的集不参与——无从判断就不当作未来集。
     */
    fun nextUpcomingEpisode(
        episodes: List<EpisodeBo>,
        today: java.time.LocalDate,
    ): EpisodeBo? = mainEpisodes(episodes).firstOrNull { ep ->
        val airdate = ep.airdate.toLocalDateOrNull() ?: return@firstOrNull false
        !airdate.isBefore(today)
    }

    /** 下一个未来播出正片的播出日；没有则 null（[nextUpcomingEpisode] 的投影）。 */
    fun nextUpcomingAirdate(
        episodes: List<EpisodeBo>,
        today: java.time.LocalDate,
    ): java.time.LocalDate? = nextUpcomingEpisode(episodes, today)?.airdate?.toLocalDateOrNull()

    /**
     * 首页**领域候选**的归类：**一个 when，条目只得到一个 [HomeSection] 标签**。
     *
     * 这只是归类，不等于首页最终展示在哪一块。两层分开看：
     * - 本函数（领域层）：给每个条目打上标签——
     *   - [HomeSection.TO_WATCH]：追踪章节的动画、三次元只要有已播未看正片就归入，完结作品也包括在内；
     *   - [HomeSection.SCHEDULE_ONLY]：连载中且没有已播未看；
     *   - [HomeSection.CONTINUE]：其余条目，包括书籍、没有已播未看的完结作品；
     *   - [HomeSection.IN_PROGRESS]：游戏、音乐。
     * - 首页展示层（`sectionsOf`，feature:home）：
     *   - 顶部「待看」轮播取 [HomeSection.TO_WATCH] 候选冻结排序后截断到上限的那几部；
     *   - 「追更时间表」收待看与仅时间表中有未来播出的连载作品，一部作品可同时出现在轮播与时间表；
     *   - 「继续」展示的是**顶部（轮播截断之后 + 时间表）实际没展示出来的条目**——
     *     候选是除 [HomeSection.IN_PROGRESS] 之外的全部标签，因此被轮播截掉、冷却出局的待看作品
     *     也会回落到这里，并不只有 [HomeSection.CONTINUE] 标签的条目。
     *
     * 判定顺序即优先级：没有进度的类型（游戏、音乐）→ 有已播未看的动画或三次元 → 连载（仅时间表）→ 继续。
     */
    fun homeSection(
        subjectType: SubjectType,
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
    ): HomeSection = when {
        !hasProgress(subjectType) -> HomeSection.IN_PROGRESS
        tracksEpisodes(subjectType) && airedUnwatchedCount(episodes, watchStates, today) > 0 -> HomeSection.TO_WATCH
        isOngoing(episodes, today) -> HomeSection.SCHEDULE_ONLY
        else -> HomeSection.CONTINUE
    }

    /**
     * `"2026-07-04"` → [java.time.LocalDate]；空串、格式不合法、或语义非法（如 2 月 30 日）返回 null。
     *
     * 用 `DateTimeFormatter.ISO_LOCAL_DATE` 而非 `LocalDate.parse` 默认行为：
     * 后者对 `"2026-7-4"` 这类非补零写法也放行，而 Bangumi 恒为补零格式，
     * 宽松解析会把脏数据当成合法日期静默接受。
     */
    internal fun String.toLocalDateOrNull(): java.time.LocalDate? =
        if (isBlank()) null
        else runCatching { java.time.LocalDate.parse(this, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

    // -----------------------------------------------------------------------
    // 播出状态：**全 App 唯一的「播没播」判定**（红线一）
    // -----------------------------------------------------------------------

    /** 一集的播出日；没有或不合法为 null（解析规则见 [toLocalDateOrNull]）。 */
    fun airdateOf(episode: EpisodeBo): java.time.LocalDate? = episode.airdate.toLocalDateOrNull()

    /**
     * 某一集的播出状态——**唯一判定点**。调用方只决定 [AirState.UNKNOWN] 怎么处理。
     *
     * **今天播出算 [AirState.AIRED]**。Bangumi 只有播出日期、没有具体时刻，用户当天看完
     * 最新一集就要能标上；因此「播出日 <= 今天」一律算已播出、可标记。
     *
     * 各处对 [AirState.UNKNOWN]（`airdate` 为空或不合法）的处理**刻意不同**，都写在这里：
     * - [airedUnwatchedCount]（首页「待看」计数）：**保守不计入**——把未知当已播会让待看凭空多出条目；
     * - [episodeGrid]（网格）：**当已播出**，可点、画成已播未看的空位——无从判断就不拦用户，与 [nextEpisode]
     *   「不受播出日约束」的口径一致；
     * - [epButtonState]：**当已播出**，按钮可点；
     * - [isOngoing] / [nextUpcomingEpisode]：**不算未来集**——播出日未知的集不参与连载判定。
     *
     * 这几条差异不是不一致，是各自场景的取舍；改任一条都要回来改这份说明。
     */
    fun airState(episode: EpisodeBo, today: java.time.LocalDate): AirState =
        when (val airdate = airdateOf(episode)) {
            null -> AirState.UNKNOWN
            else -> if (airdate.isAfter(today)) AirState.UNAIRED else AirState.AIRED
        }

    // -----------------------------------------------------------------------
    // 章节方格
    // -----------------------------------------------------------------------

    /** 未播出的集在多少天以内要播，格子里就写出播出日（再远的只画灰格）。我的设计决定（not in M3）。 */
    const val UPCOMING_AIRDATE_DAYS = 31L

    /** 点一格一次改动超过这么多集就先确认，防误触（产品决定：15 集以内一点即成）。 */
    const val WATCH_JUMP_CONFIRM_OVER = 15

    /** 超过这个集数就按 [EPISODE_RANGE_SIZE] 集一段分段（原型 `phase1.html` 的 `RANGE = 100`）。 */
    private const val EPISODE_RANGE_THRESHOLD = 100

    /** 每个分段包含的集数。 */
    const val EPISODE_RANGE_SIZE = 100

    /**
     * 正片列表（按 `sort` 升序）。**全 App 唯一一份正片过滤**。
     *
     * 判定走 [EpisodeType.isMain]，它收齐了接口的全部取值（见 [EpisodeType]）；
     * 界面不得再写一遍类型数字。
     *
     * 详情页状态里存的是**完整**章节列表（含 SP/OP/ED），因为网格下方的只读特别篇列表也要用它；
     * 正片与非正片的切分只在这里发生，界面不得再写一遍 `type == 0`。
     */
    fun mainEpisodes(episodes: List<EpisodeBo>): List<EpisodeBo> =
        episodes.filter { EpisodeType.isMain(it.type) }.sortedBy { it.sort }

    /** 非正片列表（SP/OP/ED/预告/MAD/其他，按 `sort` 升序）：不参与正片进度，只能逐集单独标记。 */
    internal fun specialEpisodes(episodes: List<EpisodeBo>): List<EpisodeBo> =
        episodes.filter { !EpisodeType.isMain(it.type) }.sortedBy { it.sort }

    /**
     * 特别篇按类型分组（章节 tab 正片方格下方的特别篇方格）：只收接口已知的类型（[EpisodeType.isKnown]），
     * 组按 [EpisodeType.ALL] 的次序、组内按 `sort` 升序；空组不出现。
     */
    fun specialGroups(episodes: List<EpisodeBo>): List<Pair<Int, List<EpisodeBo>>> {
        val byType = specialEpisodes(episodes).filter { EpisodeType.isKnown(it.type) }.groupBy { it.type }
        return EpisodeType.ALL.mapNotNull { type -> byType[type]?.let { type to it } }
    }

    /** 章节方格有没有格子可画（正片，或已知类型的特别篇）：章节 tab「没有章节」的唯一判定。 */
    fun hasTiles(episodes: List<EpisodeBo>): Boolean =
        mainEpisodes(episodes).isNotEmpty() || specialGroups(episodes).isNotEmpty()

    /**
     * 特别篇的格子——**唯一判定点**。特别篇互不连续，不连段、没有「看到这里 / 下一集」：
     * 看过 → [EpisodeCellKind.WATCHED]；播出日晚于今天 → [EpisodeCellKind.UNAIRED]；
     * 网页上标的想看、抛弃照样显示；其余 → [EpisodeCellKind.AIRED]。
     */
    fun specialCell(
        episode: EpisodeBo,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
    ): EpisodeCell {
        val status = watchStates[episode.id]
        val kind = when {
            status == EpisodeStatus.DONE -> EpisodeCellKind.WATCHED
            airState(episode, today) == AirState.UNAIRED -> EpisodeCellKind.UNAIRED
            else -> markedKind(status)
        }
        return cellOf(episode, kind, status, today)
    }

    /** 已播、没看、也不是下一集的格子：网页上标的想看、抛弃照样画出来（App 里不改它们），其余是空位。 */
    private fun markedKind(status: EpisodeStatus?): EpisodeCellKind = when (status) {
        EpisodeStatus.WISH -> EpisodeCellKind.WISH
        EpisodeStatus.DROPPED -> EpisodeCellKind.DROPPED
        else -> EpisodeCellKind.AIRED
    }

    /** 一格：种类之外，今天播出没看的标小点，[UPCOMING_AIRDATE_DAYS] 天内要播的写播出日。 */
    private fun cellOf(
        episode: EpisodeBo,
        kind: EpisodeCellKind,
        status: EpisodeStatus?,
        today: java.time.LocalDate,
    ): EpisodeCell {
        val airdate = episode.airdate.toLocalDateOrNull()
        return EpisodeCell(
            episode = episode,
            kind = kind,
            airdate = airdate,
            airsToday = airdate == today && status != EpisodeStatus.DONE,
            upcomingAirdate = airdate?.takeIf { kind == EpisodeCellKind.UNAIRED && !it.isAfter(today.plusDays(UPCOMING_AIRDATE_DAYS)) },
        )
    }

    /** 点一个特别篇 = 在「看过」与「无记录」之间切换（只动这一集）。想看/抛弃视作未看过，点了变看过。 */
    fun specialToggleTarget(current: EpisodeStatus?): EpisodeStatus =
        if (current == EpisodeStatus.DONE) EpisodeStatus.NONE else EpisodeStatus.DONE

    // -----------------------------------------------------------------------
    // 章节方格：行、分段与点一格
    // -----------------------------------------------------------------------

    /**
     * 分段网格：**界面只调这一个**，拿到行与分段，不自己切段或算锚点。
     *
     * 之所以收成一个函数：CURRENT/NEXT 必须按**完整**正片列表算，而分段的网格只画某一段。
     * 若把「当前段」当成要画的那批集，锚点就会在该段内重算——
     * 海贼王看到 1090 集时，1–100 段会把第 100 集画成「看到这里」，
     * 用户点它就走退一集，把全局进度从 1090 退到 1089。
     * 收成一个入口后，界面**没有机会**传错列表。
     *
     * 「今天」由调用方传入（函数内不读系统时钟），见本对象 KDoc 的时间来源说明。
     */
    fun episodeGrid(
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
        selectedRange: Int,
        columns: Int,
    ): EpisodeGrid {
        require(columns > 0) { "网格列数必须为正" }
        val mains = mainEpisodes(episodes)
        val ranges = episodeRanges(mains)
        val index = selectedRange.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
        val shown = ranges.getOrNull(index).orEmpty()
        return EpisodeGrid(
            rows = rowsOf(shown, episodes, watchStates, today, columns),
            ranges = ranges,
            rangeWatched = ranges.map { range -> range.count { watchStates[it.id] == EpisodeStatus.DONE } },
            selectedRange = index,
        )
    }

    /**
     * 行构造：把 [visible]（要画的那批正片）切成每行 [columns] 格，
     * 但 CURRENT / NEXT 锚点按 [all]（**完整**正片列表）算。
     *
     * [visible] 与 [all] 分开就是为了让「只画一段」不影响锚点；不分段时两者同一个列表。
     */
    private fun rowsOf(
        visible: List<EpisodeBo>,
        all: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
        columns: Int,
    ): List<EpisodeRow> {
        if (visible.isEmpty()) return emptyList()
        val currentId = currentEpisode(all, watchStates)?.id
        val nextId = nextEpisode(all, watchStates)?.id

        return visible.chunked(columns).map { rowEpisodes ->
            val cells = rowEpisodes.map { ep ->
                val status = watchStates[ep.id]
                val kind = when {
                    status == EpisodeStatus.DONE ->
                        if (ep.id == currentId) EpisodeCellKind.CURRENT else EpisodeCellKind.WATCHED
                    airState(ep, today) == AirState.UNAIRED -> EpisodeCellKind.UNAIRED
                    ep.id == nextId -> EpisodeCellKind.NEXT
                    else -> markedKind(status)
                }
                cellOf(ep, kind, status, today)
            }
            EpisodeRow(cells = cells, bands = bandsOf(cells.map { it.kind }))
        }
    }

    /**
     * 「看到这里」那一集：**正片列表（按 sort 排好）里最后一集看过的**；没有看过的集则 null。
     *
     * 进度的唯一锚点：下一集、已播未看（经 [afterCurrent]）、退一集、网格的「看到这里」都从它算，不各算一遍。
     */
    fun currentEpisode(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>): EpisodeBo? =
        mainEpisodes(episodes).lastOrNull { watchStates[it.id] == EpisodeStatus.DONE }

    /**
     * 章节分段：段长 [EPISODE_RANGE_SIZE]、正片多到 [splitsIntoRanges] 才分段。
     * 不分段时返回**唯一一段**（含全部正片），调用方无需特判。
     */
    fun episodeRanges(mains: List<EpisodeBo>): List<List<EpisodeBo>> =
        if (splitsIntoRanges(mains.size)) mains.chunked(EPISODE_RANGE_SIZE) else listOf(mains)

    /**
     * [mainCount] 集正片要不要分段（超过 [EPISODE_RANGE_THRESHOLD] 集）。分段就有分段导航条：
     * 章节还没到时，骨架按条目的话数用它预留导航条的位置。
     */
    fun splitsIntoRanges(mainCount: Int): Boolean = mainCount > EPISODE_RANGE_THRESHOLD

    /**
     * 分段网格的默认段下标：**下一集所在段**；全部看过则最后一段。
     *
     * 纯函数，界面只接收结果——分段是进度推导的一部分，不是界面自己的视图状态。
     */
    fun defaultRangeIndex(ranges: List<List<EpisodeBo>>, watchStates: Map<Int, EpisodeStatus>): Int {
        if (ranges.isEmpty()) return 0
        val anchorId = nextEpisode(ranges.flatten(), watchStates)?.id
            ?: ranges.flatten().lastOrNull()?.id
        return ranges.indexOfFirst { chunk -> chunk.any { it.id == anchorId } }.coerceIn(0, ranges.lastIndex)
    }

    /**
     * 退一集的目标：**「看到这里」（[currentEpisode]）的前一集正片**，调用方「看到它」，即取消看到这里那一集。
     *
     * - 看到这里就是第一集 → `null`，调用方清空；
     * - 一集都没看过、空列表 → `null`。
     *
     * 稀疏记录也按这个规则走：看过 {1,2,5} 时看到这里是 5，退到第 4 集。
     */
    internal fun revertTarget(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>): EpisodeBo? {
        val current = currentEpisode(episodes, watchStates) ?: return null
        val mains = mainEpisodes(episodes)
        return mains.getOrNull(mains.indexOfFirst { it.id == current.id } - 1)
    }

    /**
     * 点方格里的 [episode] 是什么意思——**网格点击的唯一判定**：要「看到」哪一集（点「看到这里」是退一集，
     * 退到底为 [EpisodeProgressTarget.Empty]），这一下让哪些正片变化，要不要先确认（合计超过 [WATCH_JUMP_CONFIRM_OVER] 集）。
     * 每集怎么变按 [statesAfterWatchedUpTo] 的规则，与写入同一口径；方格的预览与逐格过场都读这里给出的 [EpisodeTap.changes]。
     */
    fun tapOf(episodes: List<EpisodeBo>, watchStates: Map<Int, EpisodeStatus>, episode: EpisodeBo): EpisodeTap {
        val mains = mainEpisodes(episodes)
        val current = currentEpisode(episodes, watchStates)
        // 点「看到这里」是退一集，退到底是一集都没看
        val upTo = if (episode.id == current?.id) revertTarget(episodes, watchStates) else episode
        val cut = indexIn(mains, upTo)
        val after = statesAfterCut(mains, watchStates, cut)
        val changes = mains.mapNotNull { main ->
            val watched = watchStates[main.id] == EpisodeStatus.DONE
            val watchedAfter = after[main.id] == EpisodeStatus.DONE
            when {
                watchedAfter && !watched -> main.id to WatchChange.FILL
                watched && !watchedAfter -> main.id to WatchChange.CLEAR
                else -> null
            }
        }.toMap()
        return EpisodeTap(
            target = targetOf(upTo),
            forward = cut > indexIn(mains, current),
            changes = changes,
            completes = watchedAllMains(episodes, upTo),
        )
    }

    /** 正片 [episode] 在 [mains] 里的位置；null（一集都没看）是 -1。 */
    private fun indexIn(mains: List<EpisodeBo>, episode: EpisodeBo?): Int =
        episode?.let { wanted -> mains.indexOfFirst { it.id == wanted.id } } ?: -1

    /**
     * 章节 tab 顶上那一行：看到第几集、共几集正片、播出到哪、是不是全部看完。
     *
     * 播出：今天或以后还有正片要播是「连载中」（同 [isOngoing]），带已播集数；全部正片都有播出日且都已播出是「已完结」；
     * 播出日不全又没有要播的，说不准，不写。
     */
    fun episodeSummary(
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
    ): EpisodeSummary {
        val mains = mainEpisodes(episodes)
        val airing = when {
            isOngoing(episodes, today) -> EpisodeAiring.Ongoing(aired = mains.count { airState(it, today) == AirState.AIRED })
            mains.isNotEmpty() && mains.all { airState(it, today) == AirState.AIRED } -> EpisodeAiring.Finished
            else -> EpisodeAiring.Unknown
        }
        return EpisodeSummary(
            current = currentEpisode(episodes, watchStates),
            total = mains.size,
            airing = airing,
            allWatched = watchedAllMains(episodes, currentEpisode(episodes, watchStates)),
        )
    }

    /**
     * 一行的连段——**连段的唯一判定**：把**列相邻**的看过格合成连续段，稀疏记录各自成段。
     * [kinds] 是这一行从左到右每格的种类；网格行（[episodeGrid]）与方格逐格过场时按此刻显示的种类连段都只调这里。
     *
     * 例：一行内看过列为 `{0,1,2,4}` → 两段 `[0..2]` 与 `[4..4]`。
     * 「看到这里」格（[EpisodeCellKind.CURRENT]）在连段内，与看过格同等相连。
     */
    fun bandsOf(kinds: List<EpisodeCellKind>): List<EpisodeBand> {
        val bands = mutableListOf<EpisodeBand>()
        var runStart = -1
        kinds.forEachIndexed { column, kind ->
            val watched = kind == EpisodeCellKind.WATCHED || kind == EpisodeCellKind.CURRENT
            when {
                watched && runStart < 0 -> runStart = column
                !watched && runStart >= 0 -> {
                    bands += EpisodeBand(runStart, column - 1)
                    runStart = -1
                }
            }
        }
        if (runStart >= 0) bands += EpisodeBand(runStart, kinds.lastIndex)
        return bands
    }

    /**
     * EP 按钮状态——**唯一判定点**。
     *
     * - [EpButtonState.Empty]：章节已返回，但没有任何正片（确实没有可点的进度动作）；
     *   章节**还没返回**是 [EpButtonState.Loading]，由调用方在拿到章节之前给出，本函数不会返回它；
     * - [EpButtonState.Finished]：正片全部看过（[nextEpisode] 为 null）→ 按钮显示「标为看过」；
     * - [EpButtonState.Waiting]：下一集**未播出**（[AirState.UNAIRED]，即播出日严格晚于今天）
     *   → 显示「明天／N 天后」且不可点；同时带上一集正片到这一集的播出间隔（倒计时环的满格，
     *   见 [EpButtonState.Waiting.elapsedFraction]）；
     * - [EpButtonState.Next]：其余（已播出、或播出日未知）→ 可点，标记到这一集。
     *
     * 两个可见态都带 **这一集在本条目正片里的序号**（[mainEpisodePosition]，从 1 数起），
     * 按钮上显示的就是它，而不是系列绝对集号 `sort`——列表行的「看过 N / M 集」数的也是正片条数，
     * 两者因此在同一个坐标系里，不会出现「看过 18 / 51 集」配一个「220」这种要心算的组合。
     * 注意它**不等于**「看过数 + 1」：跳着看时（看了 1–17 和 20）看过数 18，下一集却是第 21 个正片。
     *
     * **今天播出算可点**（[AirState.AIRED]）——Bangumi 只有播出日期，用户当天看完最新一集
     * 就要能标上。因此 [EpButtonState.Waiting.daysUntil] 恒 `>= 1`，「今天」不出现在等待态里；
     * 「哪天算播出」与章节网格同取 [airState]，两处口径一致。
     */
    fun epButtonState(
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
    ): EpButtonState {
        val mains = mainEpisodes(episodes)
        if (mains.isEmpty()) return EpButtonState.Empty
        val next = nextEpisode(episodes, watchStates) ?: return EpButtonState.Finished
        val position = mainEpisodePosition(mains, next)
        if (airState(next, today) != AirState.UNAIRED) return EpButtonState.Next(next, position)
        // UNAIRED 蕴含「播出日已知且晚于今天」，天数必然算得出来——不做兜底：
        // 兜底会掩盖「airState 与 airdate 解析口径不一致」这类真 bug。
        val nextAirdate = requireNotNull(next.airdate.toLocalDateOrNull()) {
            "未播出的集必须有可解析的播出日（airState 已判定）：airdate=${next.airdate}"
        }
        val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, nextAirdate)
        // 上一集正片（按 sort 紧挨在前的那一集）到这一集的间隔：第一集、播出日未知、同日连播时没有间隔
        val intervalDays = mains.getOrNull(position - 2)?.airdate?.toLocalDateOrNull()
            ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, nextAirdate) }
            ?.takeIf { it > 0 }
        return EpButtonState.Waiting(next, daysUntil, position, intervalDays)
    }

    /**
     * 一集在本条目正片里的序号（从 1 数起）——**唯一判定点**。
     *
     * [mains] 必须是 [mainEpisodes] 的结果（已按 `sort` 排好、已剔除特典）。
     * 传进来的 [episode] 必然在其中（调用方都来自 [nextEpisode]），找不到是逻辑错误，不做兜底。
     */
    fun mainEpisodePosition(mains: List<EpisodeBo>, episode: EpisodeBo): Int {
        val index = mains.indexOfFirst { it.id == episode.id }
        require(index >= 0) { "第 ${episode.sort} 集不在正片列表里" }
        return index + 1
    }

    /**
     * 条目级进度计数——**全 App 进度计数唯一判定点**。
     *
     * - 逐集推进类型（动画、三次元，见 [tracksEpisodes]）：
     *   - 逐集表尚未到位（[episodes] == null 或 [watchStates] == null）：会拉逐集表的状态（[loadsEpisodeStates]，
     *     即「在看」）返回 [ProgressCount.Loading]；其余状态不拉，取收藏记录里服务端算好的 `epStatus / totalEps`
     *     （否则这些行永远停在骨架上）；
     *   - 逐集表没到位、这一轮的章节或逐集请求又失败了（[loadFailed]）：不再等，同样取收藏记录的 `epStatus / totalEps`，
     *     计数照常显示，重试成功、逐集表到位后换回按逐集数；
     *   - 逐集表已到位：返回 [ProgressCount.Known]，分子为状态为「看过」的**正片**（[mainEpisodes]）条数，
     *     特典一律不计入分子；分母同样只数正片（与分子同源）；
     * - 书籍：没有逐集表，单位取 [bookUnitOverride]（详情页按规格表只记卷），没给时由 [bookUnit] 判定；
     *   用卷时取 `volStatus / totalVols`，否则 `epStatus / totalEps`；
     * - 游戏、音乐：没有进度，返回 [ProgressCount.None]（[hasProgress] 为假，一个像素都不占）。
     */
    fun progressCount(
        subjectType: SubjectType,
        episodes: List<EpisodeBo>?,
        watchStates: Map<Int, EpisodeStatus>?,
        epStatus: Int = 0,
        volStatus: Int = 0,
        totalEps: Int = 0,
        totalVols: Int = 0,
        bookUnitOverride: BookUnit? = null,
        collectionType: CollectionType,
        loadFailed: Boolean = false,
    ): ProgressCount = when {
        !hasProgress(subjectType) -> ProgressCount.None
        tracksCounts(subjectType) -> {
            val unit = bookUnitOverride ?: bookUnit(epStatus, volStatus)
            if (unit == BookUnit.VOL) ProgressCount.Known(unit, volStatus, totalVols)
            else ProgressCount.Known(unit, epStatus, totalEps)
        }
        episodes != null && watchStates != null -> {
            val mains = mainEpisodes(episodes)
            val watched = mains.count { watchStates[it.id] == EpisodeStatus.DONE }
            ProgressCount.Known(unit = null, current = watched, total = mains.size)
        }
        loadsEpisodeStates(subjectType, collectionType) && !loadFailed -> ProgressCount.Loading
        else -> ProgressCount.Known(unit = null, current = epStatus, total = totalEps)
    }

    /**
     * 记数类型（书籍，[tracksCounts]）的 EP 按钮状态（由 [progressCount] 派生）：达到总话数/总卷数时为 [EpButtonState.Finished]。
     */
    private fun countedEpButtonState(
        subjectType: SubjectType,
        epStatus: Int,
        volStatus: Int,
        totalEps: Int,
        totalVols: Int,
        bookUnitOverride: BookUnit?,
    ): EpButtonState {
        val count = progressCount(
            subjectType = subjectType,
            collectionType = CollectionType.DOING,
            episodes = null,
            watchStates = null,
            epStatus = epStatus,
            volStatus = volStatus,
            totalEps = totalEps,
            totalVols = totalVols,
            bookUnitOverride = bookUnitOverride,
        ) as? ProgressCount.Known ?: return EpButtonState.Empty
        if (count.total > 0 && count.current >= count.total) return EpButtonState.Finished
        return EpButtonState.BookNext(current = count.current, unit = requireNotNull(count.unit))
    }

    /**
     * 条目通用的 EP 按钮状态判定——**全项目唯一判定入口**。
     *
     * 书籍由 [countedEpButtonState] 给出状态（「第 N 话 / 第 N 卷」，单位见 [progressCount] 的 `bookUnitOverride`），
     * 非书籍按章节与逐集状态给出。
     * 界面层直接使用本函数返回的 [EpButtonState]，**禁止在 UI 里写 when(type)**。
     */
    fun epButtonState(
        subjectType: SubjectType,
        episodes: List<EpisodeBo>,
        watchStates: Map<Int, EpisodeStatus>,
        today: java.time.LocalDate,
        epStatus: Int = 0,
        volStatus: Int = 0,
        totalEps: Int = 0,
        totalVols: Int = 0,
        bookUnitOverride: BookUnit? = null,
    ): EpButtonState = if (tracksCounts(subjectType)) {
        countedEpButtonState(subjectType, epStatus, volStatus, totalEps, totalVols, bookUnitOverride)
    } else {
        epButtonState(episodes, watchStates, today)
    }

    /**
     * 该类型的进度是否靠**逐集状态**推进（动画、三次元）。由规格表的 [ProgressUnit.EPISODE] 派生，不另判类型。
     */
    fun tracksEpisodes(subjectType: SubjectType): Boolean =
        subjectTypeSpec(subjectType).progressUnit == ProgressUnit.EPISODE

    /**
     * 该类型的进度是否是**收藏记录里的话数 / 卷数**（书籍）：显示取 `epStatus / volStatus`，写入走收藏 PATCH。
     * 由规格表的 [ProgressUnit.VOLUME] 派生，不另判类型。
     */
    fun tracksCounts(subjectType: SubjectType): Boolean =
        subjectTypeSpec(subjectType).progressUnit == ProgressUnit.VOLUME

    /**
     * 该类型有没有进度（列表行是否显示进度文字）：逐集（[tracksEpisodes]）或记数（[tracksCounts]）都算；
     * 游戏、音乐、不认识的类型没有进度，一个像素都不占。
     */
    fun hasProgress(subjectType: SubjectType): Boolean =
        subjectTypeSpec(subjectType).progressUnit != null

    /**
     * 是否为这一行拉章节与逐集状态——**唯一判定点**：逐集推进的类型（[tracksEpisodes]）且「在看」。
     * 其余状态（想看/看过/搁置/抛弃）的列表可能有上百条，逐条拉章节太重；它们的进度由 [progressCount]
     * 取收藏记录里的 `epStatus`。进度页预取与进度计数都经这里，不各判一次。
     */
    fun loadsEpisodeStates(subjectType: SubjectType, collectionType: CollectionType): Boolean =
        tracksEpisodes(subjectType) && collectionType == CollectionType.DOING

    /**
     * 列表行是否**预留 EP 槽位**——**唯一判定点**：这一行有没有可能出现按钮。
     * 要带进度（[hasProgress]），且收藏状态是「在看」——只有「在看」的条目才写进度。
     *
     * 类型与收藏状态在第一帧就都知道，所以槽位不会随章节加载而出现或消失，行内不回流；
     * 「想看/看过」等行不留槽位、没有空隙。进度页算按钮状态时也走这里。
     */
    fun reservesEpSlot(subjectType: SubjectType, collectionType: CollectionType): Boolean =
        hasProgress(subjectType) && collectionType == CollectionType.DOING


    /**
     * 服务端 `/p1/home` 返回的 `eps[]` 完整性判定。
     *
     * ## 证明
     * 服务端 `lib/home/progress.ts` 的 `dynamicEpList` 实现为：
     * `if (eps.length <= 72) return eps;`（未截断，全量返回）
     * 否则取以进度为起点的闭区间 `[start, start + 72]` 窗口（最多 73 个元素）。
     *
     * 判为完整的充要条件为：服务端走了 `eps.length <= 72` 分支。
     * 1. 若条目章节数 > 72，由于闭区间大小至多 73 且正片全量总数等于 `subject.eps`，
     *    截断窗口内的正片数严格小于 `subject.eps`（除非截断前总数 ≤ 72），或者返回总数超过 72。
     * 2. 因此满足 `subjectEps > 0 && returnedMains >= subjectEps && returnedTotal <= 72` 时，
     *    不仅所有正片均已返回，且总数未超出服务端截断阈值 72，必未发生截断。
     * 3. 对总集数未知（`subjectEps <= 0`）或超出 72 的条目，本函数保守返回 `false`，
     *    走 v0 全量端点回退，保证数据绝对准确。
     *
     * 截断窗口可能恰好含全部正片而漏掉 sort 更大的 SP——无妨：进度判定只看正片。
     */
    fun isEpisodeListComplete(
        subjectType: SubjectType,
        subjectEps: Int,
        episodes: List<EpisodeBo>,
    ): Boolean {
        // 不靠逐集状态推进度的类型不用章节，恒视为完整、不触发回退。
        if (!tracksEpisodes(subjectType)) return true
        if (subjectEps <= 0 || episodes.size > 72) return false
        val mainCount = mainEpisodes(episodes).size
        return mainCount >= subjectEps
    }
}
