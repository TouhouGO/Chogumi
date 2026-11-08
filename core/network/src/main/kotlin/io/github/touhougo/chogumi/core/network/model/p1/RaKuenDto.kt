package io.github.touhougo.chogumi.core.network.model.p1

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 联合依据合同中的外层 type 选择。认不出的 type（服务端新加的种类）解成 [RaKuenUnknownTopicDto]，
 * 不默认成任一已知类别，也不让一条拖垮整页：映射时丢掉这一条。
 */
@Serializable(with = RaKuenTopicSerializer::class)
sealed interface RaKuenTopicDto

/** 认不出种类的一条；它不对应合同里的任何分支。 */
data object RaKuenUnknownTopicDto : RaKuenTopicDto

/**
 * 整条读过、不取任何字段，得到 [RaKuenUnknownTopicDto]。多态选择要的是完整的 [KSerializer]；
 * 本应用从不编码这个类型，编码时写一个空对象。
 */
private object RaKuenUnknownTopicSerializer : KSerializer<RaKuenUnknownTopicDto> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): RaKuenUnknownTopicDto {
        JsonElement.serializer().deserialize(decoder)
        return RaKuenUnknownTopicDto
    }
    override fun serialize(encoder: Encoder, value: RaKuenUnknownTopicDto) =
        JsonElement.serializer().serialize(encoder, JsonObject(emptyMap()))
}

object RaKuenTopicSerializer : JsonContentPolymorphicSerializer<RaKuenTopicDto>(RaKuenTopicDto::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RaKuenTopicDto> = when (element.jsonObject["type"]?.jsonPrimitive?.content) {
        "group" -> RaKuenGroupTopicDto.serializer()
        "subject" -> RaKuenSubjectTopicDto.serializer()
        "episode" -> RaKuenEpisodeDto.serializer()
        "character" -> RaKuenCharacterDto.serializer()
        "person" -> RaKuenPersonDto.serializer()
        else -> RaKuenUnknownTopicSerializer
    }
}
@Serializable
data class RaKuenGroupTopicDto(val type: String, val id: Int, val title: String, val replyCount: Int, val creator: P1UserDto, val group: SlimGroupDto, val updatedAt: Long) : RaKuenTopicDto
@Serializable
data class RaKuenSubjectTopicDto(val type: String, val id: Int, val title: String, val replyCount: Int, val creator: P1UserDto, val subject: P1SlimSubjectDto, val updatedAt: Long) : RaKuenTopicDto
@Serializable
data class RaKuenEpisodeDto(val type: String, val id: Int, val subject: P1SlimSubjectDto, val episode: RaKuenEpisodeSummaryDto, val updatedAt: Long) : RaKuenTopicDto
@Serializable
data class RaKuenEpisodeSummaryDto(val id: Int, val sort: Double, val type: Int, val name: String, val nameCN: String, val comment: Int)
@Serializable
data class RaKuenCharacterDto(val type: String, val id: Int, val name: String, val nameCN: String, val images: PersonImagesDto? = null, val comment: Int, val updatedAt: Long) : RaKuenTopicDto
@Serializable
data class RaKuenPersonDto(val type: String, val id: Int, val name: String, val nameCN: String, val images: PersonImagesDto? = null, val comment: Int, val updatedAt: Long) : RaKuenTopicDto
