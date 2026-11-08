package io.github.touhougo.chogumi.shell

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign

/**
 * 底栏与导航轨上目的地的名字：整个显示，不截断、不缩字号。
 *
 * From M3（navigation.md「Navigation bar」/「Navigation rail」）：「Don't truncate or display an ellipsis」「Don't reduce
 * the type size」；放大字号时导航项竖向长高、标签可以折行，2 倍字号以内整个标签都要看得见；放不下的长词断字。
 * 所以不限行数，按断字规则折行（英文的「Community」在 380dp、1.1 倍字号下折成「Commu-nity」），居中对齐两行。
 * 字号、字重仍由导航项给的标签样式定（选中与未选中不同）。
 */
@Composable
internal fun TopLevelLabel(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = LocalTextStyle.current.merge(TextStyle(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)),
    )
}
