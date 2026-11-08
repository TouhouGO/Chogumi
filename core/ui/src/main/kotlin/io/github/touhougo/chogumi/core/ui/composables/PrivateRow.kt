package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R

/**
 * 面板里的「仅自己可见」开关（收藏面板的私密收藏、目录面板的私密目录共用）：一行列表项，整行可点，开关只是显示；
 * 底色透明，跟着所在面板。
 */
@Composable
fun PrivateRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        leadingContent = { Icon(painterResource(R.drawable.visibility_off_24), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f)),
    ) {
        Text(stringResource(R.string.private_collection))
    }
}
