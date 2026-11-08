package io.github.touhougo.chogumi.core.network.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** PATCH 存在性与内容分离；T 本身可空时，Value(null) 表示显式清空。 */
@kotlinx.serialization.Serializable(with = PatchFieldSerializer::class)
sealed interface PatchField<out T> {
    data object Absent : PatchField<Nothing>
    data class Value<T>(val value: T) : PatchField<T>
}

class PatchFieldSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<PatchField<T>> {
    override val descriptor: SerialDescriptor = valueSerializer.descriptor
    override fun deserialize(decoder: Decoder): PatchField<T> = PatchField.Value(valueSerializer.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: PatchField<T>) {
        when (value) {
            PatchField.Absent -> throw SerializationException("未提供的 PATCH 字段必须省略")
            is PatchField.Value -> valueSerializer.serialize(encoder, value.value)
        }
    }
}
