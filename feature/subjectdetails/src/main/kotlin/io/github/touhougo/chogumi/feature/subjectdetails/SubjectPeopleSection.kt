package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.StaffPositionRow
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.staffPositionRows
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.composables.AvatarRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.FactRowItem
import io.github.touhougo.chogumi.core.ui.composables.ListImageAvatarSize
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.labelRes

/** 人物 tab 的两组：角色、制作人员。 */
internal enum class PeopleGroup(@StringRes val titleRes: Int) {
    CHARACTERS(R.string.characters),
    STAFF(R.string.detail_staff),
}

/** 人物 tab 列表项的 testTag：角色项、职位行与骨架行共用。 */
internal const val DetailsPeopleItemTag = "details_people_item"

/** 每个职位行露出几个人，多的收成「+N」（设计稿）。 */
private const val STAFF_CHIPS_PER_ROW = 4

/** 人物 tab 列表骨架画几行（设计决定，not in M3）。 */
private const val PEOPLE_SKELETON_ROWS = 4

/**
 * 人物 tab 一组先露多少行（角色一行一人、制作人员一行一个职位），多的点「展开全部」再往下接（设计决定，not in M3）。
 * 20 行在手机上约两屏，够看主要角色；航海王有一千多个角色，一次全排进同一个列表项的话，切到人物 tab 会卡住、来回切几次内存耗尽闪退。
 */
internal const val PEOPLE_PREVIEW_ROWS = 20

/** 当前露出哪一组：[selected] 不可选（该组已确认为空）时回落到第一个可选组；两组都空时为 null。 */
private fun currentPeopleGroup(
    selected: PeopleGroup,
    characters: AsyncList<RelatedCharacterBo>,
    persons: AsyncList<RelatedPersonBo>,
    otherStaffFacts: List<FactRow>,
): Pair<List<PeopleGroup>, PeopleGroup?> {
    val groups = PeopleGroup.entries.filter { group ->
        group.list(characters, persons).state != SectionState.EMPTY ||
            (group == PeopleGroup.STAFF && otherStaffFacts.isNotEmpty())
    }
    return groups to (if (selected in groups) selected else groups.firstOrNull())
}

/**
 * 人物 tab 的列表（逐行懒排）：前 [PEOPLE_PREVIEW_ROWS] 行连同 chip 与「展开全部」在一个列表项里；
 * 展开后其余各行**逐项**接在后面，只排屏幕上看得见的那几行。
 */
