package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.RelatedImagesBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.model.p1.EpisodeDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectImagesDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectRelationDto

/** p1 info 是条目信息短行，不冒充详情 summary；interest 属于账户收藏，不进入公共条目。 */
fun P1SlimSubjectDto.toSubjectBo() = SubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    rawType = type,
    name = name,
    nameCn = nameCN,
    imageLarge = images?.large,
    imageCommon = images?.common,
    ratingScore = rating.score,
    ratingRank = rating.rank,
    ratingTotal = rating.total,
    nsfw = nsfw,
    metaTags = metaTags,
    // p1 短行：没有 tags/summary/date/platform，不是完整详情。
    hasFullDetail = false,
)

fun P1SlimSubjectDto.toSnapshot(updatedAt: Long) = SubjectSnapshot(
    value = toSubjectBo().toEntity(updatedAt),
    fields = buildSet {
        addAll(listOf(SubjectField.TYPE, SubjectField.NAME, SubjectField.NAME_CN,
            SubjectField.RATING_SCORE, SubjectField.RATING_RANK, SubjectField.RATING_TOTAL,
            SubjectField.NSFW, SubjectField.META_TAGS))
        if (images != null) addAll(listOf(SubjectField.IMAGE_LARGE, SubjectField.IMAGE_COMMON))
    },
)

/** p1 的一集（首页进度、单集讨论页）。标题与超展开的单集行一样去掉 HTML 实体。 */
fun EpisodeDto.toEpisodeBo() = EpisodeBo(
    id = id,
    subjectId = subjectID,
    type = type,
    name = name.unescapeHtmlEntities(),
    nameCn = nameCN.unescapeHtmlEntities(),
    sort = sort,
    airdate = airdate,
    comment = comment,
    desc = desc,
    disc = disc,
)

/** p1 条目图 → 领域图片档位容器；只搬字段，取哪档由 [RelatedImagesBo] 决定。 */
fun SubjectImagesDto.toRelatedImagesBo() = RelatedImagesBo(large, common, medium, small, grid)

/** 关联项只取分组与卡片要用的字段；关系语义由 relationId 在领域层归类，不带服务端的中文名。 */
fun SubjectRelationDto.toBo() = SubjectRelationBo(
    id = subject.id,
    type = SubjectType.from(subject.type) ?: SubjectType.UNKNOWN,
    name = subject.name,
    nameCn = subject.nameCN,
    images = subject.images?.toRelatedImagesBo(),
    relationId = relation.id,
    order = order,
    nsfw = subject.nsfw,
    score = subject.rating.score,
)
