package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.network.model.v0.AvatarDto
import io.github.touhougo.chogumi.core.network.model.v0.UserDto
import kotlin.test.Test
import kotlin.test.assertEquals

class UserMapperTest {

    private val dto = UserDto(
        id = 42,
        username = "fixture_user",
        nickname = "Fixture User🖖",
        avatar = AvatarDto("l.jpg", "m.jpg", "s.jpg"),
        sign = "Awesome!",
    )

    @Test
    fun `DTO 到 BO 到 Entity 到 BO 往返无损`() {
        val bo = dto.toDomain()
        val entity = bo.toEntity(System.currentTimeMillis())
        val bo2 = entity.toBo()
        assertEquals(bo, bo2)
        assertEquals(42, bo.id)
        assertEquals("m.jpg", bo.avatarMedium)
        assertEquals("Fixture User🖖", bo.nickname)
    }
}
