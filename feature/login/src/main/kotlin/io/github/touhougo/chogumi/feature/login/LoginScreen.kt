package io.github.touhougo.chogumi.feature.login

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.HeaderTopAppBar
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SecretTextField
import io.github.touhougo.chogumi.core.ui.composables.rememberScrolledFill
import io.github.touhougo.chogumi.core.ui.composables.skeletonFill
import org.koin.compose.viewmodel.koinViewModel

/** 登录主按钮的尺寸：M3 中号按钮（buttons.md「Sizes」，与输入框的 56dp 同高），主操作靠尺寸拉开层级。 */
private val LoginButtonHeight = ButtonDefaults.MediumContainerHeight

/** 表单的最大宽度：宽屏上输入框不横贯整屏（M3 input.md「Placement」）；400dp 是设计决定（not in M3）。 */
private val FormMaxWidth = 400.dp

/** 页面左右留白：设计决定（not in M3）。 */
private val PageMargin = 24.dp

/** 海报在状态栏以下的设计高度（屏幕够高时面板顶边就在这里，矮屏上海报往下缩，见 LoginLayout）：设计决定（not in M3）。 */
private val HeroHeight = 300.dp

/** 面板上边距、面板下边距（不含系统导航条）、表单与备选入口之间至少的空隙、应用名离海报底边：设计决定（not in M3）。 */
private val PanelTopPadding = 28.dp
private val PanelBottomPadding = 24.dp
private val AlternativesGap = 24.dp
private val WordmarkBottomPadding = 24.dp

/** 验证码图卡：与输入框同高（M3 输入框容器 56dp），宽是设计决定（not in M3），图按比例放进去。 */
private val CaptchaHeight = 56.dp
private val CaptchaWidth = 148.dp

/** 验证码图卡右下角「换一张」小圆及其图标：设计决定（not in M3）。 */
private val RefreshBadgeSize = 22.dp
private val RefreshBadgeIconSize = 14.dp

/**
 * 登录页：全 App 唯一的登录界面，是推进当前 tab 的独立页（`Route.Login`），有返回。未登录时首页、进度、「我的」tab 的
 * 「登录」按钮（[SignInPrompt]）和写操作（经 `LoginGate`）都跳到这里。
 *
 * 内容：上方是从屏幕顶边画起的海报（品牌色形状 + 应用名），下方一块圆角面板托着账号密码表单（邮箱、密码、验证码；
 * 一次拿到令牌与网页会话，网页才有的操作都能做）和整行宽的登录按钮，面板底部是两个备选入口：浏览器授权（先说明它做不了
 * 网页才有的操作，确认后再去）、手动粘贴 Access Token。顶栏右侧是网络（[onNetworkClick]：去设置的「网络」页，打不开 bgm.tv 时从这里开直连；
 * 设备不支持直连时为 null、不画）与设置：未登录也能改设置。整页固定一屏，不滚动（[LoginLayout]）。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onNetworkClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LoginScreen(koinViewModel<LoginViewModel>(viewModelStoreOwner = loginViewModelOwner()), onBack, onNetworkClick, onSettingsClick, modifier)
}

/**
 * 登录页 ViewModel 所在的存储：Activity 的，不是所在导航页的。登录页可以同时压在好几个 tab 的栈里，它们是同一个
 * 登录页；放在各自的导航页里，每处各开一次网站登录、各要一张验证码，切个 tab 填了一半的表单就换成另一张图。
 */
@Composable
internal fun loginViewModelOwner(): ViewModelStoreOwner = checkNotNull(LocalActivity.current as? ComponentActivity)

/** [LoginScreen] 拿到全 App 那一份 [viewModel] 之后的整页。 */
@Composable
internal fun LoginScreen(
    viewModel: LoginViewModel,
    onBack: () -> Unit,
    onNetworkClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    modifier: Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf(LoginDialog.NONE) }

    FailureNoticeEffect(viewModel.failures, snackbarHostState, onAction = { if (it === TryDirectRoute) onNetworkClick?.invoke() })

    LoginContent(
        processing = uiState.processing,
        form = uiState.form,
        onFormChange = viewModel::onFormChange,
        captcha = uiState.captcha.map { it.image },
        onCaptchaRefresh = viewModel::onCaptchaRefresh,
        onLogin = viewModel::onPasswordLogin,
        onBrowserLogin = { dialog = LoginDialog.BROWSER },
        onManualToken = { dialog = LoginDialog.MANUAL_TOKEN },
        onBack = onBack,
        onNetworkClick = onNetworkClick,
        onSettingsClick = onSettingsClick,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    when (dialog) {
        LoginDialog.NONE -> Unit
        LoginDialog.BROWSER -> BrowserLoginDialog(
            onConfirm = { viewModel.onBrowserLogin(uriHandler::openUri) },
            onDismiss = { dialog = LoginDialog.NONE },
        )
        LoginDialog.MANUAL_TOKEN -> ManualTokenDialog(
            value = uiState.manualToken,
            onValueChange = viewModel::onManualTokenChange,
            onConfirm = {
                dialog = LoginDialog.NONE
                viewModel.onManualTokenSubmit()
            },
            onDismiss = {
                dialog = LoginDialog.NONE
                viewModel.onManualTokenDismiss()
            },
            enabled = !uiState.processing,
        )
    }
}