internal fun LazyListScope.peopleSection(
    characters: AsyncList<RelatedCharacterBo>,
    persons: AsyncList<RelatedPersonBo>,
    staffRows: List<StaffPositionRow>,
    otherStaffFacts: List<FactRow>,
    selected: PeopleGroup,
    onSelect: (PeopleGroup) -> Unit,
    showAll: Set<PeopleGroup>,
    onShowAll: (PeopleGroup) -> Unit,
    openPositions: Set<String>,
    onTogglePosition: (String) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
) {
    val current = currentPeopleGroup(selected, characters, persons, otherStaffFacts).second
    val rowCount = when (current) {
        PeopleGroup.CHARACTERS -> characters.items.size
        PeopleGroup.STAFF -> staffRows.size
        null -> 0
    }
    val expanded = current != null && current in showAll && current.list(characters, persons).state == SectionState.CONTENT
    item(key = "section-people") {
        SubjectPeopleSection(
            characters = characters,
            persons = persons,
            staffRows = staffRows,
            otherStaffFacts = otherStaffFacts,
            selected = selected,
            onSelect = onSelect,
            showAll = expanded,
            onShowAll = { current?.let(onShowAll) },
            openPositions = openPositions,
            onTogglePosition = onTogglePosition,
            onCharacterClick = onCharacterClick,
            onPersonClick = onPersonClick,
        )
    }
    if (!expanded || rowCount <= PEOPLE_PREVIEW_ROWS) return
    val rowModifier = Modifier.padding(start = 16.dp, end = 16.dp, top = ListItemDefaults.SegmentedGap)
    when (current) {
        PeopleGroup.CHARACTERS -> items(rowCount - PEOPLE_PREVIEW_ROWS, key = { "people-character-${it + PEOPLE_PREVIEW_ROWS}" }) { i ->
            val index = i + PEOPLE_PREVIEW_ROWS
            CharacterRow(characters.items[index], index, rowCount, onCharacterClick, rowModifier)
        }
        PeopleGroup.STAFF -> {
            items(rowCount - PEOPLE_PREVIEW_ROWS, key = { "people-staff-${it + PEOPLE_PREVIEW_ROWS}" }) { i ->
                val index = i + PEOPLE_PREVIEW_ROWS
                StaffRow(staffRows[index], index, rowCount, staffRows[index].position in openPositions, onTogglePosition, onPersonClick, rowModifier)
            }
            if (otherStaffFacts.isNotEmpty()) {
                item(key = "people-other-staff") { OtherStaffFacts(otherStaffFacts, Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}

/**
 * 人物 tab（原型 `tabBody` people）的首块：上面「角色 N」「制作人员 N」两枚 [SelectableChip]（只有一种时只画一枚；切换动画同全仓 chip），
 * 下面是所选一组的前 [PEOPLE_PREVIEW_ROWS] 行，多的给「展开全部」（展开后其余行由 [peopleSection] 逐项接在后面）。
 *
 * - 角色：分段列表，每项「头像 + 名字 + 主角、CV 名字 + ›」；
 * - 制作人员：每职位一行（[staffRows]，详情页用 [staffPositionRows] 算好一次传下来：主创按规格表在前），右侧人名 chip 流，每行露 [STAFF_CHIPS_PER_ROW] 个，
 *   「+N」原地展开该职位全部，展开后变「收起」。
 *
 * 制作人员末尾追加已分类的 [otherStaffFacts]，保留原始键值，不可点击；只有这些行时仍可选择制作人员组。
 * 两组都还在加载时画骨架；某组失败时该组 chip 仍在，选中它看到错误与重试。
 * [selected] 由详情页持有（概览「全部 ›」直接跳到对应一组），不可选时回落到第一个可选组。
 */
@Composable
internal fun SubjectPeopleSection(
    characters: AsyncList<RelatedCharacterBo>,
    persons: AsyncList<RelatedPersonBo>,
    staffRows: List<StaffPositionRow>,
    selected: PeopleGroup,
    onSelect: (PeopleGroup) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    otherStaffFacts: List<FactRow> = emptyList(),
    showAll: Boolean = false,
    onShowAll: () -> Unit = {},
    openPositions: Set<String> = emptySet(),
    onTogglePosition: (String) -> Unit = {},
) {
    val (groups, current) = currentPeopleGroup(selected, characters, persons, otherStaffFacts)
    Column(modifier = modifier.fillMaxWidth()) {
        if (current == null) return@Column
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { group ->
                val count = group.list(characters, persons).items.size
                val title = stringResource(group.titleRes)
                SelectableChip(
                    label = if (count > 0) stringResource(R.string.label_with_count, title, count) else title,
                    selected = group == current,
                    onClick = { onSelect(group) },
                )
            }
        }
        val list = current.list(characters, persons)
        val rowCount = if (current == PeopleGroup.CHARACTERS) characters.items.size else staffRows.size
        // 展开后其余行在列表里接着排，「其他制作人员」也随之挪到最后
        val restFollows = showAll && rowCount > PEOPLE_PREVIEW_ROWS
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ListSection(
                title = null,
                state = list.state,
                error = list.error,
                onRetry = list.onRetry,
                skeleton = { PeopleListSkeleton() },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        repeat(minOf(rowCount, PEOPLE_PREVIEW_ROWS)) { index ->
                            when (current) {
                                PeopleGroup.CHARACTERS -> CharacterRow(characters.items[index], index, rowCount, onCharacterClick)
                                PeopleGroup.STAFF -> StaffRow(
                                    staffRows[index], index, rowCount, staffRows[index].position in openPositions, onTogglePosition, onPersonClick,
                                )
                            }
                        }
                    }
                    if (!showAll && rowCount > PEOPLE_PREVIEW_ROWS) {
                        TextButton(onClick = onShowAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.detail_people_show_all, rowCount))
                        }
                    }
                }
            }
            // 关联列表的加载/错误不隐藏已经获得的 infobox 原文。
            if (current == PeopleGroup.STAFF && otherStaffFacts.isNotEmpty() && !restFollows) {
                OtherStaffFacts(otherStaffFacts)
            }
        }
    }
}

private fun PeopleGroup.list(
    characters: AsyncList<RelatedCharacterBo>,
    persons: AsyncList<RelatedPersonBo>,
): AsyncList<*> = when (this) {
    PeopleGroup.CHARACTERS -> characters
    PeopleGroup.STAFF -> persons
}

/** 「其他制作人员」：infobox 里已分类、但不在关联人物接口里的键值，原样列出，不可点击。 */
@Composable
private fun OtherStaffFacts(facts: List<FactRow>, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader(title = stringResource(R.string.detail_other_staff))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            facts.forEachIndexed { index, row ->
                FactRowItem(row = row, index = index, count = facts.size)
            }
        }
    }
}

