package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer

/**
 * 全 App 唯一的文字输入框（登录、手动填 Token、发帖与回复、吐槽、进度、举报理由、重命名、主题色……）。除搜索框（[ContainedSearchField]）和
 * 标签 chip 里的内联输入外，页面不直接调用 M3 的 `TextField` / `OutlinedTextField`（门禁 check_text_fields.py）。
 *
 * 就是 M3 filled 输入框（标签在框内、有内容或获得焦点时浮到上方、56dp 高），改了三处，都是我的设计决定（not in M3）：
 * - 四角都是 large 圆角、没有底部指示线，不是 filled 的「上圆下方 + 底线」，表单不显得棱角分明；
 * - 底色 `surfaceContainerHigh`，比 filled 默认的 `surfaceContainerHighest` 低一档：框旁的占位（还没到的验证码图）
 *   画骨架色（即 highest），同色就看不见了；
 * - 放在对话框里（[inDialog]）时底色再深一档（`surfaceContainerHighest`），因为对话框自己就是 `surfaceContainerHigh`，同色框就隐形了；
 * - 获得焦点时底色换成 [raisedContainer]（浅色下近白，深色下是 `surfaceContainerHigh`，不变成一块黑），四周淡入一圈 primary 描边，宽度取 M3 outlined 输入框的聚焦描边
 *   （[OutlinedTextFieldDefaults.FocusedBorderThickness]）；出错时这一圈是 error 色。
 *
 * [label] 在框内浮动，[placeholder] 在框空且获得焦点时显示；[trailingIcon] 放在框尾（密码的显示 / 隐藏）；
 * [supportingText] 在框下（错误原因、字数计数）。[minLines] 大于 1 即多行输入。
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    minLines: Int = 1,
    inDialog: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.formFieldRing(interactionSource, isError),
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = minLines == 1,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = formFieldColors(inDialog),
    )
}

/**
 * [FormTextField] 的 [TextFieldState] 版：正文要按光标插入、包裹标记的编辑器（[state] 上的编辑操作）和要过滤输入的框用它。
 * [lineLimits] 默认单行；[inputTransformation] 在写入前过滤。
 */
@Composable
fun FormTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    inputTransformation: InputTransformation? = null,
    inDialog: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextField(
        state = state,
        modifier = modifier.formFieldRing(interactionSource, isError),
        enabled = enabled,
        textStyle = textStyle,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        inputTransformation = inputTransformation,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = formFieldColors(inDialog),
    )
}

/** 聚焦（或出错）时四周的一圈描边：目标色改透明度淡入，不从 `Color.Transparent`（透明黑）插值。 */
@Composable
private fun Modifier.formFieldRing(interactionSource: MutableInteractionSource, isError: Boolean): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val ring by animateColorAsState(
        targetValue = when {
            isError -> colors.error
            focused -> colors.primary
            else -> colors.primary.copy(alpha = 0f)
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "formTextFieldRing",
    )
    return border(OutlinedTextFieldDefaults.FocusedBorderThickness, ring, MaterialTheme.shapes.large)
}

@Composable
private fun formFieldColors(inDialog: Boolean): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    // 对话框自己的底色就是 surfaceContainerHigh（AlertDialogDefaults.containerColor）：框要再深一档才看得出来
    val resting = if (inDialog) colors.surfaceContainerHighest else colors.surfaceContainerHigh
    return TextFieldDefaults.colors(
        focusedContainerColor = colors.raisedContainer,
        unfocusedContainerColor = resting,
        errorContainerColor = resting,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )
}
