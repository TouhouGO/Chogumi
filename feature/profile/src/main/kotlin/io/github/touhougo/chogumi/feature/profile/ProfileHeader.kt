package io.github.touhougo.chogumi.feature.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.color.bannerGradient
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.formatIsoDate
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.pressedCorner
import io.github.touhougo.chogumi.core.ui.image.ImageViewer


/**
 * Banner 在状态栏之下的高度（原型 `.banner{height:148px}`，原型的状态栏另算）。
 *
 * 高度与 [FADE_POWER][io.github.touhougo.chogumi.core.ui.color.bannerGradient] 一起决定
 * "消失点落在哪里"：横幅越高、曲线越慢，颜色收干净的位置就越靠下。
 */
private val BANNER_HEIGHT_BELOW_STATUS_BAR = 148.dp


/**
 * 资料块上提压进 banner 的量（原型 `.phead{margin-top:-84px}`）：
 * 头像顶落在 148 − 84 = 64dp，正好在 64dp 顶栏之下。
 */
private val PROFILE_BLOCK_OVERLAP = 84.dp


/** 资料头头像边长 96（真实头像与骨架同一个值；设计决定，not in M3）。 */
private val ProfileAvatarSize = 96.dp


/**
 * 顶部背景 + 资料行。
 *
 * 背景是从用户头像提取的代表色（[accent]，详见 `rememberImageColor`）向下渐隐到 surface。
 * 取色不可用（灰度头像）或未就绪时回退主题色，不会出现空洞或脏灰。
 *
 * 资料块上提 [PROFILE_BLOCK_OVERLAP] 压进 banner（原型 `.phead`）：第一行头像 96dp + 名字 /
 * 「@用户名、yyyy-MM-dd 加入」底对齐、间距 16；签名另起一行占满宽，与第一行相距 12。
 */
