package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.SubjectInfoboxBo
import io.github.touhougo.chogumi.core.model.TagBo
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxItemDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxKeyValueDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxListDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxOnlyValueDto
import io.github.touhougo.chogumi.core.network.model.v0.InfoboxTextDto
import io.github.touhougo.chogumi.core.network.model.v0.ImagesDto
import io.github.touhougo.chogumi.core.network.model.v0.RatingDto
import io.github.touhougo.chogumi.core.network.model.v0.SubjectCollectionCountDto
import io.github.touhougo.chogumi.core.network.model.v0.SubjectDto
import io.github.touhougo.chogumi.core.network.model.v0.TagDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectMapperTest {

    private val dto = SubjectDto(
        id = 8,
        type = 2,
        name = "こんにちは アニメ",
        nameCn = "你好 Anime",
        summary = "简介",
        date = "1998-01-01",
        platform = "TV",
        infobox = listOf(
            InfoboxItemDto("放送开始", InfoboxTextDto("2024-01-01")),
            InfoboxItemDto(
                "别名",
                InfoboxListDto(
                    listOf(
                        InfoboxKeyValueDto("Alias", "值"),
                        InfoboxOnlyValueDto("只有值"),
                    ),
                ),
            ),
        ),
        images = ImagesDto(large = "l.jpg", common = "c.jpg"),
        eps = 26,
        rating = RatingDto(rank = 1, total = 10, score = 7.8),
        collection = SubjectCollectionCountDto(wish = 5, collect = 3, doing = 2),
        metaTags = listOf("TV"),
        tags = listOf(TagDto("原创", 12)),
    )

    @Test
    fun `SubjectDto 到 BO 到 Entity 到 BO 往返无损`() {
        val bo = dto.toDomain()
        val entity = bo.toEntity(System.currentTimeMillis())
        val bo2 = entity.toBo()
        assertEquals(bo, bo2)
        assertEquals(SubjectType.ANIME, bo.type)
        assertEquals(listOf(TagBo("原创", 12)), bo.tags)
        assertEquals(listOf("TV"), bo.metaTags)
        assertEquals("TV", bo.platform)
        assertEquals(
            listOf(
                SubjectInfoboxBo("放送开始", listOf("2024-01-01")),
                SubjectInfoboxBo("别名", listOf("Alias：值", "只有值")),
            ),
            bo.infobox,
        )
    }

    @Test
    fun `SubjectType 没有值 5 且 6 是三次元`() {
        assertEquals(null, SubjectType.from(5))
        assertEquals(SubjectType.REAL, SubjectType.from(6))
    }

    @Test
    fun `SlimSubject 的收藏人数映射到 collectionTotal 而非 ratingTotal`() {
        val slim = io.github.touhougo.chogumi.core.network.model.v0.SlimSubjectDto(
            id = 8, type = 2, name = "n",
            collectionTotal = 10, score = 8.0, rank = 1,
        )
        val bo = slim.toDomain()
        assertEquals(10, bo.collectionTotal)
        assertEquals(0, bo.ratingTotal)
    }

    @Test
    fun `EpisodeBo 映射 Entity 带上传入的 updatedAt`() {
        val bo = io.github.touhougo.chogumi.core.model.EpisodeBo(
            id = 1, subjectId = 8, type = 0, name = "Ep 1", nameCn = "第1话",
            sort = 1.0, ep = 1.0, airdate = "2024-01-01", comment = 5, desc = "desc",
        )
        val customEntity = bo.toEntity(updatedAt = 123456789L)
        assertEquals(123456789L, customEntity.updatedAt)
        assertEquals(bo, customEntity.toBo())
    }

    @Test
    fun `评分分布：字典转成 1 到 10 分的 10 项列表，缺档记 0，往返落库无损`() {
        val withCounts = dto.copy(rating = RatingDto(rank = 1, total = 6, score = 7.8, count = mapOf("1" to 1, "8" to 3, "10" to 2)))
        val bo = withCounts.toDomain()
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 3, 0, 2), bo.ratingCounts)
        assertEquals(bo.ratingCounts, bo.toEntity(System.currentTimeMillis()).toBo().ratingCounts)
    }

    @Test
    fun `评分分布：接口没给时是空表（未知），不是十个 0`() {
        assertEquals(emptyList(), dto.toDomain().ratingCounts)
    }
}
