package io.github.touhougo.chogumi.feature.home.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.collectionProgressLabel
import io.github.touhougo.chogumi.feature.home.R as HomeR
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 单部作品这一次画的东西。 */
internal sealed interface SubjectContent {
    data object SignedOut : SubjectContent

    data object LoadFailed : SubjectContent

    /** 还没选（刚放上、选的时候退出了），或选的那部已经不在在看里：点一下重新选。 */
    data object Missing : SubjectContent

    data class Work(val work: WidgetWork, val failed: Boolean, val cover: Bitmap?) : SubjectContent
}

/** 每个单部作品小组件选的是哪部（Glance 给每个小组件的那份状态）。 */
private val SubjectKey = intPreferencesKey("subjectId")

/** 单部作品在一个尺寸下的排法：矮的是一行，宽的是左边大封面，其余是封面铺满整块的海报。 */
internal enum class SubjectLayout { ROW, SIDE, POSTER }

/**
 * [size] 大的单部作品用哪种排法（[SubjectLayout]）：放不下左边大封面那一栏（[SQUARE_MIN_HEIGHT]）就是一行，否则至少
 * [SIDE_MIN_WIDTH] 宽是左边大封面，再窄是海报。一格多高由桌面定：同是 4×1，有的桌面约 60dp，Pixel 桌面约 107dp；
 * 同是 4×2，约 166dp 到 226dp。
 */
internal fun subjectLayoutOf(size: DpSize): SubjectLayout = when {
    size.height < SQUARE_MIN_HEIGHT -> SubjectLayout.ROW
    size.width >= SIDE_MIN_WIDTH -> SubjectLayout.SIDE
    else -> SubjectLayout.POSTER
}

/**
 * 比这矮就是一行的排法：左边大封面那一栏（一行作品名、大数字、进度条、按钮，加上下边距）要这么高。
 * 落在一格（约 60 到 110dp）与两格（约 166dp 起）之间。
 */
private val SQUARE_MIN_HEIGHT = 140.dp

/** 至少这么宽才把封面放在左边，也才在一行的排法里写全「看完第 6 集」（4×2 约 276dp 起，2×2 约 130 到 195dp）。 */
private val SIDE_MIN_WIDTH = 220.dp

/** 左边大封面那一栏的内容区至少这么高，作品名才写两行（否则一行）。 */
private val SIDE_TWO_LINE_HEIGHT = 134.dp

/** 单部作品取的封面：海报要铺满 2×2，按这个尺寸取，其余排法缩小着用。 */
private val SUBJECT_COVER_HEIGHT = 192.dp

/** 这个小组件选的作品在 [works] 里的样子：没选、没登录、读不出来、不在在看里各有各的样子。 */
internal fun subjectContentOf(works: WidgetWorks, subjectId: Int?, failed: Set<Int>, cover: Bitmap?): SubjectContent = when (works) {
    WidgetWorks.SignedOut -> SubjectContent.SignedOut
    is WidgetWorks.Failed -> SubjectContent.LoadFailed
    is WidgetWorks.Ready -> works.works.firstOrNull { it.subjectId == subjectId }
        ?.let { SubjectContent.Work(it, it.subjectId in failed, cover) }
        ?: SubjectContent.Missing
}

/**
 * 单部作品：放上时选一部（[SubjectWidgetPickerActivity]），之后常驻它的进度和一个标下一集的按钮，排法随尺寸（[subjectLayoutOf]）：
 * 4×1 是一行（与在看列表的一行同一个排法），4×2 是左边一张占满高度的封面、右边作品名与大数字，2×2 是封面铺满整块、
 * 作品名与进度压在底部的暗影上、右下角一个圆形 +1。
 */
