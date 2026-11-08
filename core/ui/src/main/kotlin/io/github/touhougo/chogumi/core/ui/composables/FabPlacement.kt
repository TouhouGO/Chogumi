package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.unit.dp

/*
 * 全 App FAB 位置的唯一规则：右下角，距尾边 / 底边 16dp（M3 fabs.md「the FAB should always have 16dp margins」），
 * 在底栏 / 系统导航条之上。只有两种放法，调用处不许再自己 align / padding / offset / 补导航条高度：
 *
 * - 普通 FAB（含扩展 FAB）：放进 Scaffold 的 floatingActionButton 槽位，由 Scaffold 留 16dp 并避让系统栏；
 * - 选项菜单 FAB：[SelectionFabMenu]，自己贴所在 Box 的右下角，M3 FloatingActionButtonMenu 自带 16dp。
 *
 * 调用处自己摆就会一页一个位置（实测过）：菜单 FAB 调用处再加 16dp 就距边 32dp；作品详情「在看」再补一次导航条高度
 * 就比别处高 24dp；只有 Scaffold 槽位里的 16dp 是对的。
 *
 * FAB 也不随滚动变形（设计决定，not in M3：M3 允许扩展 FAB 往下滚收成图标、往回滚展开）：一个 FAB 要么一直带文字
 * （扩展 FAB，如作品详情的收藏），要么只有图标（FloatingActionButton，fabs.md 最小一档 56dp，如「回复」「发帖」），
 * 尺寸固定，扩展 FAB 不传 expanded。门禁：tools/guards/check_fabs.py（tools/ci/check.sh lint）。
 */

/**
 * 滚动列表为 FAB 让出的底部留白（全 App 唯一）：FAB 收起态 56 + 外边距 16 + 呼吸 16 = 88dp。
 * 56 / 16 出自 M3（fabs.md），呼吸 16 是设计决定（not in M3，8dp 网格）。用 FAB 的页面，列表底部 contentPadding 都取它。
 */
val FabClearance = 88.dp
