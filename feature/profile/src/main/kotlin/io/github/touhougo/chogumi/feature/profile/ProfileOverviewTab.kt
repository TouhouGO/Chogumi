package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectType

/**
 * 概览：生涯卡（[CareerCard]），本人与他人同一个组件，点整张卡进完整统计。资料未到时是同尺寸的骨架，数据到了原位替换。
 *
 * 签名是身份信息，在资料头里（见 [ProfileHeader]）；简介与资料（注册时间 / 所在地 / 主页）不在这里：
 * 入口是资料头 ID 右侧的 ⓘ，点开是 [ProfileInfoSheet]。
 * 按原型**删掉邮箱、关联账号与「退出登录」**——退出登录的唯一入口在设置页。
 */
@Composable
internal fun OverviewTab(
    career: CareerState,
    publicRatings: Boolean,
    onOpenCareer: (SubjectType) -> Unit,
    onRetryCareer: () -> Unit,
) {
    // 原型每段 `.sect{padding-top:16px}`：首段减去列表项间距 12 后上留 4，末尾留 24。
    CareerCard(
        content = career.cardContent(publicRatings),
        title = careerTitle(career),
        onOpenFull = onOpenCareer,
        onRetry = onRetryCareer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp),
    )
}