internal class SubjectWidget : GlanceAppWidget(), KoinComponent {
    private val source: WidgetWorksSource by inject()
    private val actions: WidgetProgressActions by inject()
    private val updates: WidgetUpdates by inject()
    private val preferences: PreferencesRepository by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideLiveContent(
            context,
            updates,
            preferences,
            load = {
                val subjectId = getAppWidgetState<Preferences>(context, id)[SubjectKey]
                val works = source.load()
                val work = (works as? WidgetWorks.Ready)?.works?.firstOrNull { it.subjectId == subjectId }
                val cover = work?.let {
                    coverBitmapsOf(context, listOf(it.coverRef), limit = 1, coverWidthOf(SUBJECT_COVER_HEIGHT), SUBJECT_COVER_HEIGHT)[it.subjectId]
                }
                subjectContentOf(works, subjectId, actions.failed.value, cover)
            },
        ) { SubjectBody(it) }
    }

    @Composable
    private fun SubjectBody(content: SubjectContent) {
        val context = LocalContext.current
        val size = LocalSize.current
        val layout = subjectLayoutOf(size)
        if (content is SubjectContent.Work && layout == SubjectLayout.POSTER) {
            SubjectPoster(content)
            return
        }
        val row = layout == SubjectLayout.ROW
        val vertical = if (row) rowFramePaddingOf(size.height, ROW_PADDING) else FRAME_PADDING
        Column(widgetFrame().padding(horizontal = FRAME_PADDING, vertical = vertical), verticalAlignment = Alignment.CenterVertically) {
            when (content) {
                SubjectContent.SignedOut -> SignInContent()
                SubjectContent.LoadFailed -> MessageContent(context.getString(R.string.widget_load_failed))
                SubjectContent.Missing -> MessageContent(
                    context.getString(R.string.widget_subject_missing),
                    pickSubject(context, LocalGlanceId.current),
                )
                is SubjectContent.Work -> if (row) {
                    val button = if (size.width >= SIDE_MIN_WIDTH) ButtonShape.LABELED else ButtonShape.PLUS_ONE
                    WorkRow(content.work, content.failed, content.cover, min(size.height - vertical * 2, ROW_COVER_MAX), button)
                } else {
                    SubjectSide(content, size.height - vertical * 2, size.width - FRAME_PADDING * 2)
                }
            }
        }
    }

    /** 4×2：左边一张占满高度的封面（最多占一半宽），右边作品名（放得下时两行）、看到第几集的大数字、进度条与「看完第 6 集」。 */
    @Composable
    private fun ColumnScope.SubjectSide(content: SubjectContent.Work, height: Dp, width: Dp) {
        val context = LocalContext.current
        val palette = LocalWidgetPalette.current
        val work = content.work
        val coverHeight = min(height, width / 2 * 4 / 3)
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(work.subjectId, content.cover, coverHeight)
            Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 16.dp)) {
                Text(
                    work.title,
                    style = widgetText(WidgetType.title, palette.onSurface),
                    maxLines = if (height >= SIDE_TWO_LINE_HEIGHT) 2 else 1,
                    modifier = GlanceModifier.fillMaxWidth().clickable(openSubject(context, work.subjectId)),
                )
                Spacer(GlanceModifier.defaultWeight())
                ProgressNumber(work)
                ProgressLine(work, content.failed, sentence = false)
                Spacer(GlanceModifier.height(8.dp))
                ProgressButton(work, ButtonShape.LABELED, GlanceModifier.fillMaxWidth())
            }
        }
    }

    /** 看到第几集的大数字与总集数（总数未知时只写大数字）；进度不是数字时不画。 */
    @Composable
    private fun ProgressNumber(work: WidgetWork) {
        val context = LocalContext.current
        val palette = LocalWidgetPalette.current
        val known = work.progress as? ProgressCount.Known ?: return
        Row(verticalAlignment = Alignment.Bottom) {
            Text(known.current.toString(), style = widgetText(WidgetType.number, palette.onSurface), maxLines = 1)
            if (known.total > 0) {
                Text(
                    context.getString(R.string.widget_progress_total, known.total),
                    style = widgetText(WidgetType.body, palette.onSurfaceVariant),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
    }

    /**
     * 2×2：封面铺满整块（按比例裁），底部压一层暗影，上面是作品名与进度句（白字，写失败时换成「没同步上」），右下角圆形 +1
     * （主色：深浅两色下都比封面亮或深得多，压在图上看得见）。白字与暗影不随配色变（设计决策，not in M3：字压在图上，
     * 图的颜色不由配色定）。没有封面时铺一块空的封面色、字用常规颜色。
     */
    @Composable
    private fun SubjectPoster(content: SubjectContent.Work) {
        val context = LocalContext.current
        val palette = LocalWidgetPalette.current
        val work = content.work
        val cover = content.cover
        val ink = if (cover != null) PosterInk else palette.onSurface
        val subInk = if (cover != null) PosterInk else palette.onSurfaceVariant
        Box(widgetFrame().clickable(openSubject(context, work.subjectId)), contentAlignment = Alignment.BottomStart) {
            if (cover != null) {
                Image(ImageProvider(cover), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Image(
                    ImageProvider(HomeR.drawable.widget_poster_scrim),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxWidth().height(LocalSize.current.height * 2 / 3),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Box(GlanceModifier.fillMaxSize().background(palette.track)) {}
            }
            Row(GlanceModifier.fillMaxWidth().padding(FRAME_PADDING), verticalAlignment = Alignment.Bottom) {
                Column(GlanceModifier.defaultWeight().padding(end = 8.dp)) {
                    Text(work.title, style = widgetText(WidgetType.title, ink), maxLines = 2)
                    val line = if (content.failed) context.getString(R.string.widget_write_failed) else collectionProgressLabel(context.resources, work.progress)
                    if (line != null) {
                        Text(line, style = widgetText(WidgetType.meta, subInk), maxLines = 1, modifier = GlanceModifier.padding(top = 2.dp))
                    }
                }
                ProgressButton(work, ButtonShape.ROUND, statusInk = subInk)
            }
        }
    }

    private companion object {
        val FRAME_PADDING = 12.dp

        /** 一行的排法上下各留的空（框矮时让给这一行，见 [rowFramePaddingOf]）。 */
        val ROW_PADDING = 8.dp

        /** 一行的排法里封面最高多少。 */
        val ROW_COVER_MAX = 64.dp

        /** 海报上压在暗影上的字：白。 */
        val PosterInk = ColorProvider(Color.White)
    }
}

/** 打开选作品页，选给 [glanceId] 这个小组件。 */
private fun pickSubject(context: Context, glanceId: GlanceId): Action {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    return actionStartActivity(
        Intent(context, SubjectWidgetPickerActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 选作品页选好了：记下这个小组件的作品，马上重画。 */
internal suspend fun chooseSubject(context: Context, appWidgetId: Int, subjectId: Int, updates: WidgetUpdates): Outcome<Boolean> = storageOutcomeOf {
    val manager = context.getSystemService(AppWidgetManager::class.java)
    if (manager.getAppWidgetInfo(appWidgetId)?.provider != android.content.ComponentName(context, SubjectWidgetReceiver::class.java)) return@storageOutcomeOf false
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    updateAppWidgetState(context, glanceId) { it[SubjectKey] = subjectId }
    updates.updateNow()
    true
}

class SubjectWidgetReceiver : ChogumiWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SubjectWidget()
}
