package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 横排里一个人的 testTag：真实项与骨架共用，几何测试据此对位。 */
const val CarouselPersonTag = "carousel_person"

/** 一个人的宽与头像直径（原型 `.car .c`；设计决定，not in M3）。 */
private val CAROUSEL_PERSON_WIDTH = 76.dp
private val CAROUSEL_AVATAR_SIZE = 72.dp

/** 骨架画几项：铺满一屏宽即可（设计决定，not in M3）。 */
const val CAROUSEL_SKELETON_COUNT = 5

/** 横排的项间距（原型 `.car`）：在横排上方按项的位置作图的（作品统计的系列折线）也按它排。 */
val CarouselItemSpacing = 12.dp

/**
 * 横排（原型 `.car`）：项间距 [CarouselItemSpacing]、左右 16、下 4。全仓的横排卡片流（探索货架、首页的「也在进行」「好友在看」、
 * 作品详情的角色 / 制作 / 关联作品 / 系列、人物页的代表角色、角色与人物的关联人物）都走它；骨架传 [userScrollEnabled] = false。
 * 要跟着各项的位置画东西时传 [state]，从它的布局信息读项的位置。
 * [snapToItems] 为 true 时松手停在某一项的起始边上（对齐左边距）：一项占了大半屏宽的横排（探索的焦点卡、榜单页）用，
 * 不会停在两张卡中间。[itemSpacing] 换项距：探索的书脊排得更密；年表为 0，间隔画在项里（时间线要连过项与项之间）。
 */
@Composable
fun CarouselRow(
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    state: LazyListState = rememberLazyListState(),
    snapToItems: Boolean = false,
    itemSpacing: Dp = CarouselItemSpacing,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        flingBehavior = if (snapToItems) rememberSnapFlingBehavior(state, SnapPosition.Start) else ScrollableDefaults.flingBehavior(),
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

/**
 * 横排的一个人（原型 `.car .c`）：72 圆形头像 + 名字（`labelMedium`）+ 副行（`bodySmall`），各 1 行，宽 76。
 * [name] 为 null 时画骨架（同一份排版）。
 */
@Composable
fun CarouselPerson(
    name: String?,
    caption: String?,
    imageUrl: String?,
    onClick: (() -> Unit)?,
) {
    PressArea(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag(CarouselPersonTag).width(CAROUSEL_PERSON_WIDTH),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val nameStyle = MaterialTheme.typography.labelMedium
            val captionStyle = MaterialTheme.typography.bodySmall
            if (name == null) {
                SkeletonBox(Modifier.size(CAROUSEL_AVATAR_SIZE), shape = CircleShape)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonText(nameStyle, Modifier.fillMaxWidth(), widthFraction = 0.8f)
                    SkeletonText(captionStyle, Modifier.fillMaxWidth(), widthFraction = 0.6f)
                }
                return@Column
            }
            CircleAvatar(url = imageUrl, description = null, size = CAROUSEL_AVATAR_SIZE)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = caption.orEmpty(),
                    style = captionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 人的横排骨架：同一个 [CarouselRow] 与 [CarouselPerson] 排版，不可滑动。 */
@Composable
fun CarouselPersonSkeleton() {
    CarouselRow(userScrollEnabled = false) {
        items(CAROUSEL_SKELETON_COUNT) { CarouselPerson(name = null, caption = null, imageUrl = null, onClick = null) }
    }
}

/**
 * 列表项里的人物头像直径 56 = M3 List 的 `ItemLeadingImageWidth / Height`（component-tokens.md）；
 * 裁成圆是设计决定（not in M3，M3 的 leading avatar 是 40）。作品详情的角色列表、角色 / 人物页的关联列表与出演角色共用。
 */
val ListImageAvatarSize = 56.dp

/**
 * 圆形头像：图片铺满后**顶对齐**裁圆（画法见 [PortraitImage]）。角色 / 人物给的是整张竖立绘
 * （`RelatedImagesBo.avatarUrl`），脸在上部；方形的用户头像顶对齐与居中无异。没有图或加载失败时在 `surfaceContainerHigh` 底上画人形图标（[MissingImageIcon]；旁白、部分制作人员本来就没有图）。
 * 横排、角色列表、人名 chip、CV 行、关联人物、动态里的角色 / 人物、讨论与评论的用户头像、资料页与用户卡的头像共用。
 * [onClick] 非 null 时整个圆可点，水波纹随圆裁切。
 */
@Composable
fun CircleAvatar(url: String?, description: String?, size: Dp, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        PortraitImage(url = url, description = description, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 头像位上的一个图标：列表里不是某个人、却与人的行排在一起的一行（短信列表的「写信」、写给一个用户名）。
 * 与 [CircleAvatar] 同一位置、同一尺寸，tonal 圆底（secondaryContainer），图标只是装饰，行的文字说明它做什么。
 */
@Composable
fun IconAvatar(@DrawableRes icon: Int, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * 叠放的头像（加好友的动态、谁在看、首页好友在看）——全 App 唯一画法：后一个压前一个 [size] 的 1/4，
 * 每个外面一道 [size] 1/16 宽的描环把相邻头像分开，描环取头像下面那层的颜色（[ringColor]：卡片上 surfaceContainer、
 * 压在封面上 surface）。每个头像都是 [CircleAvatar]，缺图（或还在加载，传 null）时同一个人形占位。
 * 尺寸比例取自原型 `.stack`（32 压 8、描 2）：设计决定（not in M3）。
 */
@Composable
fun StackedAvatars(avatars: List<String?>, size: Dp, ringColor: Color, modifier: Modifier = Modifier) {
    if (avatars.isEmpty()) return
    val ring = size / 16
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-size / 4)) {
        avatars.forEach { url ->
            Box(Modifier.size(size).border(ring, ringColor, CircleShape).padding(ring)) {
                CircleAvatar(url = url, description = null, size = size - ring * 2)
            }
        }
    }
}

/** [roomPx] 像素里叠放得下几个 [sizePx] 的头像（与 [StackedAvatars] 同一套压叠）；连一个都放不下是 0。 */
fun stackedAvatarsFitting(roomPx: Int, sizePx: Int): Int =
    if (roomPx < sizePx) 0 else 1 + (roomPx - sizePx) / (sizePx - sizePx / 4)
