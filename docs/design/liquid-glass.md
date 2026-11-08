# 液态玻璃（实验）

设置 › 外观 › 「玻璃效果」打开后，浮在内容上的几样控件换成半透明的玻璃。默认关；系统没有模糊（Android 11 及以下）时这一行不出现。
整套是设计决策，不在 M3 里。

## 代码在哪

玻璃的全部代码只在 **core:glass** 一个模块里；core:ui 与页面原来的组件不知道它。

| 东西 | 在哪 |
|---|---|
| 开关（设置打开且系统支持） | `GlassTheme`，包在 `ChogumiTheme` 里面（`AppActivity`） |
| 一层采样、把一块玻璃画出来 | `Glass.kt`：`rememberGlassLayer`、`glassSource`、`Modifier.glass` |
| 按下浮起、松手落回（圆钮、主操作） | `GlassTouch.kt`：`rememberGlassTouch`、`Modifier.glassTouch`、`Modifier.glassContent` |
| 底栏往下读时收成左下角圆钮 | `GlassBarMinimize.kt`：`rememberGlassBarMinimize`、`Modifier.glassBarScroll` |
| 圆钮、顶边、胶囊里的单选与它的放大镜（底栏与 tab 共用） | `GlassControls.kt`：`GlassIconButton`、`GlassTopEdge`、`GlassTabs` |
| 头部从顶边画起的页面外框与顶栏 | `GlassHeader.kt`：`GlassHeaderTabsFrame`、`GlassHeaderBar` |
| 内容从玻璃下面滚过时让出多少 | `GlassInsets.kt`：`LocalGlassContentTop`、`LocalGlassContentBottom`、`AboveBottomBar`、`AboveGlassBar` |
| 一屏唯一的主操作 | `GlassAction.kt`：`GlassActionButton` |

页面只在入口分岔：`rememberGlassLayer()`（或页面自己的 `rememberSubjectGlass()`）是 null 就画原来的组件，原来那一支照旧。
现在接入的地方：

- 底栏：app 的 `GlassNavigationBar.kt`，`AppShell` 里按开关二选一；页面区录进采样层（`ShellLayout`）。顶级页（首页、探索、进度、社区、我的）
  在 `AppEntries` 里包进 `AboveBottomBar`：玻璃关着时让出底栏，和原来一样；开着时页面铺到屏幕底，列表的下内边距加上
  `LocalGlassContentBottom`，FAB 与提示条放进 `AboveGlassBar`，停在胶囊上面。推进来的页面这个值是 0。`AboveGlassBar` 不另包一层盒子，
  只换掉对齐用的作用域：多一层铺满页面的节点会在点击判定里挡住下面的列表（`AboveGlassBarTest`）。
- 作品详情：feature:subjectdetails 的 `SubjectDetailsGlass.kt`（玻璃版切换栏与收藏按钮），`SubjectDetailsScreen` 里顶栏、外框、骨架、
  收藏按钮四处分岔；列表的上内边距读 `LocalGlassContentTop`（不在玻璃外框里时是 0）。
- 页面的 ⋮：core:ui `PageMoreMenu` 的按钮经 `LocalBarIconButton` 画，玻璃顶栏把它换成玻璃圆钮。

和别的地方共用的逻辑只有一份：收藏按钮里那一行（`CollectionFabLabel`）、有哪些 tab（`visibleSectionsFor`）、菜单项（`PageMoreMenu`）。
去掉玻璃：删掉 core:glass、几个 `*Glass*.kt` 和入口的分岔。

## 哪里加，哪里不加

玻璃只给**浮在内容上、有内容从下面滚过去**的控件。内容本身永远是实心的。

加：

- 底栏：一条浮动的玻璃胶囊，选中项上压着一块放大镜；顶级页的内容一直铺到屏幕底，从胶囊后面滚过。往下读时收成左下角一颗圆钮。
- 头部从顶边画起的页面（作品详情）：顶上的返回与 ⋮ 是玻璃圆钮；顶上一条渐隐的模糊（顶边）在内容滚上来时淡入，标题写在它上面；
  钉住的 tab 是玻璃胶囊，内容从它下面滚过。
