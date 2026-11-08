package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.composables.HeaderTopAppBar
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu

/**
 * 用户主页的顶栏（[HeaderTopAppBar]）：叠在横幅上，内容滚上来时连状态栏一起填色、显出称呼 [title]。
 * 推进来的页面左边是返回；右边看自己是设置，看他人是 ⋮。
 */
@Composable
internal fun BoxScope.ProfileTopBar(
    title: String,
    /** 主页外框的收起状态：顶栏据此填色，头部收满后让位给分段（[HeaderTopAppBar]）。 */
    frame: CollapsibleHeaderState,
    isSelf: Boolean,
    onSettingsClick: () -> Unit,
    /** 推进来的页面才有返回（见 [ProfileScreen]）。 */
    onBack: (() -> Unit)?,
    /** 他人主页 ⋮ 的「网页」「分享」：主页地址与称呼。 */
    link: PageLink,
    /** ⋮「绝交」请求（登录门之后弹确认框）。 */
    onBlockRequest: () -> Unit,
    /** ⋮「报告」：打开报告面板。 */
    onOpenReport: () -> Unit,
) {
    HeaderTopAppBar(
        title = title,
        frame = frame,
        onBack = onBack,
        modifier = Modifier.align(Alignment.TopCenter),
        actions = {
            if (isSelf) {
                IconButton(onClick = onSettingsClick) {
                    Icon(painterResource(R.drawable.settings_filled_24), stringResource(R.string.settings))
                }
            } else {
                // 绝交 / 报告是写操作，经页面的登录门（未登录点了去登录）
                PageMoreMenu(link) {
                    item(R.string.block, R.drawable.block_24, onBlockRequest)
                    item(R.string.report, R.drawable.flag_24, onOpenReport)
                }
            }
        },
    )
}

/** 他人主页的网页地址；资料还没到时给站点首页。 */
internal fun profilePageUrlOf(user: UserBo?): String = user?.let { BgmWeb.user(userKeyOf(it.username, it.id)) } ?: BGM_SITE
