package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.touhougo.chogumi.core.res.R

/**
 * 可移除的条件 chip（唯一实现）：筛选面板「其他已选」的每一条。
 *
 * 用 input chip：代表用户输入的一条离散信息，trailing 图标必须能移除它（input.md:387、:403）；这里点整颗即移除。
 * 整颗 chip 是一个可聚焦元素，朗读「移除 [spoken]」；trailing 的关闭图标只是视觉提示，contentDescription 为 null，不单独朗读。
 */
@Composable
internal fun RemovableChip(
    label: String,
    spoken: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removeText = stringResource(R.string.search_remove_condition, spoken)
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.close_24),
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
        modifier = modifier.semantics { contentDescription = removeText },
    )
}