- 一屏唯一的主操作（作品详情的收藏按钮）：带主色的玻璃。

不加：卡片、封面、章节格、列表、楼层、图表；输入框与表单；确认对话框；页面背景与设置行；玻璃里面的按钮（玻璃里不叠玻璃）。
面板、菜单、提示条以后再说。

## 三种玻璃

| 种类 | 给谁 | 底色 |
|---|---|---|
| 透明 `Clear` | 只放图标的圆钮 | 页面底色，浅 38%、深 42% |
| 带字 `Labeled` | 胶囊（底栏、tab） | 页面底色，浅 66%、深 62%，背后的字不和胶囊上的字抢 |
| 带色 `Tinted` | 一屏唯一的主操作 | 主色容器，浅 66%、深 70%，字用主色容器上的颜色 |

模糊半径 10dp（背后的字糊开读不出，封面的颜色与大块轮廓还认得出），叠一层提亮饱和度；边上一圈 12dp 的折射与高光（Android 13 起），一层淡阴影。
胶囊里选中的那滴取 M3 导航指示器的颜色（`secondaryContainer`）。

顶边不是一块玻璃：从屏幕顶到钉住的 tab 胶囊下沿是实的（模糊 24dp，压 86% 的页面底色，字读不出），再往下 24dp 淡到透明；
它垫在 tab 胶囊下面，圆钮与标题写在它上面。

## 怎么动

- 圆钮与主操作按下：玻璃连同图标整体浮起、放大一圈（短边多出 8dp），松手落回。形状不变、不被拉扯，没有水波纹。
- 胶囊里（底栏、tab）：胶囊本身不动。选中的那一格上压着一块放大镜，落着时是一块淡底色，透过它的那一格是主色。手指一按上胶囊，
  放大镜浮起、长到比胶囊还高（1.36 倍），变成透明的玻璃气泡，滑到手指下面：边缘折射（带一点色散）、高光、阴影，透过它的那一格放大 1.2 倍、
  显成主色。按着左右拖，气泡跟着手指走，滑得快时顺着方向略拉长；松手落回最近的一格并选中它。只点一下就是普通的点选。
  气泡里看到的是页面、胶囊另录的一份和一份看不见的主色格子三层叠起来。
- 换选中项：放大镜从旧位置滑到新位置，到位回弹。
- 底栏收起（照 iOS 26 的标签栏）：内容往下读连续滚过 48dp，胶囊缩成左下角一颗圆钮（当前 tab 的图标），往回滚 48dp 或点圆钮就展开；
  换 tab 时展开。只看内容真的滚了多少，横向翻页、拉到顶再往下拉都不算。全收起后胶囊不在组合里，空出来的地方点到的是页面。
- 顶边：内容在顶上时完全透明（没有模糊），头部底边退到 tab 胶囊那里时淡入，胶囊钉住时全出来。
- 头部收起：内容区跟着 tab 胶囊往上走，胶囊钉住后内容区不再上移，头部最后那一截从顶边下面退走；列表在顶部时第一行永远在胶囊下面。
- 作品页的圆钮与 tab 胶囊不随滚动让位；底栏滑出屏幕的规则与原来一样。
- 弹簧取主题的 expressive 空间规格（`check_motion.py` 登记），底栏收起用主题的空间规格；系统关掉动画时一切瞬时。

## 性能

- 一页只有一层采样，浮在上面的玻璃共用；胶囊的放大镜另读胶囊录下的一份和一行主色格子，落着时不折射、不模糊。
- 位置、按下进度、顶边出现几成、底栏收起几成都只在排版或绘制阶段读，滚动、按下与收起的过程中不重组。
- 顶边透明时整层不画；底栏整条出了屏幕时不画。
