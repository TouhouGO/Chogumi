package io.github.touhougo.chogumi.core.model

/**
 * 角色在所有作品里的定位取最主要的一个（唯一判定，头部「主角、N 人收藏」）：
 * 只要在一部作品里是主角就算主角，否则配角，再否则客串；没有任何定位时为 null（头部不写这一段）。
 */
fun characterBillingRole(works: List<MonoWorkBo>): CastRole? = works.mapNotNull { it.role }.minOrNull()

/**
 * 角色的声优（唯一判定，头部 CV 行）：出现在最多作品里的那一位；次数相同取先出现的。没有声优时为 null（不画 CV 行）。
 * 同一部作品里出现两次只算一次。
 *
 * 三次元作品（真人剧 / 电影）里的出演者是**演员**不是声优，不参与（例：安乐冈花火的动画与同名真人剧各一部，
 * 平票时取了先列出的真人剧女主演）。只出现在三次元作品里的角色没有 CV。
 */
fun characterVoiceActor(works: List<MonoWorkBo>): MonoPersonRefBo? {
    val counts = LinkedHashMap<Int, Int>()
    val people = HashMap<Int, MonoPersonRefBo>()
    works.filter { it.subjectType != SubjectType.REAL }.forEach { work ->
        work.actors.distinctBy { it.id }.forEach { actor ->
            people.putIfAbsent(actor.id, actor)
            counts[actor.id] = (counts[actor.id] ?: 0) + 1
        }
    }
    // maxBy 取第一个最大值，LinkedHashMap 保住首次出现顺序
    return counts.entries.maxByOrNull { it.value }?.let { people.getValue(it.key) }
}

/** 头部事实 chip 的种类。值取资料表里第一个有值的键（按 [keys] 顺序）。 */
enum class MonoFactKind(val keys: List<String>) {
    GENDER(listOf("性别")),
    BIRTHDAY(listOf("生日")),
    BIRTHPLACE(listOf("出身地", "出生地")),
    AGENCY(listOf("事务所", "所属事务所")),
}

/** 头部的一枚事实 chip。 */
data class MonoFact(val kind: MonoFactKind, val value: String)

/**
 * 头部事实 chip（唯一取值处）：取哪几种由 [monoTypeSpec] 决定
 * （角色是性别、生日；人物是生日、出身地、事务所）。资料表里没有的种类跳过，不留空位。
 */
fun monoFacts(type: MonoType, infobox: List<FactRow>): List<MonoFact> =
    monoTypeSpec(type).factKinds.mapNotNull { kind ->
        kind.keys.firstNotNullOfOrNull { key ->
            infobox.firstOrNull { it.key == key }?.value?.trim()?.takeIf { it.isNotEmpty() }
        }?.let { MonoFact(kind, it) }
    }

/**
 * 「全部资料」的行（唯一判定）：空值项在映射时已丢弃；「简体中文名」与标题相同不列（标题已经写了）。
 */
fun monoInfoRows(infobox: List<FactRow>, title: String): List<FactRow> =
    infobox.filterNot { it.key == SIMPLIFIED_CHINESE_NAME_KEY && it.value.trim() == title.trim() }

private const val SIMPLIFIED_CHINESE_NAME_KEY = "简体中文名"

/** 作品子页的第二维筛选按什么分：人物按职位、角色按出场（第一维一律是作品类型）。 */
enum class MonoWorkFacetBy { POSITION, ROLE }

/**
 * 角色与人物两类实体的全部「按类别变」的决定（唯一规格表，同作品的 [subjectTypeSpec]）。
 * 事实 chip、作品筛选、默认排序都从这里取，任何地方不写第二个 `when (MonoType)` 做业务判定。
 */
data class MonoTypeSpec(
    val factKinds: List<MonoFactKind>,
    val facetBy: MonoWorkFacetBy,
    /** 公共评分开着时的默认排序（关掉时一律按时间，见 [defaultMonoWorkSort]）。 */
    val defaultSort: MonoWorkSort,
    /** 有没有「代表角色」一区（人物配过音、画过的角色；角色本身没有）。 */
    val hasCastCharacters: Boolean,
    /** bgm.tv 网页地址里的路径段（`/character/{id}`、`/person/{id}`），拼地址与认地址共用（[BgmWeb]）。 */
    val webPath: String,
    /** 报告这里一条吐槽时的对象类型（服务端角色回复、人物回复各一类）。 */
    val replyReportType: ReportType,
    /** 收进目录时的类别。 */
    val indexCategory: IndexCategory,
)

fun monoTypeSpec(type: MonoType): MonoTypeSpec = when (type) {
    MonoType.CHARACTER -> MonoTypeSpec(
        factKinds = listOf(MonoFactKind.GENDER, MonoFactKind.BIRTHDAY),
        facetBy = MonoWorkFacetBy.ROLE,
        defaultSort = MonoWorkSort.TIME,
        hasCastCharacters = false,
        webPath = "character",
        replyReportType = ReportType.CHARACTER_REPLY,
        indexCategory = IndexCategory.CHARACTER,
    )
    MonoType.PERSON -> MonoTypeSpec(
        factKinds = listOf(MonoFactKind.BIRTHDAY, MonoFactKind.BIRTHPLACE, MonoFactKind.AGENCY),
        facetBy = MonoWorkFacetBy.POSITION,
        defaultSort = MonoWorkSort.RATING,
        hasCastCharacters = true,
        webPath = "person",
        replyReportType = ReportType.PERSON_REPLY,
        indexCategory = IndexCategory.PERSON,
    )
}

