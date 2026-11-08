package io.github.touhougo.chogumi.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 关于页：版本、版权所有者、GPL-3.0 声明、源码、隐私说明与作者主页地址，随包分发的第三方库与素材、运行时取用的第三方数据，
 * 以及许可原文。GPL-3.0 第 5 条要求有交互界面的程序显示版权、无担保声明和查看许可的方式，这里是那个界面。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { versionNameOf(context) }
    var openedLicense by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    PageScaffold(title = stringResource(R.string.about), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag(AboutListTag),
            contentPadding = PaddingValues(
                start = ChogumiDimens.ScreenPadding,
                end = ChogumiDimens.ScreenPadding,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_copyright),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_legal, stringResource(R.string.app_name)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item { LinkRow(0, 3, stringResource(R.string.about_source)) { uriHandler.openUri(SourceRepositoryUrl) } }
            item { LinkRow(1, 3, stringResource(R.string.about_privacy)) { uriHandler.openUri(PrivacyNoticeUrl) } }
            item { LinkRow(2, 3, stringResource(R.string.about_author)) { uriHandler.openUri(OwnerHomepageUrl) } }
            item { SectionHeader(stringResource(R.string.about_license_texts)) }
            val texts = LicenseKind.entries.filter { it.asset != null }
            itemsIndexed(texts, key = { _, kind -> kind.name }) { index, kind ->
                LinkRow(index, texts.size, kind.label) { openedLicense = kind.name }
            }
            thirdPartyNotices(R.string.about_libraries, BundledThirdPartyNotices, context, uriHandler)
            thirdPartyNotices(R.string.about_runtime_data, RuntimeDataNotices, context, uriHandler)
        }
    }

    openedLicense?.let { name ->
        val kind = LicenseKind.valueOf(name)
        ScrollableModalSheet(onDismiss = { openedLicense = null }, contentKey = name) {
            SheetTitleRow(kind.label)
            val text = remember(kind) { licenseText(context, kind) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ChogumiDimens.ScreenPadding)
                    .padding(bottom = 32.dp),
            ) {
                Text(text = text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal const val AboutListTag = "about-list"

/** 一节第三方清单：标题行加一段分段列表，每行名称与许可，点开来源。 */
private fun LazyListScope.thirdPartyNotices(@StringRes titleRes: Int, notices: List<ThirdPartyNotice>, context: Context, uriHandler: UriHandler) {
    item { SectionHeader(stringResource(titleRes)) }
    itemsIndexed(notices, key = { _, notice -> notice.id }) { index, notice ->
        SegmentedItem(
            index = index,
            count = notices.size,
            onClick = { uriHandler.openUri(notice.url) },
            modifier = Modifier.fillMaxWidth(),
            supportingContent = { Text(notice.license.label(context)) },
        ) { Text(stringResource(notice.nameRes)) }
    }
}

/** 安装包里的版本名；读不到时（不会发生）显示空串，不抛。 */
private fun versionNameOf(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

/** assets/licenses/ 下的许可原文，按段落重排（见 [unwrapParagraphs]）。 */
internal fun licenseText(context: Context, kind: LicenseKind): String =
    unwrapParagraphs(context.assets.open("licenses/${checkNotNull(kind.asset)}").bufferedReader().use { it.readText() })