/** 登录页上开着的对话框：同一时刻最多一个。 */
private enum class LoginDialog { NONE, BROWSER, MANUAL_TOKEN }

/** [LoginScreen] 的画面本身，不碰 ViewModel：截图用例直接画它。[captcha] 是验证码图（GIF）的字节。 */
@Composable
internal fun LoginContent(
    processing: Boolean,
    form: PasswordForm,
    onFormChange: (PasswordForm) -> Unit,
    captcha: Load<ByteArray>,
    onCaptchaRefresh: () -> Unit,
    onLogin: () -> Unit,
    onBrowserLogin: () -> Unit,
    onManualToken: () -> Unit,
    onBack: () -> Unit,
    onNetworkClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            HeaderTopAppBar(
                title = "",
                fill = rememberScrolledFill(scroll),
                onBack = onBack,
                actions = {
                    if (onNetworkClick != null) {
                        IconButton(onClick = onNetworkClick) {
                            Icon(painterResource(R.drawable.language_filled_24), stringResource(R.string.settings_network))
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(painterResource(R.drawable.settings_filled_24), stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) { padding ->
        // 整页正好一屏（见 LoginLayout）：平时没有可滚的距离，顶栏也就一直透明、不会填上底色盖住海报。
        // 只有键盘弹起、一屏放不下时才滚得动，正在填的那一格才能露出来。
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            // 布局作用域互不透明：LoginLayout 里读不到这里的 maxHeight，先取出来
            val viewport = maxHeight
            LoginLayout(
                viewport = viewport,
                topInset = statusBarHeight,
                bottomInset = padding.calculateBottomPadding(),
                hero = { Hero(topInset = statusBarHeight) },
                wordmark = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.displayMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                form = {
                    FormFields(
                        processing = processing,
                        form = form,
                        onFormChange = onFormChange,
                        captcha = captcha,
                        onCaptchaRefresh = onCaptchaRefresh,
                        onLogin = onLogin,
                    )
                },
                alternatives = {
                    AlternativeButtons(onBrowserLogin = onBrowserLogin, onManualToken = onManualToken, enabled = !processing)
                },
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll, overscrollEffect = null),
            )
        }
    }
}

/**
 * 登录页的整页布局：上面是海报（左下压着应用名），下面一块圆角面板托着表单，备选入口沉在面板底部；合起来正好一屏 [viewport]。
 *
 * - 面板至少是内容的高度：上边距、表单、至少 [AlternativesGap]、备选入口、下边距（含系统导航条 [bottomInset]）；
 * - 海报拿剩下的高度，最高是设计高度（状态栏 + [HeroHeight]），高屏上多出的高度留给面板，备选入口沉底；
 * - 矮屏上海报往下缩，最矮到「状态栏 + 顶栏 + 应用名」：顶栏的返回、设置永远不压在应用名上，海报的形状跟着等比缩小（[Hero]）；
 * - 比这还挤（键盘弹起）时总高才超过一屏，外面的 verticalScroll 才有得滚。
 *
 * 每个子项只量一次：先量表单、备选入口与应用名，算出海报的高度，再量海报与面板底。
 */
@Composable
private fun LoginLayout(
    viewport: Dp,
    topInset: Dp,
    bottomInset: Dp,
    hero: @Composable () -> Unit,
    wordmark: @Composable () -> Unit,
    form: @Composable () -> Unit,
    alternatives: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    val panelColor = MaterialTheme.colorScheme.surface
    Layout(
        contents = listOf(hero, wordmark, { Box(Modifier.background(panelColor, panelShape)) }, form, alternatives),
        modifier = modifier,
    ) { (heroPart, wordmarkPart, panelPart, formPart, alternativesPart), constraints ->
        val width = constraints.maxWidth
        val margin = PageMargin.roundToPx()
        val content = Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0))
        val formPlaceable = formPart.single().measure(content)
        val alternativesPlaceable = alternativesPart.single().measure(content)
        val wordmarkPlaceable = wordmarkPart.single().measure(content)

        val panelTop = PanelTopPadding.roundToPx()
        val panelBottom = (PanelBottomPadding + bottomInset).roundToPx()
        val panelMin = panelTop + formPlaceable.height + AlternativesGap.roundToPx() + alternativesPlaceable.height + panelBottom
        val wordmarkBottom = WordmarkBottomPadding.roundToPx()
        val heroMin = (topInset + TopAppBarDefaults.TopAppBarExpandedHeight).roundToPx() + wordmarkPlaceable.height + wordmarkBottom
        val heroMax = (topInset + HeroHeight).roundToPx().coerceAtLeast(heroMin)
        val viewportPx = viewport.roundToPx()
        val heroHeight = (viewportPx - panelMin).coerceIn(heroMin, heroMax)
        val height = maxOf(viewportPx, heroHeight + panelMin)

        val heroPlaceable = heroPart.single().measure(Constraints.fixed(width, heroHeight))
        val panelPlaceable = panelPart.single().measure(Constraints.fixed(width, height - heroHeight))
        layout(width, height) {
            heroPlaceable.place(0, 0)
            wordmarkPlaceable.place(margin, heroHeight - wordmarkBottom - wordmarkPlaceable.height)
            panelPlaceable.place(0, heroHeight)
            formPlaceable.place((width - formPlaceable.width) / 2, heroHeight + panelTop)
            alternativesPlaceable.place((width - alternativesPlaceable.width) / 2, height - panelBottom - alternativesPlaceable.height)
        }
    }
}

