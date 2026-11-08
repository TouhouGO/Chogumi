package io.github.touhougo.chogumi.core.network.model.v0

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class InfoboxItemDto(val key: String, val value: InfoboxValueDto)
@Serializable(with = InfoboxValueSerializer::class)
sealed interface InfoboxValueDto
@Serializable
@JvmInline
value class InfoboxTextDto(val value: String) : InfoboxValueDto
@Serializable
@JvmInline
value class InfoboxListDto(val value: List<InfoboxEntryDto>) : InfoboxValueDto
object InfoboxValueSerializer : JsonContentPolymorphicSerializer<InfoboxValueDto>(InfoboxValueDto::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<InfoboxValueDto> = when {
        element is JsonPrimitive && element.isString -> InfoboxTextDto.serializer()
        element is JsonArray -> InfoboxListDto.serializer()
        else -> throw SerializationException("infobox.value 必须为字符串或键值列表")
    }
}
@Serializable(with = InfoboxEntrySerializer::class)
sealed interface InfoboxEntryDto
@Serializable
data class InfoboxKeyValueDto(val k: String, val v: String) : InfoboxEntryDto
@Serializable
data class InfoboxOnlyValueDto(val v: String) : InfoboxEntryDto
object InfoboxEntrySerializer : JsonContentPolymorphicSerializer<InfoboxEntryDto>(InfoboxEntryDto::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<InfoboxEntryDto> {
        val objectValue = element as? JsonObject ?: throw SerializationException("infobox 条目必须为对象")
        return if ("k" in objectValue) InfoboxKeyValueDto.serializer() else InfoboxOnlyValueDto.serializer()
    }
}
@Serializable
data class ErrorDetailDto(val title: String, val description: String, val details: ErrorDetailsDto? = null)
@Serializable(with = ErrorDetailsSerializer::class)
sealed interface ErrorDetailsDto
@Serializable
@JvmInline
value class ErrorDetailsTextDto(val value: String) : ErrorDetailsDto
@Serializable
data class ErrorDetailsObjectDto(val error: String? = null, val path: String? = null) : ErrorDetailsDto
object ErrorDetailsSerializer : JsonContentPolymorphicSerializer<ErrorDetailsDto>(ErrorDetailsDto::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ErrorDetailsDto> = when {
        element is JsonPrimitive && element.isString -> ErrorDetailsTextDto.serializer()
        element is JsonObject -> ErrorDetailsObjectDto.serializer()
        else -> throw SerializationException("错误 details 必须为字符串或具类型对象")
    }
}
