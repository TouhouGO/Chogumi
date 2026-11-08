package io.github.touhougo.chogumi.feature.monodetails

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.PersonCastFilter

/** 本模块测试共用的假仓库：默认每个接口都「未触达」，用例只覆写自己要的那几个。 */
internal open class FakeMonoRepository : MonoRepository {
    override suspend fun getDetails(type: MonoType, id: Int): MonoDetailsBo = error("未触达")
    override suspend fun getPersonImage(id: Int): String? = error("未触达")
    override suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean): List<MonoWorkBo> = error("未触达")
    override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> = error("未触达")
    override suspend fun getRelations(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<MonoRelationBo> = error("未触达")
    override suspend fun getIndexes(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<IndexBo> = error("未触达")
    override suspend fun getComments(type: MonoType, id: Int): List<CommentThreadBo> = error("未触达")
    override suspend fun setCollected(type: MonoType, id: Int, collected: Boolean): Outcome<Unit> = error("未触达")
}