/**
 * 海报：品牌色的几块形状（M3 形状库的 cookie、圆、胶囊、圆角方块，shape.md「decorative UI」）。
 * 形状只是装饰，读屏读不到；横向位置按宽度比例摆，宽屏上跟着散开，出了海报的部分裁掉。
 * 整组按设计高度 [HeroHeight] 贴着海报底边摆；海报被挤矮时（状态栏 [topInset] 那一截不算）整组等比缩小，不会只剩一截。
 * 各块的尺寸、位置、角度都是我的设计决定（not in M3）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(topInset: Dp) {
    val colors = MaterialTheme.colorScheme
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    BoxWithConstraints(Modifier.clipToBounds().clearAndSetSemantics {}) {
        // 布局作用域互不透明：内层读不到这里的 maxWidth / maxHeight，先取出来
        val scale = ((maxHeight - topInset) / HeroHeight).coerceIn(0f, 1f)
        val top = maxHeight - HeroHeight * scale
        val heroWidth = maxWidth
        HeroShape(heroWidth * -0.1f, top + 40.dp * scale, DpSize(200.dp, 200.dp) * scale, colors.primaryContainer, CircleShape)
        HeroShape(heroWidth - 140.dp * scale, top + 24.dp * scale, DpSize(150.dp, 150.dp) * scale, colors.tertiaryContainer, cookie)
        HeroShape(heroWidth * 0.36f, top + 96.dp * scale, DpSize(132.dp, 56.dp) * scale, colors.primary, CircleShape, rotation = -24f)
        HeroShape(
            heroWidth * 0.51f,
            top + 196.dp * scale,
            DpSize(64.dp, 64.dp) * scale,
            colors.secondaryContainer,
            MaterialTheme.shapes.largeIncreased,
            rotation = 18f,
        )
        HeroShape(heroWidth - 60.dp * scale, top + 210.dp * scale, DpSize(24.dp, 24.dp) * scale, colors.primary, CircleShape)
    }
}

/** 海报上的一块形状：左上角在 ([x], [y])，转 [rotation] 度。 */
@Composable
private fun HeroShape(x: Dp, y: Dp, size: DpSize, color: Color, shape: Shape, rotation: Float = 0f) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .rotate(rotation)
            .background(color, shape),
    )
}

/**
 * 面板上的表单：邮箱、密码整行；验证码一行是左边的验证码图（点了换一张）加右边的输入框；下面是整行宽的登录按钮。
 * 宽屏上不超过 [FormMaxWidth]。框之间 12dp、框与按钮 24dp：设计决定（not in M3）。
 */
