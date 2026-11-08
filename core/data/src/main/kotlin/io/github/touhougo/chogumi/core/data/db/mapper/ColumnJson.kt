package io.github.touhougo.chogumi.core.data.db.mapper

import io.github.touhougo.chogumi.core.model.SubjectInfoboxBo
import io.github.touhougo.chogumi.core.model.TagBo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// 本地表里存成 JSON 文本的列表列（标签、资料、评分各档人数、条目 id）：编码、解码与容错只在这一处。
// 读不懂的旧行按空列表处理，不让一行坏数据拖垮整页。

private val json = Json { ignoreUnknownKeys = true }

private val stringListSerializer = ListSerializer(String.serializer())

private val tagListSerializer = ListSerializer(TagBo.serializer())

private val infoboxListSerializer = ListSerializer(SubjectInfoboxBo.serializer())

private val intListSerializer = ListSerializer(Int.serializer())

internal fun List<String>.toJson() = json.encodeToString(stringListSerializer, this)

/** 泛型擦除导致与 List&lt;String&gt;.toJson() 的 JVM 签名冲突，用 @JvmName 消歧 */
@JvmName("tagsToJson")
internal fun List<TagBo>.toJson() = json.encodeToString(tagListSerializer, this)

/** 详情资料单独使用 JSON 列，避免将多态网络 DTO 传入 Room。 */
@JvmName("infoboxToJson")
internal fun List<SubjectInfoboxBo>.toJson() = json.encodeToString(infoboxListSerializer, this)

/** 整数列表列：评分各档人数与搜索第一页的条目 id 共用同一种 JSON 编解码。 */
@JvmName("intsToJson")
internal fun List<Int>.toJson() = json.encodeToString(intListSerializer, this)

internal fun String.toStringList() =
    runCatching { json.decodeFromString(stringListSerializer, this) }.getOrDefault(emptyList())

internal fun String.toTagList() =
    runCatching { json.decodeFromString(tagListSerializer, this) }.getOrDefault(emptyList())

internal fun String.toInfoboxList() =
    runCatching { json.decodeFromString(infoboxListSerializer, this) }.getOrDefault(emptyList())

internal fun String.toIntList() =
    runCatching { json.decodeFromString(intListSerializer, this) }.getOrDefault(emptyList())
