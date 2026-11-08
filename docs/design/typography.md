# 全应用字阶规范

唯一用途对照。界面使用 `MaterialTheme.typography` 中的字阶，不在调用点填写 `fontSize` 或另建产品字号 token。M3 分 display、headline、title、body、label 五类；缩减字阶时仍须保持明显对比，避免近似字号并列。依据：`.agent-skills/m3-expressive/references/typography.md`、`references/tokens.md`。

| 用途 | 字阶 | 依据 |
|---|---|---|
| 顶栏标题 | `titleLarge` | M3 typography.md「Top app bar title → title」；选 large 是本产品设计决策（not in M3）。 |
| 页面头部的主角名（条目名、人物名、用户名） | `headlineSmall` | M3 的 headline 用于短且高强调的文字，名片人名用 headline；具体 small 是本产品设计决策（not in M3）。 |
| 区块标题（详细资料、参与作品、章节等） | `titleMedium` | M3 分类标题用 title；具体 medium 是本产品设计决策（not in M3）。 |
| 面板的标题（通知、举报、发帖、资料等） | `titleLarge`，一行，靠左；确认动作在标题行右侧 | M3 没有规定面板标题字阶（not in M3），与顶栏标题同级；全部走 core:ui `SheetTitleRow`。 |
| 面板里一项设置的名字（收藏面板的评分、标签） | `labelLarge`，`onSurfaceVariant`（分组标题走 core:ui `SheetSectionTitle`） | M3 label 用于组件内的短字；这些名字属于面板这块组件、不是页面区块，不用区块标题的 title；具体 large 与配色是本产品设计决策（not in M3）。 |
| 列表项或卡片的主标题 | `bodyLarge` | Compose Material3 `ListItem` 的 headline 默认使用 `bodyLarge`；M3 卡片标题可用 title，本产品跨列表及卡片统一跟随 ListItem，属设计决策（not in M3）。 |
| 正文、简介、楼层正文 | `bodyMedium`；长篇阅读可用 `bodyLarge` | M3 文章正文用 body；具体 medium 与长篇 large 是本产品设计决策（not in M3）。 |
| 元信息（日期、集数、类型、计数文字） | `bodySmall` | M3 body 是文字段落、label 是组件内短字；元信息选 small 是本产品设计决策（not in M3）。 |
| chip、徽标、组件内的小字 | `labelMedium` 或 `labelSmall` | M3 label 用于组件内文字及很小的说明；尺寸按可用空间选择是本产品设计决策（not in M3）。 |
| 按钮 | `labelLarge` | M3 typography.md 明确按钮用 label large。 |
| 关键数字（进度数、统计大数） | 对应字阶的 emphasized 版本 | M3 的卡片主数字可用 display，强调字阶维持原尺寸而增加字重；对应字阶与选用场景是本产品设计决策（not in M3）。 |

强调态只用于选中态、操作按钮、标题和编辑式排版，不替换全套基础字阶。emphasized 字阶直接用 `MaterialTheme.typography` 上对应的 `*Emphasized` 样式（如 `titleMediumEmphasized`、`labelLargeEmphasized`，material3 版本以 `gradle/libs.versions.toml` 为准），不要用 `copy(fontWeight = …)` 自造，也不重设字号、行高或字距。

## 数字、拉丁字母与汉字之间留空格

界面文案在阿拉伯数字、拉丁字母与汉字之间留一个半角空格：字符串资源的占位符同样处理（`第 %1$d 集`、`%1$d 年 %2$d 月`），日期文案只经 core:ui 的 `formatMonthDay`（「9 月 30 日 周三」）、`formatPastDate`（「10 月 2 日」「2024 年 3 月 2 日」）与 `formatIsoDate`（「2026-09-30」），相对时间只经 `relativeTimeText`（一周以前的落到 `formatPastDate`）。服务端原文（资料表、简介、楼层）原样显示，不改。本条是本产品设计决策（not in M3）。

## 门禁与圆角

`tools/guards/check_type_shape.py`（`tools/ci/check.sh lint`）拦三件事：写死字号（`sp`、`em`、`TextUnit(…)`，或先放进同文件常量再传）、自造字重（字阶上 `copy` / `merge(fontWeight = …)`、`TextStyle(fontWeight = …)`、给 `Text` 单独传 `fontWeight`；正文里的局部加粗 `SpanStyle(fontWeight = …)` 是内容排版，不拦），以及等于 M3 圆角阶梯（4 / 8 / 12 / 16 / 28 dp，加上 M3 Expressive 的 20 = Large increased、32 = Extra large increased、48 = Extra extra large）的 `RoundedCornerShape` / `AbsoluteRoundedCornerShape` 字面量（`8.dp`、`8.0.dp`、`size = …`、`CornerSize(…)` 都算），或先放进同文件常量再传的写法——组合函数里写 `MaterialTheme.shapes.*`（20dp 是 `largeIncreased`），顶层常量写 `ShapeDefaults.*`。分角写法里某一角等于阶梯（`RoundedCornerShape(topStart = …, bottomEnd = 16.dp)`）同样拦，从 token 改角：`ShapeDefaults.Large.copy(topStart = …)`。不在阶梯上的圆角（6、10、18 等原型值）就地注明「设计决定（not in M3）」。