@Composable
private fun FormFields(
    processing: Boolean,
    form: PasswordForm,
    onFormChange: (PasswordForm) -> Unit,
    captcha: Load<ByteArray>,
    onCaptchaRefresh: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = FormMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PasswordFormFields(
            form = form,
            onFormChange = onFormChange,
            captcha = captcha,
            onCaptchaRefresh = onCaptchaRefresh,
            onDone = onLogin,
            enabled = !processing,
        )
        // M3 中号按钮（56dp，与输入框同高），整行宽
        BusyButton(
            onClick = onLogin,
            enabled = form.complete && captcha is Load.Ready,
            busy = processing,
            busyLabel = stringResource(R.string.loading),
            height = LoginButtonHeight,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Text(stringResource(R.string.login_bangumi))
        }
    }
}

/** 两个备选入口（浏览器授权、Access Token）：描边按钮，居中排一行，放不下时折行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternativeButtons(onBrowserLogin: () -> Unit, onManualToken: () -> Unit, enabled: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AlternativeButton(R.drawable.open_in_new_24, stringResource(R.string.login_browser_entry), onBrowserLogin, enabled)
        AlternativeButton(R.drawable.key_24, stringResource(R.string.manual_token_action), onManualToken, enabled)
    }
}

/** 备选登录入口：M3 描边按钮，带前置图标。 */
@Composable
private fun AlternativeButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

/**
 * 邮箱、密码整行（密码框尾是显示 / 隐藏），验证码一行是验证码图 + 输入框。最后一格按完成即登录。
 */
@Composable
private fun PasswordFormFields(
    form: PasswordForm,
    onFormChange: (PasswordForm) -> Unit,
    captcha: Load<ByteArray>,
    onCaptchaRefresh: () -> Unit,
    onDone: () -> Unit,
    enabled: Boolean,
) {
    FormTextField(
        value = form.email,
        onValueChange = { onFormChange(form.copy(email = it)) },
        label = stringResource(R.string.login_email),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    )
    SecretTextField(
        value = form.password,
        onValueChange = { onFormChange(form.copy(password = it)) },
        label = stringResource(R.string.login_password),
        showLabel = stringResource(R.string.login_password_show),
        hideLabel = stringResource(R.string.login_password_hide),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CaptchaImage(captcha, onRefresh = onCaptchaRefresh, enabled = enabled)
        FormTextField(
            value = form.captcha,
            onValueChange = { onFormChange(form.copy(captcha = it)) },
            label = stringResource(R.string.login_captcha),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 验证码图卡：与输入框同高、同圆角，卡本身是骨架色：图没到时就是同大的骨架；取不到或读不出时卡里是刷新图标。
 * 图在时右下角一个小圆标着「点了换一张」；整卡点了换一张。
 */
@Composable
private fun CaptchaImage(captcha: Load<ByteArray>, onRefresh: () -> Unit, enabled: Boolean) {
    val shape = MaterialTheme.shapes.large
    val bytes = captcha.valueOrNull()
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val description = stringResource(R.string.login_captcha_refresh)
    PressArea(
        onClick = onRefresh.takeIf { enabled && captcha !is Load.Pending },
        shape = shape,
        onClickLabel = description,
        modifier = Modifier.size(width = CaptchaWidth, height = CaptchaHeight).skeletonFill(shape),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.login_captcha),
                        contentScale = ContentScale.Fit,
                        // PressArea 只裁波纹不裁内容；图自带底色，不裁就露出直角
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(RefreshBadgeSize)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.refresh_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(RefreshBadgeIconSize),
                        )
                    }
                }
                captcha !is Load.Pending -> Icon(painterResource(R.drawable.refresh_24), contentDescription = description)
            }
        }
    }
}

/** 浏览器授权登录前的说明：它拿不到网页会话，网页才有的操作做不了；确认后才去浏览器。 */
@Composable
private fun BrowserLoginDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        target = Unit,
        confirmLabel = stringResource(R.string.login_browser_confirm),
        onConfirm = { onConfirm() },
        onDismiss = onDismiss,
        title = stringResource(R.string.login_browser_title),
        text = stringResource(R.string.login_browser_body),
    )
}

@Composable
internal fun ManualTokenDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_token_entry)) },
        text = {
            SecretTextField(
                value = value,
                onValueChange = onValueChange,
                label = stringResource(R.string.manual_token_hint),
                showLabel = stringResource(R.string.manual_token_show),
                hideLabel = stringResource(R.string.manual_token_hide),
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                inDialog = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.manual_token_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
