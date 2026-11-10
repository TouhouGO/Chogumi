package io.github.touhougo.chogumi.feature.monodetails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.MonoFact
import io.github.touhougo.chogumi.core.model.MonoFactKind
import io.github.touhougo.chogumi.core.model.MonoPersonRefBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.copyOnLongPress
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.CollectCountButton
import io.github.touhougo.chogumi.core.ui.composables.FactChip
import io.github.touhougo.chogumi.core.ui.composables.FactChipSkeleton
import io.github.touhougo.chogumi.core.ui.composables.HeaderChip
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PortraitImage
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText

/** 立绘 132 × 176（原型 `.portrait`；设计决定，not in M3）。 */
private val PORTRAIT_WIDTH = 132.dp
private val PORTRAIT_HEIGHT = 176.dp

/**
 * 拱形立绘（原型 `.portrait.arch`）：上两角取宽的一半成半圆，下两角是 M3 `CornerLarge`。拱形是设计决定（not in M3）。
 */
private val PortraitShape = ShapeDefaults.Large.copy(
    topStart = CornerSize(PORTRAIT_WIDTH / 2),
    topEnd = CornerSize(PORTRAIT_WIDTH / 2),
)

/** 头部底色渐变的长度（原型 `.mhead{background:linear-gradient(… var(--surface) 300px)}`；设计决定，not in M3）。 */
private val HEADER_WASH_LENGTH = 300.dp

/**
 * 角色 / 人物页头部（原型 `.mhead`，截图 28、30）：`secondaryContainer` → `surface` 的渐变底，拱形立绘 + 右侧一列
 * （名字 / 原名 / 身份行 + 事实 chip 与 CV chip，贴立绘底边）。名字与原名各自长按复制全文（[copyOnLongPress]）。
 *
 * 收藏在顶栏右上角（[MonoTopBarActions]），CV 与「ⓘ 资料」是 chip、并进右列（资料 chip 与作品页头部同一个 [HeaderChip]，点了打开全部资料），
 * 立绘下方不另起操作行——事实、CV、操作三行排在立绘下会留出大片空白。
 *
 * 出场身份（主角 / 配角…）是右列第一枚 chip、排在性别前面；收藏人数在顶栏右上角的收藏按钮上。
 *
 * @param role 角色的出场身份（主角 / 配角 / 闲角…）；null 时不画（人物页恒为 null）。
 * @param identity 身份行的各段（人物的职业，「歌手」「声优」，用 MetaText 连成一行）；null 时不画（角色没有职业）。
 * @param voiceActor 角色的声优；null 时不画 CV chip（人物页恒为 null）。
 * @param onPortraitClick 点立绘（有图时）：全屏看原图，开着与否由屏幕持有。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MonoHeader(
    title: String,
    originalName: String?,
    coverImage: String?,
    role: String?,
    identity: List<String>?,
    facts: List<MonoFact>,
    voiceActor: MonoPersonRefBo?,
    topPadding: Dp,
    onVoiceActorClick: (Int) -> Unit,
    onAllInfoClick: () -> Unit,
    onPortraitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MonoHeaderFrame(
        topPadding = topPadding,
        modifier = modifier,
        portrait = { Portrait(coverImage, title, onClick = onPortraitClick) },
    ) {
        Text(
            text = title,
            modifier = Modifier.copyOnLongPress(title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (originalName != null) {
            Text(
                text = originalName,
                modifier = Modifier.copyOnLongPress(originalName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (identity != null) {
            MetaText(
                parts = identity,
                modifier = Modifier.padding(top = IDENTITY_GAP),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            modifier = Modifier.padding(top = FACTS_GAP),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // CV chip 是 M3 assist chip，外面带 48 的最小触控区，比 32 高的事实 chip 高出一截；
            // 同一行按竖直居中排，可见的 32 高胶囊才对齐（顶对齐的话，事实 chip 比 CV chip 高一截）
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            role?.let { FactChip(text = it, icon = null) }
            // 性别与生日带图标，出身地与事务所不带（原型如此）
            facts.forEach { FactChip(text = it.value, icon = it.kind.iconRes()) }
            if (voiceActor != null) {
                VoiceActorChip(voiceActor, onClick = { onVoiceActorClick(voiceActor.id) })
            }
            HeaderChip(icon = R.drawable.info_24, onClick = onAllInfoClick) {
                Text(stringResource(R.string.detail_info))
            }
        }
    }
}

/**
 * 角色 / 人物页头部的骨架：与 [MonoHeader] 同一个 [MonoHeaderFrame]，
 * 立绘画成同形状的占位，右列是名字一行、身份行与一枚事实 chip 的占位。右列比立绘矮、底对齐，
 * 所以行高就是立绘高；真实右列一般也不超过立绘，内容出现时头部不跳。
 */
