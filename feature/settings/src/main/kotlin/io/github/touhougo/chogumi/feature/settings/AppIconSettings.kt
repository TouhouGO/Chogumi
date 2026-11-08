package io.github.touhougo.chogumi.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.AppIcon
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem

/** 「应用图标」一行：五个图标排成一排，点哪个桌面就换成哪个。 */
@Composable
internal fun AppIconRow(index: Int, count: Int, selected: AppIcon, onSelect: (AppIcon) -> Unit) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        details = {
            Row(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon.entries.forEach { icon ->
                    AppIconTile(icon, icon == selected, onClick = { onSelect(icon) }, modifier = Modifier.weight(1f, fill = false))
                }
            }
        },
    ) { Text(stringResource(R.string.app_icon)) }
}

/**
 * 一个图标：画启动图标前景层中间可见的 72dp（整层 108dp 的中间三分之二），裁成圆；选中的外面一圈主色描边。
 * 一排放不下时每个跟着变小，最大 [TileMaxSize]。
 */
@Composable
private fun AppIconTile(icon: AppIcon, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spec = appIconSpecOf(icon)
    val bitmap = ImageBitmap.imageResource(spec.preview)
    val painter = remember(bitmap) {
        BitmapPainter(
            image = bitmap,
            srcOffset = IntOffset(bitmap.width / 6, bitmap.height / 6),
            srcSize = IntSize(bitmap.width * 2 / 3, bitmap.height * 2 / 3),
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val ring by animateColorAsState(
        targetValue = if (selected) primary else primary.copy(alpha = 0f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "appIconRing",
    )
    Image(
        painter = painter,
        contentDescription = stringResource(spec.label),
        modifier = modifier
            .widthIn(max = TileMaxSize)
            .aspectRatio(1f)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(2.dp, ring, CircleShape)
            .padding(4.dp)
            .clip(CircleShape),
    )
}

private val TileMaxSize = 56.dp
