package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagBo

/**
 * 详情页骨架几何测试与截图测试共用的假数据。全部在本地构造，不连账号、不发网络。
 *
 * 数据特意取「字段都有」的一份：骨架按常见形态的上界画（标题两行、原名一行、元信息一行、两枚小标、
 * 一行标签、三行简介），字段缺失时真实内容只会比骨架矮，内容出现时下面的区块往上收，不在本测试的断言范围内。
 */
internal object DetailsSkeletonFixtures {

    /** 标题足够长：1 倍与 2 倍字号下都会占满两行（`maxLines = 2`）。 */
    val subject = SubjectBo(
        id = 1,
        type = SubjectType.ANIME,
        name = "葬送のフリーレン",
        nameCn = "葬送的芙莉莲 这是一个足够长的标题 用来在任何字号下都占满两行显示",
        summary = "勇者辛美尔等人击败魔王，为世界带来和平，此后精灵魔法使芙莉莲踏上了新的旅程。".repeat(12),
        date = "2023-09-29",
        platform = "TV",
        eps = 28,
        totalEpisodes = 28,
        ratingScore = 8.9,
        ratingRank = 12,
        ratingTotal = 12345,
        collectionTotal = 54321,
        metaTags = listOf("奇幻", "冒险", "治愈"),
        tags = listOf(TagBo("魔法", 100), TagBo("旅途", 80), TagBo("人生", 60)),
    )

    /**
     * 「半截条目」：列表接口（p1 放送/浏览/热门）落库的短行，照 `subjects` 表的实测签名构造——
     * 有 metaTags（分类），没有 tags、简介、放送日期、平台/话数。详情页从列表点进来读到的就是它。
     */
    val partialSubject = SubjectBo(
        id = 456078,
        type = SubjectType.ANIME,
        name = "マジカル★エクスプローラー",
        nameCn = "魔法★探险家",
        ratingScore = 6.0,
        ratingTotal = 31,
        metaTags = listOf("TV", "日本", "校园", "奇幻", "穿越", "小说改"),
        hasFullDetail = false,
    )

    /**
     * [partialSubject] 的完整版：详情接口补上 tags、简介、放送日期与平台/话数。
     * 简介取长文，确保折叠态（3 行）被填满——短简介的真实高度比占位矮，属于「只向上收」，不在本用例。
     */
    val fullSubject = partialSubject.copy(
        summary = "传说级美少女游戏《魔法★探险家》。我转生成为凭作弊能力被众多女角簇拥的主角，" +
            "身边负责陪笑的不幸好友角色沈香勋……！在创作游戏中的毫不起眼，得不到回报，" +
            "一直到最后都只是个配角。但我可是拥有这款游戏的一切、悄悄埋下的伏笔，" +
            "仿佛游玩的前世的知识！！而这一次，我要反过来利用那些知识，站到最前面。".repeat(3),
        date = "2026-10-03",
        platform = "TV",
        totalEpisodes = 13,
        tags = listOf(TagBo("2026年10月", 12), TagBo("穿越", 9), TagBo("TV", 8), TagBo("2026", 7)),
        hasFullDetail = true,
    )

    /** 完整但没有标签也没有简介的条目：标签行占位必须整块上收，不许留在原地。 */
    val fullSubjectWithoutTagsOrSynopsis = fullSubject.copy(
        summary = "",
        metaTags = emptyList(),
        tags = emptyList(),
    )

    /**
     * 另一种半截条目：v0 搜索/浏览落库的行。它有 tags、有 summary、有放送日期，但 summary 是服务端
     * **截断**的摘要（实测截在 120 字），metaTags 与 infobox/platform 缺失。
     * 摘要当终值渲染的话，完整简介到达时会再长高几行、把下面整块推下去。
     */
    val partialSearchSubject = SubjectBo(
        id = 8,
        type = SubjectType.ANIME,
        name = "Code Geass: Hangyaku no Lelouch",
        nameCn = "Code Geass 反叛的鲁路修",
        summary = "被神圣不列颠帝国侵略而亡国的日本——11 区中，在生死边缘存活下来的少年鲁路修。",
        date = "2008-04-06",
        eps = 25,
        ratingScore = 8.3,
        tags = listOf(TagBo("机战", 50), TagBo("鲁路修", 40)),
        hasFullDetail = false,
    )

    /** [partialSearchSubject] 的完整版：简介补全并填满折叠态的 3 行，补齐 metaTags 与平台。 */
    val fullSearchSubject = partialSearchSubject.copy(
        summary = "被神圣不列颠帝国侵略而亡国的日本——11 区中，在生死边缘存活下来的少年鲁路修，" +
            "获得了名为「Geass」的力量：对他人的绝对服从。他戴上假面、自称 Zero，" +
            "率领黑色骑士团向帝国举起反旗，而昔日挚友朱雀却站到了他的对面。".repeat(4),
        platform = "TV",
        totalEpisodes = 25,
        metaTags = listOf("TV", "日本", "机战", "原创"),
        tags = listOf(TagBo("机战", 50), TagBo("鲁路修", 40), TagBo("2006", 30)),
        hasFullDetail = true,
    )

    /** 正片 [count] 集，播出日都在过去：网格里全是「已播出」格。 */
    fun episodes(count: Int = 12): List<EpisodeBo> = (1..count).map { sort ->
        EpisodeBo(
            id = 1000 + sort,
            subjectId = subject.id,
            type = EpisodeType.NORMAL,
            name = "ep$sort",
            nameCn = "",
            sort = sort.toDouble(),
            airdate = "2023-10-%02d".format(sort),
        )
    }

    val persons = (1..6).map { id ->
        RelatedPersonBo(id = id, name = "制作人员$id", type = 1, relation = "导演")
    }

    val characters = (1..6).map { id ->
        RelatedCharacterBo(id = id, name = "角色$id", summary = "", type = 1, role = CastRole.MAIN)
    }

    /**
     * 关联作品卡标题固定两行（`minLines = maxLines = 2`），骨架画的就是这个上界。标题取长名，让真实卡在
     * 1 倍与 2 倍字号下都渲染足两行；一行短标题会因 `minLines` 补齐的那一行比真实两行略矮（约 1.3dp），
     * 卡片底缘上收，那是数据形态差异而非骨架错位（与个人页网格卡用长名同理）。
     * 关系 ID 1（改编）：卡片左上角的关系徽标画「动画」。
     */
    val relations = (1..6).map { id ->
        SubjectRelationBo(
            id = 100 + id,
            type = SubjectType.ANIME,
            name = "关联作品$id 的标题足够长，用来在关联卡片里占满两行显示",
            nameCn = "关联作品$id 的标题足够长，用来在关联卡片里占满两行显示",
            relationId = 1,
            score = 0.0,
        )
    }
}