@Composable
internal fun MonoHeaderSkeleton(topPadding: Dp, modifier: Modifier = Modifier) {
    MonoHeaderFrame(
        topPadding = topPadding,
        modifier = modifier,
        portrait = { SkeletonBox(Modifier.size(width = PORTRAIT_WIDTH, height = PORTRAIT_HEIGHT), shape = PortraitShape) },
    ) {
        SkeletonText(MaterialTheme.typography.headlineSmallEmphasized, widthFraction = 0.8f)
        SkeletonText(MaterialTheme.typography.labelMedium, Modifier.padding(top = IDENTITY_GAP), widthFraction = 0.6f)
        FactChipSkeleton(Modifier.padding(top = FACTS_GAP))
    }
}

/** 身份行与名字之间、事实 chip 与身份行之间的间距（原型值；设计决定，not in M3）。 */
private val IDENTITY_GAP = 6.dp
private val FACTS_GAP = 12.dp

/**
 * 头部的**框架**——真实头部 [MonoHeader] 与骨架 [MonoHeaderSkeleton] 共用的唯一出处：渐变底、顶栏下方起排、
 * 立绘与右列（间距 20、左右 16、底对齐）。两边只往槽位里填内容。
 */
@Composable
private fun MonoHeaderFrame(
    topPadding: Dp,
    portrait: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    info: @Composable ColumnScope.() -> Unit,
) {
    val wash = MaterialTheme.colorScheme.secondaryContainer
    val surface = MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to wash,
                    1f to surface,
                    endY = with(LocalDensity.current) { (topPadding + HEADER_WASH_LENGTH).toPx() },
                ),
            )
            .padding(top = topPadding + TopAppBarDefaults.TopAppBarExpandedHeight)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        portrait()
        Column(modifier = Modifier.weight(1f), content = info)
    }
}

/**
 * 顶栏右上角的收藏：与目录页同一个 [CollectCountButton]，心形 + 缩写人数；未登录点了去登录。
 * [loaded] 为 false（详情还没到、页面在画骨架）时不画，到了再随正文淡入。
 */
@Composable
internal fun MonoTopBarActions(
    loaded: Boolean,
    isCollected: Boolean,
    collectCount: Int,
    isCollectionLoading: Boolean,
    onToggleCollection: Gated<() -> Unit>,
) {
    CollectCountButton(
        visible = loaded,
        collected = isCollected,
        count = collectCount,
        icon = R.drawable.favorite_24,
        collectedIcon = R.drawable.favorite_filled_24,
        collectLabel = stringResource(R.string.mono_collect),
        onToggle = onToggleCollection,
        loading = isCollectionLoading,
    )
}

/** 拱形立绘：顶对齐裁切（人物头部在画面上部），画法见 [PortraitImage]。有图时点开全屏看原图（[onClick]）。 */
@Composable
private fun Portrait(url: String?, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = PORTRAIT_WIDTH, height = PORTRAIT_HEIGHT)
            .clip(PortraitShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (url != null) Modifier.clickable(onClickLabel = stringResource(R.string.image_open), onClick = onClick) else Modifier),
    ) {
        PortraitImage(url = url, description = description, modifier = Modifier.fillMaxSize())
    }
}


/** 事实种类 → 图标；没有合适图标的种类不画。 */
private fun MonoFactKind.iconRes(): Int? = when (this) {
    MonoFactKind.GENDER -> R.drawable.person_24
    MonoFactKind.BIRTHDAY -> R.drawable.calendar_month_24
    MonoFactKind.BIRTHPLACE, MonoFactKind.AGENCY -> null
}

/**
 * CV chip：M3 assist chip（动作：去声优页；`component-tokens.md` Chips 高 32、前置图 18），
 * 前置位放声优头像，文字「CV 名字」。
 */
@Composable
private fun VoiceActorChip(actor: MonoPersonRefBo, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = stringResource(R.string.detail_character_cv, actor.name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { CircleAvatar(url = actor.avatarUrl, description = null, size = AssistChipDefaults.IconSize) },
    )
}
