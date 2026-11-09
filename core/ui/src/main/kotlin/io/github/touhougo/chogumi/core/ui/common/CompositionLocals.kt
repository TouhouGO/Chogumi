package io.github.touhougo.chogumi.core.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.CoverQuality
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.ReadingTextSize
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.ui.common.navigation.Route

/**
 * 站内链接的去处：链接已由 `inAppRoute` 解析成路由，这里负责导航过去。
 *
 * **唯一提供点**：app 壳的 `AppShell`，值是导航器的 `navigate`。
 * 渲染层（BBCode 正文、动态里的链接、设置页的调试跳转）从这里取，不各自收回调参数。
 *
 * 默认 null 表示未接线环境（预览 / 单元测试）：站内链接退回浏览器行为，而不是崩溃。
 */
val LocalInAppLinkHandler = staticCompositionLocalOf<((Route) -> Unit)?> { null }

/**
 * 当前的功能可见性开关组（完整 / 专注模式、公共评分开 / 关）。
 *
 * **唯一提供点**：app 壳的 `AppShell`（`CompositionLocalProvider(LocalAppFeatures provides …)`），
 * 值由 `ShellViewModel` 从偏好 DataStore 的 `appFeatures` 派生。
 * 渲染层只读这里的布尔值，**不得**自行判断模式，也不得在各页另存副本；
 * 红线的源码守卫见 core:model 的 `ExperienceModeSingleSourceTest`。
 *
 * 默认值是全部功能开启的派生结果：预览与未接线的单元测试环境不该看到功能凭空消失；
 * 要验证隐藏行为，在测试里显式 provide 相应派生结果。
 */
val LocalAppFeatures = staticCompositionLocalOf { AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true) }

/**
 * 当前账号关注的人（[Friends]）。**唯一提供点**：app 壳的 `AppShell`，值由 `ShellViewModel` 从 `FriendsRepository` 取，
 * 加好友、取消好友后当场变。只有 core:ui 的 `FriendMark`（名字旁的「好友」小牌）与 core:reader 的 `UserCardSheet`（卡片里的关注状态按钮）、
 * 楼层长按菜单（`CommentThreadItem`，加为好友 / 解除好友二选一）读它，页面不另存副本。名单会变，所以用 [compositionLocalOf]：
 * 变了只重组读它的小牌、按钮与菜单。默认谁也没关注：预览与单元测试里不画小牌。
 */
val LocalFriends = compositionLocalOf { Friends.NONE }

/**
 * 当前登录的账号（游客为 null）。**唯一提供点**同 [LocalAppFeatures]（app 壳 `AppShell`，值是 `ShellState.account`）。
 * 只给要把「我」画成一个人的地方取用户名：贴贴名单里我那一格（头像按用户名取，点了去自己的主页）。
 * 登没登录仍看页面从 `appEntries` 收到的 `isLoggedIn`，「是不是我」仍在数据层判（`isOwn`、贴贴的 `selected`），不读这里。
 * 换号时整套页面按账号重建（`AccountPageScope`），所以用 [staticCompositionLocalOf]。
 */
val LocalSignedInAccount = staticCompositionLocalOf<SignedInAccount?> { null }

/**
 * 当前的阅读字号档位。**唯一提供点**同 [LocalAppFeatures]（app 壳 `AppShell`，值来自偏好 DataStore）；
 * 只有 core:reader 的 `ReaderTypography` 读它，档位 → 字阶的映射在那里。
 */
val LocalReadingTextSize = staticCompositionLocalOf { ReadingTextSize.DEFAULT }

/**
 * 当前的封面画质档位。**唯一提供点**同 [LocalAppFeatures]（app 壳 `AppShell`，值来自偏好 DataStore）；
 * 只有 core:ui 的 `rememberCoverRequest` 读它，档位 → 取哪张图的规则在 `coverUrlFor`。
 */
val LocalCoverQuality = staticCompositionLocalOf { CoverQuality.DEFAULT }

/**
 * 用户卡片（点楼层头像弹出的面板）里的生涯卡：参数是该用户的用户名。生涯卡与它的数据在 feature:profile，
 * 弹出用户卡片的 core:reader 够不着，所以由 app 层接上。
 *
 * **唯一提供点**：app 的 `AppEntries`（它同时决定「这个用户名是不是我自己」与点卡片去哪）。
 * 默认 null 表示未接线环境（预览 / 单元测试）：用户卡片不画生涯卡。
 */
val LocalUserCareerCard = staticCompositionLocalOf<(@Composable (username: String) -> Unit)?> { null }

/**
 * 「收录」面板（页面 ⋮ 里的「收录」打开）：参数是收进目录的对象与面板滑走后的回调。面板与它的数据在 feature:profile，
 * 画 ⋮ 的 core:ui 够不着，所以由 app 层接上。
 *
 * **唯一提供点**：app 的 `AppEntries`。默认 null 表示未接线环境（预览 / 单元测试）：⋮ 里不出现「收录」。
 */
val LocalIndexPicker = staticCompositionLocalOf<(@Composable (target: IndexTarget, onDismiss: () -> Unit) -> Unit)?> { null }