@Composable
internal fun ProfileHeader(
    user: UserBo?,
    accent: Color?,
    /** 加入日期 yyyy-MM-dd；未知为 null。 */
    joinedDate: String?,
    /** 点 ID 后面的 ⓘ 打开资料面板；没有任何资料可看时为 null，图标不出现。 */
    onOpenInfo: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val base = accent ?: scheme.primary
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bannerHeight = statusBar + BANNER_HEIGHT_BELOW_STATUS_BAR

    // 横幅本身铺满整宽（渐变是满色块，任何切口都看得见），
    // 资料行才需要左右留白，所以 padding 加在内层而不是外层。
    // 横幅从屏幕顶边画起，顶栏（[ProfileTopBar]）叠在它上面。
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .background(bannerGradient(base, scheme.surface)),
        )
        // 资料块上提压进渐变（原型 `.phead{margin-top:-84px;gap:12px}`）：从横幅底边往上 [PROFILE_BLOCK_OVERLAP] 处起排，
        // 画在 banner 之后，叠在它上面；整体高度 = 横幅高 - 压进的部分 + 资料块高。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = (bannerHeight - PROFILE_BLOCK_OVERLAP).coerceAtLeast(0.dp))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                // 资料未到时头像、昵称、ID 行、签名都画同尺寸骨架（原位替换，不闪现）。
                if (user == null) {
                    SkeletonBox(Modifier.size(ProfileAvatarSize), CircleShape)
                } else {
                    // 点头像全屏看原图
                    val avatarUrl = user.avatarUrl
                    var viewingAvatar by rememberSaveable { mutableStateOf(false) }
                    CircleAvatar(avatarUrl, displayNameOf(user), ProfileAvatarSize, onClick = avatarUrl?.let { { viewingAvatar = true } })
                    if (viewingAvatar && avatarUrl != null) {
                        ImageViewer(listOf(avatarUrl), startIndex = 0, onDismiss = { viewingAvatar = false })
                    }
                }
                // 原型 `.who{padding-bottom:4px}`
                Column(Modifier.weight(1f).padding(start = 16.dp, bottom = 4.dp)) {
                    if (user == null) {
                        SkeletonText(MaterialTheme.typography.headlineSmallEmphasized, widthFraction = 0.5f)
                    } else {
                        Text(
                            text = displayNameOf(user),
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 「@用户名」与「yyyy-MM-dd 加入」各占一行（接在同一行时，窄屏会把「加入」单独挤到下一行）；
                    // 加入日期未知时第二行留空（与骨架两行同高）。ⓘ 紧跟在 ID 后面，点它打开资料面板；
                    // 没有 ⓘ 时同一个按钮隐形、不可点地占着位置，ID 行的高度不随它变。
                    val idStyle = MaterialTheme.typography.bodyMedium
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (user == null) {
                            SkeletonText(idStyle, Modifier.weight(1f), widthFraction = 0.4f)
                        } else {
                            // 用户名太长时截断的是用户名，ⓘ 一直在
                            Text(
                                text = "@${user.username}",
                                style = idStyle,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        ProfileInfoButton(onOpenInfo)
                    }
                    if (user == null) {
                        // 加入日期那一行按一句同样长短的样例排：大字号、窄屏下真实日期会折成两行，骨架跟着折
                        SkeletonText(idStyle, widthFraction = 0.6f, sample = stringResource(R.string.profile_joined, JoinedDateSample))
                    } else if (joinedDate != null) {
                        Text(
                            text = stringResource(R.string.profile_joined, joinedDate),
                            style = idStyle,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        // 日期未知也占着日期那一行（折不折行都与有日期时一样）：照常排一句样例，不可见、读屏读不到
                        Text(
                            text = stringResource(R.string.profile_joined, JoinedDateSample),
                            style = idStyle,
                            modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                        )
                    }
                }
            }
            // 签名属于身份信息，放在资料头里（原型 `.sign.t-body-m`，正文色）。这一行**恒占一行**：骨架一行、签名一行（长了截断）、
            // 没写签名也空着这一行，资料到了下面的数字行、分段与内容都不挪位置。
            if (user == null) {
                SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.8f)
            } else {
                Text(
                    text = user.sign,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    minLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * ID 后面的 ⓘ：M3 extra small 标准图标按钮（component-tokens.md XSmallIconButton：容器 32、图标 20）。版面只占按钮本身，
 * ID 行不被撑成 48dp 高；48dp 触控区由 Compose 对小目标的触控扩展给（同 ReactionBar 的 `ReactionRowButton`）。
 * [onOpenInfo] 为 null（骨架、或没有资料可看）时同一个按钮隐形、不可点、读屏读不到，只占位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileInfoButton(onOpenInfo: (() -> Unit)?) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(
            onClick = onOpenInfo ?: {},
            enabled = onOpenInfo != null,
            modifier = Modifier
                .size(IconButtonDefaults.extraSmallContainerSize())
                .then(if (onOpenInfo == null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
        ) {
            Icon(
                painterResource(R.drawable.info_24),
                contentDescription = onOpenInfo?.let { stringResource(R.string.profile_info) },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
            )
        }
    }
}

/** 骨架里加入日期一行的样例日期：与真实日期同样长短（yyyy-MM-dd）。 */
private const val JoinedDateSample = "2000-01-01"


/** 数字行每一项左右的内边距（原型 `.pstat{padding:0 10px}`）；整行向左收这么多，第一个数字的文字左缘才对齐头像。 */
internal val ProfileNumberLineInset = 10.dp

/**
 * 资料头数字行：「好友 N、日志 N、目录 N」（原型 .statsline）。
 *
 * 数字 [MaterialTheme.typography.titleMediumEmphasized]、名称 bodyMedium；
 * 每项 40dp 高的可点区域（[PressArea]，只裁水波纹）；**按下圆角收小**是本项目设计决定（not in M3）：
 * 常态 20dp（原型 `.pstat{border-radius:20px}`）、按下 8dp，用更小的圆角表达「被按住」。
 *
 * [items] 为 null（资料未到）画一条同字阶的骨架，为空表（全是 0）留一行空白，资料到了只换内容。
 * 一行放得下（常见）时骨架、空行与有内容的行同高；英文标签加四位数、大字号时有内容的行会折成两行，那时资料一到下面的分段会下移一行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileNumberLine(
    items: List<ProfileNumberItem>?,
    onSelect: (ProfileNumberItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 原型 `.pstat{height:40px;border-radius:20px;padding:0 10px;gap:4px}`，项与项之间不另留空。
    // 英文标签加四位数、大字号时一行放不下，换行后左缘仍与头像对齐
    FlowRow(modifier.fillMaxWidth()) {
        when {
            items == null -> NumberLineSlot {
                SkeletonText(MaterialTheme.typography.titleMediumEmphasized, widthFraction = 0.6f)
            }
            // 一行的高度由数字的字阶撑起（与有数字时同高）；空白不念给读屏
            items.isEmpty() -> NumberLineSlot {
                Text("", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.clearAndSetSemantics {})
            }
            else -> items.forEach { item ->
                val label = when (item) {
                    is ProfileNumberItem.FRIENDS -> stringResource(R.string.profile_stats_friend)
                    is ProfileNumberItem.BLOGS -> stringResource(R.string.profile_stats_blog)
                    is ProfileNumberItem.INDEXES -> stringResource(R.string.profile_stats_index)
                }
                val interaction = remember { MutableInteractionSource() }
                val corner by pressedCorner(interaction, resting = 20.dp, pressed = 8.dp)
                PressArea(onClick = { onSelect(item) }, shape = RoundedCornerShape(corner), interactionSource = interaction) {
                    NumberLineSlot {
                        Row {
                            Text(
                                "${item.count}",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp).alignByBaseline(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 数字行的一格：至少 40dp 高、左右各 [ProfileNumberLineInset]，内容靠左居中。骨架、空行与每一项共用。 */
@Composable
private fun NumberLineSlot(content: @Composable () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .padding(horizontal = ProfileNumberLineInset),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}


/**
 * 主页分区：连通按钮组，图标 + 文字（原型 `.tabsbar .cbg`），上 4 下 4
 * （原型 `.tabsbar{padding:16px 0 4px}` 减去列表项间距 12）。选中时换实心图标。
 *
 * [tabs] 由 [profileTabsFor] 决定（专注模式少了「好友」），本组件不自行筛除。
 */
@Composable
internal fun ProfileTabGroup(
    tabs: List<ProfileTab>,
    selected: ProfileTab,
    modifier: Modifier = Modifier,
    onSelect: (ProfileTab) -> Unit,
) {
    ConnectedChoiceGroup(
        options = tabs,
        selected = selected,
        label = { stringResource(it.labelRes) },
        onSelect = onSelect,
        icon = { tab, checked -> if (checked) tab.selectedIconRes else tab.iconRes },
        modifier = modifier.padding(vertical = 4.dp),
    )
}


/** 资料面板里的一行：图标、小标签、值；[link] 非空时整行可点打开它。 */
internal data class ProfileInfoRow(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val value: String,
    val link: String? = null,
)


/** ⓘ 面板里有什么：简介（BBCode 原文，没写为 null）与资料行。 */
internal data class ProfileInfo(val bio: String?, val rows: List<ProfileInfoRow>) {
    /** 什么都没有：ⓘ 不出现。 */
    val isEmpty: Boolean get() = bio == null && rows.isEmpty()
}

/**
 * ⓘ 面板有什么：**唯一判定**（ⓘ 图标出不出现与面板内容都读它）。
 * 简介只在写了时给；资料行只给确实有值的——Bangumi 的 `site` / `location` 常常为空。
 */
internal fun profileInfoOf(user: UserBo): ProfileInfo = ProfileInfo(
    bio = user.bio.takeIf { it.isNotBlank() },
    rows = buildList {
        if (user.joinedAt > 0) add(ProfileInfoRow(R.drawable.calendar_month_24, R.string.profile_joined_at, formatIsoDate(user.joinedAt)))
        if (user.location.isNotBlank()) add(ProfileInfoRow(R.drawable.home_24, R.string.profile_location, user.location))
        if (user.site.isNotBlank()) add(ProfileInfoRow(R.drawable.link_24, R.string.profile_site, user.site, link = user.site))
    },
)


/**
 * ⓘ 面板：简介在上（[ProfileBio]），资料行在下（原型 `.seglist` 的分段列表，几何见 [SegmentedItem]）。
 * 简介可能很长：面板高度在打开时定下（[PageModalSheet]），放不下的在面板里滚。
 * 简介与分段列表相距 12dp：我的设计决定（not in M3），与资料头各块的间距一致。
 */
@Composable
internal fun ProfileInfoSheet(user: UserBo, onUserClick: (String) -> Unit, onDismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    val info = remember(user) { profileInfoOf(user) }
    val rows = info.rows
    PageModalSheet(onDismiss = onDismiss, fullHeight = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SheetTitleRow(stringResource(R.string.profile_info))
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                info.bio?.let { bio ->
                    ProfileBio(ast = remember(bio) { BBCodeParser.parse(bio) }, onUserClick = onUserClick)
                }
                if (rows.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        rows.forEachIndexed { index, row ->
                            SegmentedItem(
                                index = index,
                                count = rows.size,
                                onClick = row.link?.let { link -> { browser.openUri(link) } },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(row.iconRes),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            ) {
                                Column {
                                    Text(
                                        stringResource(row.labelRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(row.value, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 资料头数字行：**哪些项出现**的唯一判定。
 *
 * 「好友 N、日志 N、目录 N」：为 0 的项不出现；全为 0 整行不出现（结果为空表）；
 * 专注模式不显示「好友」（好友属社区，与 profileTabsFor 隐藏 FRIENDS 同一条规则）。
 * 目录数 = 创建的 + 收藏的（`UserBo.indexCreateCount` / `indexCollectCount`：两个目录列表的 total，与目录页页签同一来源）。
 */
internal fun profileNumberLineOf(user: UserBo, community: Boolean): List<ProfileNumberItem> = buildList {
    if (community && user.friendCount > 0) add(ProfileNumberItem.FRIENDS(user.friendCount))
    if (user.blogCount > 0) add(ProfileNumberItem.BLOGS(user.blogCount))
    val indexes = ProfileNumberItem.INDEXES(IndexListCounts(created = user.indexCreateCount, collected = user.indexCollectCount))
    if (indexes.count > 0) add(indexes)
}


/** 数字行的一项：数与去向。 */
internal sealed interface ProfileNumberItem {
    val count: Int

    /** 好友 → 切到好友 tab（仅自己主页有该 tab；他人主页数字行的好友项去 tab 的语义由调用方定）。 */
    data class FRIENDS(override val count: Int) : ProfileNumberItem

    /** 日志 → 日志列表页。 */
    data class BLOGS(override val count: Int) : ProfileNumberItem

    /** 目录 → 目录列表页；数 = 创建的 + 收藏的，两页各自的数随去向带过去。 */
    data class INDEXES(val counts: IndexListCounts) : ProfileNumberItem {
        override val count: Int get() = counts.created + counts.collected
    }
}