/** 作品子页第二维筛选的一个取值。 */
sealed interface MonoWorkFacet {
    /** 人物：某个职位。 */
    data class Position(val name: String) : MonoWorkFacet

    /** 角色：在这部作品里的出场（主角 / 配角 / 客串）。 */
    data class Role(val role: CastRole) : MonoWorkFacet
}

/** 「全部」作品子页的筛选：作品类型 × 第二维，两维可组合；null 表示该维不限。 */
data class MonoWorkFilter(val type: SubjectType? = null, val facet: MonoWorkFacet? = null)

/** 一枚带计数的筛选项。 */
data class MonoWorkOption<T>(val value: T, val count: Int)

/** 作品子页两排筛选的可选项（各自带计数）。 */
data class MonoWorkFilterOptions(
    val types: List<MonoWorkOption<SubjectType>>,
    val facets: List<MonoWorkOption<MonoWorkFacet>>,
)

/**
 * 作品子页的筛选项与计数（唯一判定，同网页的「类型 / 职位」两排）：
 * - **有哪些项**只看全量作品，与当前选择无关，按在作品里**首次出现**的顺序（接口按时间倒序返回，常任的职位自然在前）。
 *   所以选了一维，另一排既不会消失也不会少项（否则选「客串」后类型那排会整排消失）；
 * - **计数**两维互相约束：类型的计数按当前第二维算，第二维的计数按当前类型算，可能为 0（界面把这一项收起，排在原位不动）。
 * 输入必须是全量列表，否则后面几页才出现的职位会漏掉、计数也不对。
 */
fun monoWorkFilterOptions(type: MonoType, works: List<MonoWorkBo>, filter: MonoWorkFilter): MonoWorkFilterOptions {
    val facetsOf: (MonoWorkBo) -> List<MonoWorkFacet> = when (monoTypeSpec(type).facetBy) {
        MonoWorkFacetBy.POSITION -> { work -> work.positions.distinct().map(MonoWorkFacet::Position) }
        MonoWorkFacetBy.ROLE -> { work -> listOfNotNull(work.role?.let(MonoWorkFacet::Role)) }
    }
    val typesOf: (MonoWorkBo) -> List<SubjectType> = { listOf(it.subjectType) }
    val types = works.countInOrder(typesOf, within = works.filter { filter.facet == null || filter.facet in facetsOf(it) })
    val facets = works.countInOrder(facetsOf, within = works.filter { filter.type == null || it.subjectType == filter.type })
    return MonoWorkFilterOptions(types = types, facets = facets)
}

/** 项取自全部作品（首次出现顺序），计数只数 [within] 里的。 */
private fun <T> List<MonoWorkBo>.countInOrder(keysOf: (MonoWorkBo) -> List<T>, within: List<MonoWorkBo>): List<MonoWorkOption<T>> {
    val counts = LinkedHashMap<T, Int>()
    forEach { work -> keysOf(work).forEach { counts.putIfAbsent(it, 0) } }
    within.forEach { work -> keysOf(work).forEach { counts[it] = counts.getValue(it) + 1 } }
    return counts.map { (value, count) -> MonoWorkOption(value, count) }
}

/** 这部作品是否落在筛选里。 */
fun MonoWorkFilter.matches(work: MonoWorkBo): Boolean =
    (type == null || work.subjectType == type) &&
        when (val f = facet) {
            null -> true
            is MonoWorkFacet.Position -> f.name in work.positions
            is MonoWorkFacet.Role -> work.role == f.role
        }

/**
 * 「出演角色」子页的筛选：作品类型 × 出场，两维可组合，null 表示不限。走接口参数筛选并从第 0 页重新分页，
 * 不在已加载的页上本地过滤。
 */
data class PersonCastFilter(val type: SubjectType? = null, val role: CastRole? = null)

/** 出演角色可筛的作品类型（接口 `subjectType` 的全部取值，按 [SubjectType] 声明顺序）。 */
val personCastTypeOptions: List<SubjectType> = SubjectType.entries.filter { it != SubjectType.UNKNOWN }

/**
 * 出演角色两排筛选的可选项与计数：每个选项单独向接口取一次总数（`limit=1`）。
 * - **有哪些项**取自不带筛选时计数大于 0 的项，之后不变（同作品子页 [monoWorkFilterOptions]）；
 * - **计数**两维互相约束：类型的计数带上当前选的角色类型，角色类型的计数带上当前选的作品类型，
 *   筛选一变就重取（否则选了「游戏」，下面的「主角 145」仍是全部类型的数）。计数可能为 0，界面把这一项收起。
 */
data class PersonCastFilterOptions(
    val types: List<MonoWorkOption<SubjectType>>,
    val roles: List<MonoWorkOption<CastRole>>,
)