/** 角色分段列表的一项（原型 `.slist`）：外圆角 16、项圆角 4、项间距 2，走全仓唯一的 [SegmentedItem]。 */
@Composable
private fun CharacterRow(
    character: RelatedCharacterBo,
    index: Int,
    count: Int,
    onCharacterClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = displayNameOf(character)
    val captionParts = listOfNotNull(
        character.role?.let { stringResource(it.labelRes()) },
        character.actors.firstOrNull()?.let { stringResource(R.string.detail_character_cv, it.name) },
    )
    SegmentedItem(
        index = index,
        count = count,
        onClick = { onCharacterClick(character.id) },
        modifier = modifier
            .testTag(DetailsPeopleItemTag)
            .fillMaxWidth(),
        leadingContent = { CircleAvatar(character.images?.avatarUrl(), null, ListImageAvatarSize) },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.chevron_right_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // MetaText 不保证是单个 Text（要放进宽度时先量宽再画），不能进 M3 副行槽位（见 SegmentedItem 的说明），走 details
        details = captionParts.takeIf { it.isNotEmpty() }?.let { parts ->
            { MetaText(parts = parts, style = MaterialTheme.typography.bodySmall) }
        },
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 制作人员的一个职位行（原型 `.credits .crow`）：左列职位名（宽 72、`labelMedium` 弱化），右列人名 chip 流。
 * 行用同一个分段列表项承载（圆角、间距、底色与角色列表一致）。[open] 时露出该职位全部人，否则 [STAFF_CHIPS_PER_ROW] 个 +「+N」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StaffRow(
    row: StaffPositionRow,
    index: Int,
    count: Int,
    open: Boolean,
    onTogglePosition: (String) -> Unit,
    onPersonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = if (open) row.persons else row.persons.take(STAFF_CHIPS_PER_ROW)
    val hidden = row.persons.size - STAFF_CHIPS_PER_ROW
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier
            .testTag(DetailsPeopleItemTag)
            .fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // 职位名与人名 chip 在同一个槽位里：职位名与第一排人名按基线对齐（FlowRow 把第一排的基线报给 Row；职位名折成几行就跟着长高），
        // 中文职位名与拉丁人名的字体回退不同，按中线居中会差几个像素。
        Row {
            Text(
                text = row.position,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(STAFF_POSITION_WIDTH).alignByBaseline(),
            )
            FlowRow(
                modifier = Modifier.weight(1f).alignByBaseline().padding(start = STAFF_POSITION_GAP),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { person ->
                    PersonChip(
                        onClick = { onPersonClick(person.id) },
                        avatar = { CircleAvatar(url = person.images?.avatarUrl(), description = null, size = STAFF_AVATAR_SIZE) },
                    ) { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (hidden > 0) {
                    PersonChip(onClick = { onTogglePosition(row.position) }, avatar = null) {
                        Text(
                            text = if (open) {
                                stringResource(R.string.detail_collapse)
                            } else {
                                stringResource(R.string.detail_staff_more, hidden)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 职位列宽 72（原型 `.crow` 网格首列；设计决定，not in M3）。 */
private val STAFF_POSITION_WIDTH = 72.dp

/** 职位名与人名之间的间距：16，同 M3 列表项前置内容与主内容的间距。 */
private val STAFF_POSITION_GAP = 16.dp

/** 人名 chip 的高与头像直径：40 / 32（设计决定，not in M3——原型 32 / 24 的头像太小，要一眼看过去就知道谁是谁）。 */
private val STAFF_CHIP_HEIGHT = 40.dp
private val STAFF_AVATAR_SIZE = 32.dp

/**
 * 人名 chip（原型 `.pchip`）：描边胶囊，高 [STAFF_CHIP_HEIGHT]、圆角 8（= `CornerSmall`）、[STAFF_AVATAR_SIZE] 头像 + 名字，
 * 左 4 / 右 10、头像与字 6；没有 [avatar] 时（「+N」「收起」）左右都 10。字阶 `labelLarge`（原型 13px 不在 M3 字阶里，取最近的 14）。
 */
@Composable
internal fun PersonChip(
    onClick: () -> Unit,
    avatar: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
) {
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .height(STAFF_CHIP_HEIGHT)
                .padding(start = if (avatar != null) 4.dp else 10.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar?.invoke()
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge, content = label)
        }
    }
}

/** 人物 tab 的骨架：同一个分段列表项，头像与两行文字画占位。 */
@Composable
private fun PeopleListSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        repeat(PEOPLE_SKELETON_ROWS) { index ->
            AvatarRowSkeleton(index, PEOPLE_SKELETON_ROWS, ListImageAvatarSize, Modifier.testTag(DetailsPeopleItemTag).fillMaxWidth())
        }
    }
}
