package io.github.touhougo.chogumi.feature.home.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.touhougo.chogumi.core.data.repository.DisplayScaleRepository
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.collectionProgressLabel
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.github.touhougo.chogumi.core.ui.theme.darkThemeOf
import io.github.touhougo.chogumi.core.ui.theme.displayScaleOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 单部作品小组件的选作品页：放上时（以及 Android 12 起长按「重新配置」、选的那部不在在看里时）打开，列出在看的作品
 * （与在看列表同一份、同一个顺序），点一部就记给这个小组件。退出不选，系统就不放这个小组件。
 */
class SubjectWidgetPickerActivity : ComponentActivity() {
    private val source: WidgetWorksSource by inject()
    private val preferences: PreferencesRepository by inject()
    private val updates: WidgetUpdates by inject()

    private val displayScale: DisplayScaleRepository by inject()

    /** 界面大小与 App 一致（AppActivity 同一个覆盖）：选作品页开着的这一会儿不跟着改，下次打开就是新的。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        newBase.displayScaleOverride(displayScale.percent.value)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultOf(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            // 配色与 App 一致：读到之前只露窗口底色（App 的窗口主题），不先按默认配色画一帧再换；偏好读不出来时用默认配色
            val theme by produceState<Pair<ThemePreferences, ThemeMode>?>(null) {
                value = (outcomeOf { preferences.themePreferences.first() }.valueOrNull() ?: ThemePreferences()) to
                    (outcomeOf { preferences.themeMode.first() }.valueOrNull() ?: ThemeMode.FOLLOW_SYSTEM)
            }
            val works by produceState<WidgetWorks?>(null) { value = source.load() }
            val (themePreferences, themeMode) = theme ?: return@setContent
            ChogumiTheme(darkTheme = darkThemeOf(themeMode), themePreferences = themePreferences) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    PickerScreen(works, onBack = ::finish, onPick = { subjectId -> pick(appWidgetId, subjectId) })
                }
            }
        }
    }

    private fun pick(appWidgetId: Int, subjectId: Int) {
        lifecycleScope.launch {
            chooseSubject(applicationContext, appWidgetId, subjectId, updates)
            setResult(RESULT_OK, resultOf(appWidgetId))
            finish()
        }
    }

    private fun resultOf(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/** 选作品页：读到之前画几行同框架的骨架（没有首页缓存时要等网络）。 */
@Composable
private fun PickerScreen(works: WidgetWorks?, onBack: () -> Unit, onPick: (Int) -> Unit) {
    PageScaffold(title = stringResource(R.string.widget_picker_title), onBack = onBack) { padding ->
        when (works) {
            null -> Column(Modifier.padding(padding)) { repeat(PICKER_SKELETON_ROWS) { PickerRowSkeleton() } }
            WidgetWorks.SignedOut -> EmptyLine(stringResource(R.string.widget_sign_in_prompt), Modifier.padding(padding))
            is WidgetWorks.Failed -> EmptyLine(stringResource(R.string.widget_load_failed), Modifier.padding(padding))
            is WidgetWorks.Ready -> if (works.works.isEmpty()) {
                EmptyLine(stringResource(R.string.widget_empty), Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    items(works.works, key = { it.subjectId }) { work -> PickerRow(work, onClick = { onPick(work.subjectId) }) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(work: WidgetWork, onClick: () -> Unit) {
    PressArea(onClick = onClick, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
        PickerRowFrame(
            poster = { SubjectPoster(work.coverUrl, it) },
            title = { style -> Text(work.title, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            progress = { style ->
                collectionProgressLabel(work.progress)?.let { label ->
                    Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            },
        )
    }
}

/** 选作品页一行的骨架：与 [PickerRow] 同一个框架，标题按一行占。 */
@Composable
private fun PickerRowSkeleton() {
    PickerRowFrame(
        poster = { SubjectPosterSkeletonBox(it) },
        title = { style -> SkeletonText(style, widthFraction = 0.8f) },
        progress = { style -> SkeletonText(style, widthFraction = 0.4f) },
    )
}

/** 选作品页一行的排法（真行与骨架共用）：封面、标题、进度句。 */
@Composable
private fun PickerRowFrame(
    poster: @Composable (Modifier) -> Unit,
    title: @Composable (TextStyle) -> Unit,
    progress: @Composable (TextStyle) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        poster(Modifier.width(PickerPosterWidth))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title(MaterialTheme.typography.bodyLarge)
            progress(MaterialTheme.typography.bodySmall)
        }
    }
}

/** 读到之前画几行骨架：一屏的上半截，读到时多半不止这几部。 */
private const val PICKER_SKELETON_ROWS = 4

/** 选作品页的封面宽（M3 列表项的大图 56dp）。 */
private val PickerPosterWidth = 56.dp
