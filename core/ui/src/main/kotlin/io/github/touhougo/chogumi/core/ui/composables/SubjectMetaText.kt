package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import io.github.touhougo.chogumi.core.model.SubjectCountUnit
import io.github.touhougo.chogumi.core.model.SubjectMetaPart
import io.github.touhougo.chogumi.core.res.R

/**
 * 条目的元信息行（[io.github.touhougo.chogumi.core.model.subjectMetaParts] 给出的各段）：全 App 唯一一份，详情页头部、
 * 探索货架的焦点卡与榜单行都用它。固定一行，段间分隔见 [MetaText]；放不下时只截资料表那一段
 * （制作公司、出版社…，[SubjectMetaPart.InfoboxValue]），形式、年月、话数总是完整的；资料表那一段截到不足两个字就整段去掉，
 * 再放不下就从第二段起整段整段去掉，不把年月截成半截。
 */
@Composable
fun SubjectMetaText(
    parts: List<SubjectMetaPart>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    MetaText(
        parts = parts.map { subjectMetaPartText(it) },
        modifier = modifier,
        style = style,
        color = color,
        shrinkPart = parts.indexOfFirst { it is SubjectMetaPart.InfoboxValue }.takeIf { it >= 0 },
        droppableFrom = 1,
    )
}

/** 元信息的一段 → 文案，单位与年月的写法随语言。 */
@Composable
private fun subjectMetaPartText(part: SubjectMetaPart): String = when (part) {
    is SubjectMetaPart.Text -> part.value
    is SubjectMetaPart.InfoboxValue -> part.value
    is SubjectMetaPart.TypeName -> stringResource(part.type.labelRes())
    is SubjectMetaPart.Date -> part.value.month
        ?.let { month -> stringResource(R.string.detail_meta_year_month, part.value.year, month) }
        ?: stringResource(R.string.detail_meta_year, part.value.year)
    is SubjectMetaPart.Count -> pluralStringResource(
        when (part.unit) {
            SubjectCountUnit.EPISODE -> R.plurals.detail_episodes_count
            SubjectCountUnit.VOLUME -> R.plurals.detail_meta_volumes
            SubjectCountUnit.TRACK -> R.plurals.detail_meta_tracks
        },
        part.count,
        part.count,
    )
}
