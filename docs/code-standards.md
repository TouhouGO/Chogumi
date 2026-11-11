# 代码规范

每一处改动都按这里写、按这里审：审阅者逐条对照，哪条不满足就在 PR 上点名哪条；违反红线的 PR 不合入。
规则从哪里来：做错过的事先记在 [LESSONS.md](../LESSONS.md)，同一条在三个不同日子出现就搬到这里（标「转正」与日期）。

## 三条红线（违反任何一条 = PR 不合入，没有例外）

1. **单一真源。** 一个业务判定（按类型分支、映射、推导）只住在一个纯函数里，界面与数据层都从它推导；别处不再写第二个
   `when(type)`。状态只有一个来源：来源搬家时，旧的那份在同一次改动里删掉。
   - 显示名（中文名优先，空白时原名）只来自 core:model `displayNameOf`；用户的显示名（昵称、用户名、`#uid`）只来自
     core:model `displayNameOf(user)` / `userDisplayNameOf`。门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_display_name.py`。
   - 时间线动态的大类（`cat`）只经 core:model `timelineCategoryOf` → `TimelineCategory` 读，永远不拿 `cat` 和数字比。
     门禁：`python3 tools/guards/check_timeline_category.py`。
   - 一个修复落在所有兄弟路径都经过的那一个函数里。缺陷出现在一对里的一边（正片 / 特别篇，真行 / 骨架，头部页 / 二级页，
     同一条流的一个消费方），写修复之前先找兄弟，同一次改动一起修：两套回滚、两份同样的边距、一条顶栏规则写两遍，就是下一个缺陷。
   - 对一张列表的决定在事件发生处对整张表做一次，再往下传：网格的一页、一格、预览读同一个结果（`EpisodeTap.changes`），
     不从自己画的那一片切片重算。按 id 再找一个条目（找一集）只有一条规则、一个函数（`EpisodeTracking.progressIndexOf`）。
2. **不留死代码。** 算了没用的变量、没人引用的函数与字符串键、替换后的残留（自画的转圈换成官方组件之后）、复制粘贴的碎片：
   当场删。编译告警即构建失败（每个 Kotlin 编译单元都开 `allWarningsAsErrors`，测试源码也是）。没用的 import 也算：编译器不报，
   `python3 tools/guards/check_unused_imports.py` 报（`tools/ci/check.sh lint`）。
3. **不为省事耦合。** 不为了绕开调用链复制一份逻辑。一条规则两处要用就抽出来；要按类型分支就经过那一个判定函数。

修不住、或同一个抱怨修过一次又回来：别再逐个打补丁。用几句话把模型写在它唯一的归属处（这份文件，或归属者的 KDoc），让代码在那一处
照着做，删掉按例分支。审阅意见和它建议的修法是一个主张：先对着代码核实（路径可达吗、谁真的调用它、建议的通道存在吗）再动手。
守着不可能出现的状态的防御是死代码。外部输入不是这种状态：帖子的标记、服务端给的值（一列的范围）、在后来取到的数据里再找一次的 id。
这些按规矩降级——显示成文本，或让这个操作得到一个 `Failure`——绝不静默丢内容，也绝不把找不到目标读成「全部清掉」。
`require` 只用于调用方契约。

注释、测试名、文档只说代码现在是什么、为什么——不说它怎么来的：没有单号，没有某一期、某个 PR、交接或改版这类过程说法，没有
「以前……现在……」。错题本（LESSONS.md）是唯一记来历的地方。门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_process_tags.py`。

### 不是红线的事

- 极端情况的排版（最小触控区域、两倍字号、极窄屏）是建议，不是铁律：顺手能满足就满足，不为它单开工作，也不为它去问所有者。
  380dp 字号 1.1 是常规检查，照看。
- 现阶段做不做、怎么做由做事的 agent 自己定（[流程](dev/workflow.md)「谁来定」）；要翻一条已经写下的决定，单独一个 PR 说明理由。

## 一次改动里有什么

| 规则 | 审阅时看什么 |
|---|---|
| 只做目标需要的事。 | 每处行为变化都有理由。顺手的重构、额外的功能、流程的改动分开列：攒成一个 PR 时正文逐件写，不混在一起。 |
| 修在所有兄弟路径都经过的那一个函数里。（转正 2026-10-25） | 改之前列出兄弟：正片与特别篇、真行与骨架、头部页与二级页、同一条流的几个消费方，都在这次改动里。同一个抱怨回来第二次：停下写模型。 |
| 改共享原语先数清调用方。（转正 2026-10-25） | `cachedNetworkCall`、`Load.advance`、`collectSection`、`Pager`、常用测试夹具的签名：PR 正文列每个调用方为什么仍然成立，每一类消费方有一个测试（`combine` 几条流算一类）；改的是排版组件时，截图覆盖每一类调用方。夹具签名的改动自己一个 PR，其余分支之后合 main。 |
| 规则放在所有入口都经过的那一点。（转正 2026-10-27） | OAuth 状态只在 `AuthRepository.login` 清一次，不在每个登录函数里各清一次；同一条命令不写三遍。 |
| 列表上的决定在事件处对整张表做一次。（转正 2026-10-27） | 见红线 1：`EpisodeTap.changes`、`EpisodeTracking.progressIndexOf`。每格各算一遍是 O(n²)，只在可见的一页里找是越界。 |
| 先找已有的实现，再动手写。（转正 2026-11-09） | 动手前在 core:ui、core:data 与 core:ui 的 testFixtures、`tools/guards/sources.py` 里找同一种能力，兄弟 PR 刚合入的也算：演示与真组件读同一个判定（格子种类 `EpisodeTracking.episodeGrid`，形状、强调、描边 `TileStyle`），页面级的切换只经 `chogumiTransition`，测试的等待与收尾只经 `awaitUntil` / `cancelAndAwait`，门禁读 Kotlin 只经 `sources.py`。 |
| 修 bug 不新添死代码。（转正 2026-10-27） | 不起作用的代次检查、守着不可达分支的标志、只为不可达分支留着的辅助函数、只有测试在用的新参数、只给测试留的默认参数：删。 |
| 修好特例，也测普通的邻居。（转正 2026-10-27） | 修复旁边放一个普通情况的测试：遮罩外的 `[size]` 表情还是原大，输入「0」仍清空进度。 |
| 不悄悄丢掉，也不悄悄报零。（转正 2026-10-27） | 忙时被拒的请求记下来，忙完就做（`Pager.reloading`）；一个目录都没读到的计数工具报错，不报 0。 |
| 账号在用户动手那一刻取。（转正 2026-10-25） | 细则在「ViewModel 状态与失败提示」一次操作一个账号。 |
| 结果不落到比它新的状态上。（转正 2026-10-25） | `await` 之后先查这期间发生了什么。细则在同一节。 |
| 失败不在半路丢掉。（转正 2026-10-25） | 细则在同一节。 |
| 尺寸是预算；要活过懒加载行的状态放在列表之上。（转正 2026-10-25） | 细则在「Compose 的老坑」。 |
| 审阅意见是主张，先核实再做。 | 路径可达吗、谁真的调用它、建议的通道存在吗。 |
| 已定的决定不顺手翻。 | 写在这里、[开发须知](dev/guide.md)、LESSONS.md 里的决定（服务端故障只回滚加提示、不向上游提需求、浏览器 OAuth 保留自定义 scheme、零集作品是正常结果……）不在别的 PR 里顺手改。 |

## 测试

| 规则 | 审阅时看什么 |
|---|---|
| 等待条件单调。 | `awaitUntil` 只是采样，不是同步：等的条件一旦为真就一直为真。等生产代码本来就暴露的「这一轮结束」信号（`active` 集合清空、捕获到的值变成非空），不等中间态的快照（一张表写了、下一张没写）。它用 `System.nanoTime()` 计预算、在真实调度器上睡，所以活得过 `runTest` 的虚拟时间；`Dispatchers.setMain` 只经 `TestMain`（core:data testFixtures，install / uninstall），收尾用 `cancelAndAwait`——裸 `cancel()` 只是请求取消，会撞上下一条测试的 `setMain`。这两件事各只实现在那一处，调用点不重写。 |
| 假服务端跑在多个线程上。 | `MockEngine` 的处理器跑在发请求的那个线程：请求用 `ConcurrentRequestRecorder`（core:data testFixtures）记、用 `AtomicInteger` 数，不用普通列表或 `n++`；并行发出的请求按集合或排序后断言，不按到达顺序。 |
| 测试证明名字说的事，驱动做决定的那段生产代码。 | 复现名字里的条件，断言那条路真的走了（服务端 400 对应的请求发出去了、货架重新取了），而不只是终态好看——代码本来就会到的终态什么也证明不了。测试调用的是页面真正调用的函数、传它真正传的状态；不测判定的副本、不把期望值喂给包装函数。 |
| 测试与设计不一致时，按设计断言。 | 不把 `<` 放成 `<=`、不加容差、不为一个调用方加开关、不删断言换绿；错的测试改回意图，并在坏掉的实现上看它红。 |
| 新回归测试先红一次。 | 在它要抓的代码上（改动之前的实现，或临时弄坏的生产分支）跑一次看它红，再恢复。一开始就绿的测试没证明它测到了什么。PR 正文引用那一行失败原文。 |
| 红就是缺陷报告。 | 不静默重跑。不是本次改动引起、单跑又不复现的红，先记进 flaky 日志 `.devdoc/flaky-tests.md`（测试、失败原文、条件、单跑结果、怀疑的原因）再重跑；没有 `.devdoc/` 的会话（云端）把同样的条目写进 PR 正文。flaky 以后一个一个、限时修，不在别的工作中途无限期追。 |
| 同一条偶发红再出现就是缺陷。（转正 2026-11-09） | 记过一次 flaky 的红再出现，不再记 flaky、不重跑了事：查到根因、让它必红，再修该修的那一边（测试或生产）。单跑不复现只说明窗口小，不说明没有缺陷。失败原文从 JUnit XML 取（CI 的测试报告产物）：控制台的简写只给测试函数那一行。 |
| 偶发红先查测试自己。（转正 2026-11-07） | 偶发红常出在测试：等的条件不单调、读的是两拍之间的中间态、测试推进的调度器没盖住页面的全部协程、假故障只坏「下一次」、按真实时间算的截止在被测内容就绪之前就开始走。先找测试与生产不一样的地方（调度器、时序、夹具）并让它必红，再决定改哪边；生产没错时不改生产。按真实时间断言的测试，截止在被测内容画出来之后起算，按那一步前后两次读钟的区间断言，不按固定区间：宿主卡多久区间就宽多少（`EpButtonDrainTest`）。 |
| 必要的测试，不多不少。 | 一个行为一个测试；契约测试只守跨文件的不变量（路由都有页面、字符串两种语言都有、feature 之间不互相依赖……）；不为配置项、文案写测试。截图与逐帧录屏是给人看的，标 `@VisualCapture`、只经 `saveScreenshot` 落盘，不算测试（`python3 tools/guards/check_test_assertions.py`）。 |
| 与生产同构。 | 网络、鉴权、序列化的测试用 core:network 夹具 `testApiClient(engine)`（`python3 tools/guards/check_test_clients.py`），细则在[开发须知](dev/guide.md) §3。 |

## 验收标准

合并前每一项都要有答案，写在 PR 正文「怎么验证」里；跑的写跑了什么、在哪个提交上、打印了什么，没跑的写「没跑」，不省略。
怎么跑在[流程](dev/workflow.md)「合并前」。

1. **回退测试。** 新回归测试在改动之前的代码上红过，红的是它名字说的那个行为（编译不过、被跳过都不算），PR 引用那一行；恢复后绿。
2. **按范围的本地检查。** `tools/ci/check.sh changed origin/main`（或 `modules <模块>…`）在要合的那个提交上绿；测试数只抄
   `tools/ci/count_tests.py` 最后打印的那一行。门禁在最后一次编辑之后重跑过。
3. **仪器测试与截图。** 改到 androidTest 覆盖的界面：在认领的模拟器上跑对应模块的 `connectedDebugAndroidTest`；改到界面：
   448dp、380dp 字号 1.1、800dp，浅深两色，改动到达的每个页面、tab 与状态（短、空、全部）。云端线程写「没跑：待 Mac」。
4. **真实使用。** 改到服务端接口或网页会话：对线上实测过（契约在 `tools/openapi`，事实在[开发须知](dev/guide.md) §1）；
   没实测的标「推断」，不在它上面接着开发，也不对用户说能用。
5. **没跑的写没跑。** 仪器测试、真机、整仓检查、真实服务端，逐项写跑了还是没跑。
6. **合并的是审过的那个 head。** 审过之后又推了提交，重新审；合并时核对 head sha；squash，删分支。

## Material Design 3 / M3 Expressive

M3 规范副本在 `.agent-skills/m3-expressive`（仓库外，gitignore）：Mac 上在检出里，项目线程读项目共享文件里的副本；没有的贡献者
向维护者要。公开的 [Material Design 3 指南](https://m3.material.io/) 与
[Compose Material 3 文档](https://developer.android.com/develop/ui/compose/designsystems/material3) 可以参考。

写或审任何 M3 界面（组件、dp、圆角、动效弹簧、字阶、颜色角色、自适应布局）之前，先读
`.agent-skills/m3-expressive/SKILL.md`，按它的「Where to look」表进具体的参考文件。数值、token 名、组件名不凭记忆：

- 精确值（字阶、圆角、弹簧、海拔）→ `references/tokens.md`
- 每个组件的 dp 几何 → `references/component-tokens.md`
- 组件行为、变体、该做不该做 → `references/components/*.md`
- 平台 API 有没有 → `references/platforms.md`

M3 里真没有的值，说清楚并标「设计决策（not in M3）」，不当成规范写。深色主题里 `tertiaryContainer` 是这套配色最亮的容器色，
不用来做低强调的标记。
浮在面板、卡片或页面上的一块（筛选格、统计格、获得焦点的输入框）的底色只来自 core:ui `raisedContainer`：浅色下是近白的
`surfaceContainerLowest`，深色下是 `surfaceContainerHigh`——深色里 `surfaceContainerLowest` 是最暗的一档、接近纯黑，直接用就是面板上的一块黑。
门禁：`python3 tools/guards/check_surface_roles.py`。

图标只来自 `tools/icons/material_symbols.py`：一份钉死的 Material Symbols Rounded 快照，文件名 = 符号名（填充的加 `_filled`），
默认不填充，只有选中 / 开着的状态与顶栏动作用填充。新图标加进清单再取；不手写、不复制矢量。门禁：`python3 tools/guards/check_icons.py`。

代码里的字阶与圆角 token：字号与字重只来自 `MaterialTheme.typography`（强调 = 对应的 `*Emphasized` 样式，不用
`copy(fontWeight = …)`）；等于 M3 圆角阶梯（4 / 8 / 12 / 16 / 28 dp，加 Expressive 的 20 / 32 / 48 dp）的圆角写 token
（`MaterialTheme.shapes.*`，顶层常量用 `ShapeDefaults.*`）。规则与角色表在 [字阶规范](design/typography.md)；门禁：
`python3 tools/guards/check_type_shape.py`。

富文本（core:reader 的任何组件）不进固有尺寸查询：它的 `SubcomposeLayout` 一被问就抛，折叠判定每帧翻转。`IntrinsicSize` 只许出现在
`tools/guards/check_intrinsic_size.py` 列出、写了理由的函数里；细节在[开发须知](dev/guide.md) §2.3。

## 底部面板只有一种

每个底部面板都用 `PageModalSheet`（全高异步列表用 `ScrollableModalSheet`），跳不跳页都一样。它们画在页面自己的面板层里
（`PageSheetLayer`，由 `rememberPageSheetNavEntryDecorator` 按导航条目装上），所以面板跟着页面走、跟着页面同一帧回来——没有窗口要重建，
没有东西盖住下一页；平板上在导航轨旁边的页面区里居中，遮罩只盖页面，和别的面板一样。

不要重新解决这个问题。尤其不要：
- 用官方 `ModalBottomSheet` 做任何面板，或用 `Dialog` / `Popup` 做能跳页的面板（它们是独立的系统窗口：出去时盖住下一页、回来时
  约 100 ms 的空档、平板上按整个窗口居中盖住导航轨——第二个窗口没有时序或偏移补丁能救）；
- 读 `LocalNavAnimatedContentScope` / 过渡状态来掐面板的显隐；
- 「为了避开问题」先关面板再跳页（回来必须恢复它）。

面板开着时的失败提示画在面板上：页面把自己的 `SnackbarHostState` 交给 `PageModalSheet(snackbar = …)`（`ScrollableModalSheet` 同），
面板开着时不画页面自己的 `SnackbarHost`；面板内容不自己放 `SnackbarHost`（同一段内容也当整页画的单集讨论 `EpisodeThread` 除外）。
页面上的提示条会被面板和遮罩盖住。

`PageModalSheet` 缺新面板要的能力，就扩展 `PageModalSheet` 本身。门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_sheets.py`。

## 整理只有一种实现

探索货架和作品统计模块同用 core:ui `arrange/` 的 `arrangeOnLongPress`、`ArrangeList`、`ArrangeEditorSheet`、`ArrangeAddSheet`、`ArrangeChoiceRow`、`StyleOptions` 与 `ArrangeMachine`。调用方只提供领域草稿、目录和持久化动作；拖拽与左滑沿 `ReorderableList`，顶栏沿 `ArrangeBar`。不在feature另写整理行、面板骨架或保存代次，不让feature互相依赖。

整理有三层：页面本身进入整理（`ArrangeBar`）、「添加模块」面板、编辑面板。面板的显隐只由 `ArrangeMachine` 定：编辑面板保存成功总是回到整理页本身，从「添加模块」里新建的两层一起收起，页面上多出来的模块就是成功的反馈；保存失败两层都留着、草稿不丢；下滑、点遮罩或返回只关最上面一层。

统计 tab 上画哪些模块只由 `visibleStatsModules` 定（页面与整理列表同读）；页面只调 `statsTabItems`：布局读到之前一块骨架，一个模块也画不出来时给说明和进整理的入口，测试驱动的也是它。模块图的分派只在 `StatsModuleDrawing`，页面与真画法缩略图同读；统计只有一份（`SubjectStatsTab.calculated`）。首次可见事件启动进场，播放记录和进度留在懒列表上方按模块id持有，滚回、切tab或换画法不重播。几何走 `LocalAppMotion` 空间规格，透明度走效果规格，错开经 `stagger`，移除动画直接画终态。

## 统计图：画与点一份几何

可点的统计图只经 core:ui `chart/TapChart.kt` 的 `TapChart`，模型写在那个文件开头，新图照着做：

- 一个标记（`ChartMark`）就是图上画出来的一块，自己画自己：`draw` 拿到的就是气泡指着的那一块（`anchor`）。图不另算一份
  命中区去对画出来的东西，也不在标记之外把这一块再画一遍。
- 点中谁只由 `markAt` 按画出来的那一块判：手指在哪一块上就是哪一块；`hit` 只把这一块周围的空白（整列、整行、点周围一圈）
  也算给它，几块都要同一处时归画出来离手指最近的那一块，不看谁后声明。图里不另写命中判定。
- 画出来的形状不是矩形、给不出「这一块」的（环的段、交叠着横贯全宽的几条线、流向的带子）：标记是图例里它的那一行，
  形状画在 `ChartLayout` 的 `backdrop`。轴、刻度、参考线这些点不到的也画在 `backdrop` / `foreground`。
- 「点哪里出哪一块」的回归测试点页面画的那张图（密的柱、密的点各一条：`LifeTimelineHitTest`、`StatsPlotsTapTest`）；
  几何函数的测试只说明那个函数给的矩形。

## 可点块、FAB、海报：各一种实现

- 含文字的可点块（封面 + 标题的卡、头像 + 名字……）必须用 `PressArea`（core:ui）：整块可点，只有水波纹裁成圆角，内容不裁。
  能选上、再点取消的多选格（进度页筛选格）用它的开关重载 `PressArea(checked, onCheckedChange, …)`。不写透明的 `clip(...)` + `clickable`
  包一块带文字的内容——圆角会切掉标题；也不用可点的 `Surface` / `Card`（`onClick` 或 `checked` 重载）包文字。
- FAB 在右下、16dp 边距（M3）。只有两种摆法：Scaffold 的 `floatingActionButton` 槽（Scaffold 管边距与系统栏），或
  `SelectionFabMenu`（自己定位）。不给 FAB 自己加 `align` / `padding` / `offset` / 导航栏内边距。列表给 FAB 让出的底部空间只有
  core:ui 的 `FabClearance`。FAB 不随滚动变形：带标签（extended）就一直带，纯图标就用 `FloatingActionButton`；不给 extended FAB
  传 `expanded`。规则在 `FabPlacement.kt`。
- 作品封面只由 `SubjectPoster`（core:ui）画。它把图和图上的一切（`overlay`：排名、徽标、渐变遮罩）裁进同一个 `shape`，只点封面
  走它的 `onClick`。不自己裁封面框、不在它的 modifier 上挂 `clickable`、不在它上面叠一层兄弟：没裁的那层露出直角。
  能长按的封面传 `mark`，形状取 `posterCoverShape`，按下、抬起、落地是同一段圆角动画。

门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_press_area.py`、`python3 tools/guards/check_cover_clip.py`、
`python3 tools/guards/check_fabs.py`。

## 屏宽：网格、按钮组、单行文字——各一种实现

小屏（约 360–380dp）与平板最先坏在页面自己定宽度的地方（个人页收藏掉到两列、好友头像挤成椭圆、搜索排序标签切成「…」、搜索提示折成两行）。

- 网格只由 core:ui `ContentGrid.kt` 画：整页按网格滚的用 `ContentGrid`，滚动列里的一段用 `AdaptiveGridRows`。每个 `GridCell` 声明最小格宽，
  列数跟着可用宽度走；两侧边距（一个值）与间距也在那里，页面只给上下。页面里不写 `LazyVerticalGrid`、`GridCells.Fixed` /
  `GridCells.Adaptive`、`chunked(n)` 行。唯一的例外是 `tools/guards/check_adaptive.py` 里登记并写了理由的（`GRID_ALLOWED`、
  `CHUNKED_ALLOWED`）：列数由内容本身决定（图片拼贴、图表），不由宽度决定。
- 文字输入是 `FormTextField`（表单；`AlertDialog` 里 `inDialog = true`）或 `ContainedSearchField`（搜索），都在 core:ui。页面里不用官方
  `TextField` / `OutlinedTextField` / `SecureTextField` / `BasicTextField`，起别名、写全限定名也不行。
- 下拉菜单是 `AppDropdownMenu`（core:ui），不用官方 `DropdownMenu` / `ExposedDropdownMenu`。菜单项是 `AppMenuItem`（先关菜单再动作；
  单选项在 `SelectableDropdownMenu`），不是裸 `DropdownMenuItem`。用间隔分组的菜单是 `AppDropdownMenu(groups = …)`（页面 ⋮ 与楼层长按菜单），
  不在一个容器里塞 spacer。
- 问「做不做」的对话框是 `ConfirmDialog`（core:ui）：确认在右、「取消」在左，收不回来的操作确认用 `error`（`destructive`），先关再做。
  它接收问的那个 `target` 并交给 `onConfirm`：关闭会清掉打开它的状态，所以动作只对拿到手的东西做，不回头读状态。不自己拼 `AlertDialog`；
  带输入或别的内容的对话框在 `tools/guards/check_dialogs.py` 里登记并写理由。选一天里的时刻只经 `TimeSelectDialog`（core:ui：M3 时间选择器放在
  官方 `TimePickerDialog` 里，12 / 24 小时制跟随系统，窗口矮时直接是键盘输入），不另拼 `TimePickerDialog`。
- 组件拿它所在位置给的宽度：网格里填满格子，行里由调用方传 `width`。不用 `wrapContentWidth` / `requiredWidth`（含 `Size` / `In` 变体）
  逃出父级的宽度：自己定宽、钉在更宽格子起点的卡，会把每格多出来的宽度全堆到最后一列右边。
- 连体按钮组里的文字经 `ConnectedChoiceGroup`，它从不把标签切成「…」：等宽 → 按标签定宽 → 收窄内边距 → 尾部选项收进溢出菜单（M3）。
  不把 `Text` 直接放进 `ConnectedButtonGroup`。
- 必须一行的句子从构造上就是一行；放不下时缩短可变的部分，不缩句子（搜索提示：`ContainedSearchField(placeholderValue = …)`）。
- 任何地方不用分隔点：主源码与资源 XML 里没有「·」（也没有「•」「・」「⋅」「∙」「‧」「･」，转义写法也算），也不画。单行元信息（`主角` `CV 名字`、`周四` `16 部`）
  经 `MetaText`（core:ui）：各部分分开传，它用约一个字宽的间隔（带额外字距的空格，随字号缩放）隔开。会换行的句子也不用分隔符：
  列举用「、」，分句用「，」「；」，插话用全角括号，或拆成两行。不自己拼元信息，不写第二种分隔。

界面改动在 380dp 下也看一遍。在认领的设备上（`ANDROID_SERIAL` 已设）：
`adb shell wm size 1216x2640 && adb shell wm density 512 && adb shell settings put system font_scale 1.1`；
复原 `adb shell wm size reset && adb shell wm density reset && adb shell settings put system font_scale 1.0`。

门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_adaptive.py`、`python3 tools/guards/check_separators.py`、
`python3 tools/guards/check_text_fields.py`、`python3 tools/guards/check_menus.py`、`python3 tools/guards/check_dialogs.py`。

## 状态栏：画到状态栏下面的头部用同一个顶栏

页面要么在状态栏下面（`statusBarsPadding`，或带 `TopAppBar` 的 Scaffold），要么从屏幕顶边画自己的头部（作品页、角色 / 人物页、用户页）。
第二种必须把 `HeaderTopAppBar`（core:ui）盖在头部上：它一直在最上面，头部在它下面时透明，内容滚到它下面之后填成页面底色 `surface`
（含状态栏），滚上来的内容永远碰不到时钟和电量，顶栏的动作一直够得着。填充量（0 = 透明，1 = `surface`）是头部的收起比例，
头部随列表滚时是 `rememberScrolledFill(listState)`。

顶上这一摞（状态栏一截、顶栏、钉在下面的一行如 tab 或放送表的日期选择、吸住的排序行）是同一个颜色：页面底色 `surface`，永远。
不随滚动变，不随顶栏让位变，没有「填充量」往下传（设计决策，不在 M3：bars.md 滚动时换成 surface container）。滚到这一摞下面的内容只靠
软边（PinnedEdge.kt）分开。颜色只来自一处：`TopAppBar` 用 `pageBarColors()`（HeaderTopAppBar.kt；随页面滚的头部栏用 `PageHeaderBar`），
`PinnedRowFrame` / `rememberStickyHeaderEdge` 画的页面 `surface`。不给钉住的行自己的背景，不为这一摞写 `surfaceContainer` 或
`lerp(surface, surfaceContainer, …)`。不把图标按钮画进头部本身，不另拼一个随滚动变色的透明 `TopAppBar`。

顶栏从不与钉在它下面的行（tab、分段开关、评论排序行）叠在一起：往下读时只有那一行留在顶上。顶栏随内容上滚让位、第一下回滚就回来
（M3 enterAlways）；状态栏那一截留着。列表的 `stickyHeader`（评论排序行）吸住期间，往回滚是在翻楼层：顶栏等那一行落回原位、上面的内容
重新露出才回来（PinnedEdge.kt 的 `TopBarHold`，由 `rememberStickyHeaderEdge` 登记；设计决策，不在 M3）。每个会让位的顶栏都经
`heldBy(hold)` 接滚动（`PageScaffold` 的栏与 `CollapsibleHeader` 的 `yieldingBar`），不用裸的 enterAlways 连接。钉在列表之外的行
（`PinnedRowFrame` 的 tab）上面没有可回去的内容，不扣住顶栏。`PageScaffold` 给每个二级页做了这些；有自己头部的页把头部放进
`CollapsibleHeader`（`collapsing`）、行放进 `pinned`（`HeaderReveal.OnScrollUp`，进度、探索、社区、放送表都是）；`HeaderTabsFrame` 里
`HeaderTopAppBar(frame = …)` 在头部收起后让位。不用 `pinnedScrollBehavior`。头部与钉住的行本身也接竖向拖动，和在内容里拖是同一份
收起状态（`CollapsibleHeader` 的 `headerDrag`）：头部再高也不只剩内容露出的那一截可拖，页面不给头部另加拖动。头部按内容量、不受屏高限制；
比可用高度还高时松手不吸附（全开、全收都露不出它的下半截），停在松手的位置。

最下面那一行钉住的，在内容滚到它下面时带软边，只在 core:ui `PinnedEdge.kt` 画；`PageScaffold` 页没有自己的钉住行时，最下面那一行就是
顶栏本身（`BarEdgeFrame`），有更低的行时由它接过软边。钉在内容上方的行经 `PinnedRowFrame`（`CollapsibleHeader` 已经是），带 `stickyHeader`
（或 `floorCommentsSection`）的列表带 `Modifier.stickyHeaderFade(edge)` 与 `overscrollEffect = edge.overscrollEffect`，来自
`rememberStickyHeaderEdge(listState)`（列表末尾的拉伸会把淡出连同吸住的行一起拉动）；不自己在钉住的行下面画阴影、分割线或渐变。
门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_status_bar.py`。

## ViewModel 状态与失败提示：各一种实现

几种并行的报错方式（状态 `error` + 「已显示」回调、事件 `SharedFlow`、sealed `Message` + 消费、页面自己的 `LaunchedEffect`）会各自漂：
有的页面从不显示错误，有的把一次写失败变成白屏。现在只有一种。

- 持有页面状态的 ViewModel 继承 `StateViewModel<S>`（core:common）：一个 `uiState`，`updateState { }`，`currentState`。不手搓
  `MutableStateFlow` + `asStateFlow` 做页面状态。`updateState` 的 lambda 不是 inline，suspend 的值（`toFailure()`）先算好。
- 要告诉用户的失败（写失败、要登录、内容已显示时刷新失败）发到 ViewModel 的 `val failures = FailureNotices()`（core:common）；页面用
  `FailureNoticeEffect`（core:ui）显示，这是通向 snackbar 的唯一路（`SnackbarFailureOnlyContractTest`）。不为一次性提示加
  `error` / `message` 字段加「已显示」回调。
- `FailureNotices` 属于 ViewModel，或 ViewModel 持有的控制器（`ComposerActions`）；数据层不创建、不持有，ViewModel 可以把自己的那份当参数交给
  辅助函数（`launchWrite`）。放在单例里，它会把已关页面的重试、上一个账号的提示交给下一个打开页面的人。活过页面的工作把失败暴露成流，
  由每个 ViewModel 转发（`EpTapCoordinator.errors`），或在用户无能为力时什么也不显示。门禁：`python3 tools/guards/check_viewmodel_rules.py`。
- 失败不在半路丢掉。写失败时保留用户输入的内容：编辑器或面板只在写成功之后才关、才丢草稿。内容已显示时刷新失败要到 `failures`。
  裸的 suspend 读取（DAO、DataStore、缓存）不在 `viewModelScope` 协程或应用级循环里不包地跑：它的异常会结束那个协程或循环；
  经 `outcomeOf` 或 `launchSection`。应用级、活过页面的跟随（登录状态、偏好同步、历史维护、未读数）读失败时不以 `.catch` 收尾：
  流到那里就结束了，这次进程里之后的登出、换号再没人跟。读失败后退避重读只经 core:data `recoverStorageReads`，跟会话的读
  `TokenStore.recoveringSessions`。
- `state.error` 只表示「这个页面或分区加载失败、没有东西可显示」，用 `RetryError` 画，从不被消费。内容已在屏幕上时发到 `failures`。
- ViewModel 发出的文字是字符串资源（`failures.post(R.string.x, arg)`），不是字面量。
- 失败是 `Failure`（core:foundation），不是字符串。异常 → `Failure` 只在 core:network `toFailure()`；`Failure` → 文案只在 core:ui
  `Failure.text()` / `notice()`。数据层抛 `FailureException` 或返回 `Failure`；谁也不解析、拼接、嗅探失败文本（没有
  `message.contains("404")`，没有「只显示含 CJK 的消息」过滤）。
- 一次性调用返回 `Outcome<T>`（`Success` / `Failed`）；先缓存后网络的流发 `Load<T>`（`Pending` / `Ready(fromCache)` / `Failed`）。
  要从流里拿一个答案：`settled()`。不给一次性结果加「loading」分支。
- ViewModel 不写 `try` / `catch`：包进 `outcomeOf { }`（core:data），它重抛取消、把会话切换映射成 `Failure.SessionChanged`、其余经
  `toFailure()`。数据层同样：core:data 里只有 `util/OutcomeOf.kt`、`token/SessionRequestHelper.kt`、`util/SharedFetch.kt` 捕获
  `CancellationException`，`runCatching` 只出现在 `tools/guards/check_cancellation.py` 列出、写了理由的非 suspend 解析器与文件操作里
  （`tools/ci/check.sh lint`）。feature 代码不写 `catch (`、不碰 `TokenStore`，`AppEntries.kt` 没有 `koinViewModel`，没有 Koin 注解，
  不嗅探 `message?.contains(`，`Dispatchers.setMain` 只出现在 `TestMain`：`python3 tools/guards/check_viewmodel_rules.py`。
- 一次操作一个账号。活过页面、在应用作用域里的东西（`EpTapCoordinator`、`CollectionMarkCoordinator`）每个操作取一次 `AccountSession`，
  随操作带着，传给这个操作的每次读写（`expectedSession` / `session`），中途登出或切号让这个操作以 `Failure.SessionChanged` 失败，
  而不是写进下一个账号。操作安排的延后工作（延时保存草稿、登出后的清理、等锁的保存）在动手时取账号，不在执行时取。它只调用返回
  `Outcome` 的函数：一个失败的操作不结束它的循环、不让进程崩。跨一次请求的仓库加载（`HomeRepository.getHomeProgress`）从第一次读到
  最后一次写钉同一个会话。按账号的界面状态（`AppShell` 导航与保存的页面状态）以账号身份为键，不以直接切号时仍为 `true` 的 `isLoggedIn` 为键。
- 结果不落到比它新的状态上。`await` 之后要落的东西先查开始以来发生了什么：账号（`expectedSession`）、这期间的写入（写推进版本，之前开始的读丢弃）、
  它属于的面板（`ComposerActions` 的代次：旧请求可以结束，但不改后来打开的面板）。别的页面能改的状态（屏蔽名单、关注状态）按流观察，
  不在页面加载时读一次。写在途时跟随收到的那一拍不留到写完再落：它可能是写入之前的值，写记下的新值那一拍不保证在写结束之前到；
  写结束时重新接上跟随，新的一轮先读一次写之后的值（个人页 `refollowRelations`）。不拿界面显示标志（`PagedList.isRefreshing`）或合法的领域数字（`sort = 0.0`）当内部在途门闩或「空」哨兵：
  空与在途用显式的代数类型（`sealed interface`）或专用的内部字段。
- 自己加载的页面分区（作品、角色、关联、评论……）在页面状态里是一个 `Load<T>` 字段，不是 `items` + `isXLoading` + `xError` 三件套。
  只由 `StateViewModel.launchSection(key, …)` 填（流原样；一次性调用经 `loadOnce { }`；它取消同一个 key 的上一轮、把藏在已显示内容后面的失败
  自己发到 `failures`；`cancelSection(key)` 停一轮），内部跑 `collectSection`，刷新或重试失败时保留已显示的内容（`Load.advance`）。
  三种状态从 `sectionStateOf(load)`（core:ui）画，这是唯一的分区判定。整页内容就是一份数据的页面（放送表）同理：一个 `Load<T>`，
  只在它的输入合并处写，从 `sectionStateOf` 画；不要 `isLoading` + `error` + 数据并排。
- 分页的列表（按偏移或游标）是一个 `PagedList<T>`，只由 `Pager`（core:common：首页、重试、续取、取全、作废旧请求、本地编辑）取。
  不手搓游标 + `isLoadingMore` + `loadMoreError`。值住在页面状态里；跨页面共享的列表（时间线各 feed）住在拥有它的仓库里、由仓库跑 `Pager`。
  游标 feed 给 `Pager` 页号当位置，页号 → 游标的表放在取数旁边（`TimelineRepository`）。整页只有一张分页列表的用 `PagedListPage`，
  页面里的一张分页列表用 `loadMoreFooter(list, …)`。
- 页面从 `appEntries` 传下来的 `isLoggedIn`（应用的会话状态）知道登没登录；ViewModel 不为此读 `TokenStore`。未登录点写操作（收藏、标进度、
  贴贴、关注、绝交、举报、发布……）去登录，永远经 `LoginGate`（core:ui，`rememberLoginGate(isLoggedIn, onLogin)`）——不内联
  `if (isLoggedIn) … else onLogin()`，不弹「请登录」提示。共享组件（楼层、时间线面板、章节网格、收藏按钮）把写操作声明为 `Gated<…>`，
  只有 `LoginGate.guard` 能造，不经门把 ViewModel 方法传进去是编译错误。
  登录表单只在登录页 `LoginScreen`（feature:login）：推到当前 tab 上的 `Route.Login` 页，带返回键，一屏放下不滚。对游客没东西可显示的页
  （首页、进度、自己的个人页、我的帖子）未登录时是同一个 `SignInPrompt`（feature:login：一句话加一个打开登录页的按钮），在 `appEntries` 选；
  页面不画自己的未登录态。到处显示同一个登录状态：`LoginViewModel` 住在 Activity 上（`LoginScreen` 从 `LocalActivity` 取），整个应用只有
  一次登录尝试、一个验证码；不给登录页自己的 ViewModel。任何登录（浏览器、密码、令牌）结束——成功、失败或取消——之前或期间开始的浏览器登录
  作废：每条登录路径都经过的那一个函数（`AuthRepository.login`）清掉它，回调在同一把锁下查状态，晚到的回调不替换登录留下的账号。
  门禁：`python3 tools/guards/check_login_gate.py`。用户页是不是自己的，在 `appEntries` 用 `SignedInAccount.isMe`
  （`SessionState.Authenticated` 带的账号）判一次：指向自己用户名的路由打开自己的页（`username = null`）。「这一条是不是我的」在数据层、
  按当前会话映射时判一次（`TimelineItemBo.isOwn`），页面与仓库自己的权限检查都读这个字段。

## Compose 的老坑

每一条都至少上线过一次、后来在设备或审查里才发现。门禁看不见它们，每处界面改动对着这张表过一遍。修好的写法在点名的地方——复用，不重推。

- **忙时拒绝的请求记住，不丢。** 只触发一次的东西（续取页脚进入 idle 时问一次）永远不能撞上静默的空操作：列表重载时要的活在重载落定后做
  （`Pager.reloading`）。工具同理：数不到东西的计数器报错，不报零。
- **一次性反馈属于事件，不属于状态。** 点一下转一圈、跳到之后闪一下、计时器：在事件发生处启动（`onClick`、`FloorJump.jumpTo`），上一个也在那里取消。
  `LaunchedEffect(someState)` 在组件重新进入时重放，同一个值设两次时什么也不做。
- **只在自己量出的边界里画。** 离屏层裁到边界：`alpha < 1`、`Crossfade` / `AnimatedContent` / `AnimatedVisibility` 的淡入淡出、跑着的 `expandVertically`。
  伸出去的徽标或条是占位的一部分（`EpButtonFootprint.kt` 的 `epButtonFootprint`），不用 `offset` 或负的 `place` 推出去。
- **按下标取子节点的自定义 `Layout` 把条件内容包进 `Box`**：隐藏的 `AnimatedVisibility` 不产生节点，`measurables[1]` 崩。
- **知道官方组件按什么记忆。** M3 `Button` / `ToggleButton` 按 `shapes` 对象记形状动画，换一个 shapes 对象就跳。目标形状取决于自己的状态时，
  自己持有变形、只给按钮一个当前形状（`EpContainerShape`；`MorphingShape.kt` 的 `rememberMorphingShape`）。`semantics { disabled() }` 不禁用点击：
  门闩在动作本身（`CollectionFabState.busy`）。
- **按属性选动效。** 几何（尺寸、位置、圆角、长度）是空间动效；颜色与透明度是效果动效。几何变化取 `LocalAppMotion.current.scheme` 的空间规格。
  `animateContentSize` 在动画中途裁到当前尺寸，过冲的弹簧在缩小时切掉尾部内容：它永远取那套方案里的空间规格（M3 standard，不过冲；
  `check_motion.py` 要求给规格参数）。`cachedNetworkCall` 的流在调用点不加 `.flowOn(…)`（它加一层缓冲，过期的缓存读会多发一次没用的请求；
  要离开主线程就在 `transform` / `map` 里 `withContext`；门禁 `python3 tools/guards/check_cached_network.py`）。会变的数字经 `RollingText`
  （跟手指的读数不滚：`RollingText` 的 `animate = false`）。从无到有淡入的颜色起点是 `target.copy(alpha = 0f)`，不是 `Color.Transparent`
  （透明黑经过深灰；门禁 `python3 tools/guards/check_color_anim.py`）。
- **实时进度读时钟，不读动画。** 显示剩余真实时间的条每帧从单调时钟算比例（`commitRemainingFraction`）：动画时长会被系统的动画缩放改掉，截止时刻不会。
- **骨架与真内容共用一个框架。** 占位由它代表的那个组件定尺寸（把那个组件渲染成不可见、不可点，如 `CareerCard.kt` 的 `CareerCardSkeleton`），
  不猜 dp。几何测试在无界（可滚动）容器里按 1× 与 2× 字号量。还没到的内容（一页、一个面板、一个分区、重排中的列表）画成这样的占位，不画转圈；
  三种状态来自 `sectionStateOf`。加载指示器只许出现在 `PageLoadingSkeletonContractTest` 列出的函数里（按钮里、续取页脚、慢启动提示）。
  没东西可显示的列表或分区画一个 `EmptyLine`（core:ui），不自己写带样式和边距的 `Text`（`EmptyStateContractTest`）。
- **懒加载行回来时还是离开时的大小。** 列表项第一次出现之后决定它高度的东西（图片加载出的尺寸或失败、用户展开的折叠）用 `rememberSaveable`，
  不用 `remember`：项滚走时状态留着，滚回来第一帧就恢复。只留在组合里的话，项以占位高度重新进入、下一帧缩小；往回滚时它从顶上进入、
  缩出视口、被丢弃、再进入，列表在那一段来回循环（`BBCodeImages.kt` 的 `SingleImage`，`BBCodeImageReentryTest`）。还在加载的东西的占位
  与它代表的东西同大（`SmileyImage`）。门禁：`python3 tools/guards/check_image_result_state.py`。
- **要活过懒加载行的状态放在列表之上。** 行滚走就离开组合，进程重建后还可能一直是骨架，只有它持有的东西跟着没了。哪张卡或面板开着、
  一个选择、一次进行中的编辑：页面（或 `PageSheetLayer`）持有，以行的 id 为键。
- **存档键是定长的身份：** id，或内容的摘要（`BBCodeText.kt` 的 `contentDigestOf`），不是内容本身。屏幕上每个 `rememberSaveable` 都进同一个
  Bundle；拿内容当键，每个 saver 重复一次，长帖撑爆 binder 事务。
- **尺寸是预算。** 最小宽度（条的分段、尺子上的标签、一行里的 chip）会加起来：和放不下时按规则合并或丢弃，不重叠、不夹在一起、不裁最后一个。
- **「没在滚」不等于「停稳了」。** 设备上，手指抬起到惯性接手之间，可滚动的东西会短暂地不在滚动却还在两个位置之间；虚拟测试时钟从不显示这个空档。
  「停稳了」按它停在哪判（`TabPager`：不在滚动**且**停在整页上），跟随选中项的翻页也按同一条判；测试用打断惯性滑动复现空档（`TabPagerTest`）。
- **`rememberSaveable(inputs)` 只在它看见的变化上重置。** 输入在组件不在时变了（屏幕外的分页页、子页下面的页），恢复会把旧值带回来、不比输入。
  必须跟着键走的状态把键一起存，拒绝不匹配的恢复（`KeyedSaver.kt` 的 `keyedListSaver`）。
- **存下的值按它在组合里的位置找回，位置是哈希出来的。** 两件事会在存与取之间改变哈希，都让它们下面的每个 `rememberSaveable` 丢失：
  `hashCode` 是对象身份的 `key(…)` / 懒列表键（枚举、普通 `object`：每个进程不同，进程被回收后什么也恢复不了），以及分页器滚动时预组合
  下一页期间第一次组合的内容（嵌套的懒列表项拿到的位置与直接组合时不同，Activity 重建后什么也恢复不了）。分页键与懒列表键是字符串或数字，
  不是枚举或对象（`TabPager` 的 `key` 收 `String`，枚举传 `name`）；`TabPager` 只在页当前或在屏幕上时组合它的内容（`rememberPageShown`），组合过的页翻走也留在组合里。
- **手势只认开始时的修饰符。** 手指按着时在节点上增删 `pointerInput`、或改它的键，取消这个手势（长按丢了）。要对「当前目标」区别对待，
  给每个节点同一个修饰符、在事件里比 id（`EpisodeTileTips.kt` 的 `reportTileTouch`）。
- **报出去的值晚一帧回来。** `onValueChange` 到父级状态，新值在之后的重组才回来——晚于下一帧的输入与动画。用户拖或甩时不跟传回来的值
  （它把控件往回拽、杀掉惯性），自己的动画标记为「在动」、途中不报（`TileRuler.kt` 的 `moving`）。去重在手势里做（`RatingTiles.kt` 的 `reported`），
  不对着传回来的值或它的 `rememberUpdatedState` 做：两者晚同一帧，一步会报两次。测试把值晚一帧交回来复现，并断言代价（惯性滑了多远）。
- **自定义 `Layout` 报的尺寸在约束之内。** 更大的 `layout(w, h)` 会被钳住、内容按钳住的盒子居中，一半溢出从顶边漏出来。用
  `constraints.constrainHeight` 钳（或像 `TileStyle.kt` 的 `TileLabel` 那样按约束自己的尺寸排），放不下的丢掉（`TileLabel` 丢日期行）。
- **固定几行的文字不用 `minLines`。** 它按拉丁字母的行高补空出的行，汉字因字体回退每行略高：一行的中文标题比两行的矮一截（`labelMedium` 两行约 1.3dp，字号越大差得越多），骨架换成真内容时下面的东西上跳，同一排里一行与两行的卡片也不齐。用 `Modifier.heightIn(min = rememberTextLinesHeight(style, n))`（SkeletonText.kt，与骨架同一个样例字量）。输入框的 `minLines` 不受影响。
- **一行字共用一条基线。** 一种样式一个 `Text`：中日文与拉丁字母分成两个 `Text`、互相居中，会差 4–5 px（元信息之间的间隔是同一个 `Text` 的一部分：
  `MetaText`）。一行里不同尺寸（大数字与单位、分数与它的词）用 `Modifier.alignByBaseline()` 对齐，不用在一个字号下调好的 padding；
  测试在 2× 字号下比 `FirstBaseline`（`CollectionSheetTest`）。
- **测试时钟只在被告知时走。** 测试里过真实时间（`Thread.sleep`、`delay`）屏幕上什么也不动；自动推进开着时每次同步把动画直接跑到头，
  看不到动画中间的帧。看动效要关 `mainClock.autoAdvance`、逐帧步进（`EpisodeMotionRecordingTest`），或录应用。
- **分成一组塞进一个懒列表项的几行在同一帧组合**（出过 OOM 与 ANR）：一行一个 item。
- **测量时不读嵌套的对齐线。** M3 列表项的 supporting 槽只放一个 `Text`；多行内容放进 details。
- **会增删的项按位置组合时要有 key。**
- **`animateContentSize` 包住「先量再定」的布局会动画它自己的重排**：只动画用户引起的变化。
- **页面不透明**（预测返回在下面画上一页），背景只来自 `rememberPageSurfaceNavEntryDecorator`。
- **面板的 saveable 状态在 `PageSheetLayer` 自己的 `SaveableStateHolder` 里。** 正在滑下去关闭的面板不吞掉下一次点它的触发器：每次打开请求
  开一个新面板，正在关闭的只关自己。
- **隐藏但已组合的指示器**（收起的下拉刷新指示器）还在无障碍树里、会盖住节点：显示之前不进语义。
- **桌面小组件的布局在宿主里量，类的身份在压缩产物里核。** 小组件经 Glance 转成 RemoteViews：共享行的可用宽度由调用方扣掉真实 frame 边距，
  布局验证量实际宿主中的文字与操作区域。以运行时类名作为组件身份的代码还要核对压缩产物，debug 下各是各的类不能证明 R8 后也是。

## 动效：一套，一个来源

只有一套动效。唯一的动效设置是外观页的「简单返回动画」（默认关）：开着时返回手势与三键导航的返回键不演缩小预览，跟着进度走普通返回的整页滑走
（`chogumiPredictivePopTransition` 的 `simpleBack`，只在那一处分支）。系统关掉动画时（动画时长缩放 0、无障碍「移除动画」）一切瞬时。`ChogumiTheme` 映射一次（core:ui `theme/Motion.kt` 的
`appMotionFor`），提供两份：M3 standard 方案作为 `MaterialTheme.motionScheme`（每个 M3 组件、每个页面跟它走），整套 `AppMotion` 作为 `LocalAppMotion`。

- 动画规格只来自 `MaterialTheme.motionScheme` 或 `LocalAppMotion.current`（`scheme`、`shake`、`page`）。全局方案是 M3 standard（几乎不过冲）。
  唯一的例外是 `LocalAppMotion.current.expressive`（读主题方案的 M3 组件用 `ExpressiveMotion { }`）：章节网格点一下的弹跳与完成时过冲到 1.1、
  长按环的扩张、选择 FAB 菜单，列在 `python3 tools/guards/check_motion.py` 的 `EXPRESSIVE_ALLOWED` 里。系统关动画时它也瞬时。
  不用它做 `animateContentSize`（过冲在缩小时切掉尾部内容）。
- 调用点不写 `spring` / `tween` / `keyframes`，不取 `MotionScheme.standard()` / `expressive()`：规格只来自那两处，几何与颜色按属性选空间 / 效果，
  系统的关动画开关只在 `AppMotion` 映射一次（瞬时规格、不错开、滚动直接跳）。动画 API 的规格参数都显式传（缺省就是 Compose 自带的弹簧）：
  `animate*AsState`、`animateTo`、`animateScrollTo`、进出场（`fadeIn`、`slideIn*`……）、`Crossfade`、`AnimatedContent`、`Transition.animate*`、
  `animateContentSize`、`animateItem`。
- 错开元素的延时经 `LocalAppMotion.current.stagger(millis)`，关动画时为 0。
- 页面过渡按 `.agent-skills/m3-expressive/references/transitions.md`，只定义在 `ChogumiTransitions.kt`（`NavDisplay` 的规格都调它；
  时长与缓动来自 `PageMotion`，页面从不自己动画进出）：前进 / 后退 = 前进后退的视差形式，是 M3 安卓默认（页面滑动时淡出）之外的设计决策
  （视差是 iOS 默认）——新页不透明、横跨全宽滑入盖住旧页，旧页按同一曲线移动四分之一宽度；后退反过来，没有淡出。切 tab = 穿越淡变，
  预测返回 = 两页都不透明。作品封面打开它的作品页是唯一的容器变换（`SubjectPoster` 是起点，`PageExpand.kt` 是唯一写 `sharedBounds` /
  `sharedElement` 的地方）；从作品页打开另一个作品页回落到前进 / 后退。一帧里从不出现两个半透明的页，过渡里从不用弹簧
  （`PageTransitionFramesTest` 逐帧步进）。

门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_motion.py`。

## 时间：一个钟，一个「现在」，一个「今天」

自己读系统时钟的页面会冻在自己的那一刻：详情还在昨天而进度在今天，「刚刚」永远不变老，放送列表和它的星期标题过了午夜各说各话。

- 墙钟只经 `AppClock`（core:foundation）读；系统实现是 `AppClock.SYSTEM`，别处没有。数据层的写入把时刻当参数接（每次操作取一个 `now`），
  不给默认值。
- 随时间走的东西订阅 `AppTime`（core:foundation，由 app 模块注册的唯一实例）：`now` 在前台每个整分钟跳一次，`today` 是业务日。ViewModel
  把 `today` 放进状态（或暴露 `AppTime.today`）再推导；组件读 `LocalNow`（core:ui）算相对时间。不 `remember { today }`，不为日期写每页一个 `delay()` 循环。
- 两个时区，各一个入口：业务日期（进度、「今年」）用 `businessDateOf`（UTC+8）；帖子或动态的显示日期用 `displayZone()`（设备时区）。
  放送日（每日放送按它分天、标「今天」、画「现在」线：北京时间 4 点翻页，深夜番归前一天）只经 core:model `airingNowOf(now)` 从 `AppTime.now`
  推，只用在放送表页；时刻经 `airingMinuteOf`，星期与 bgm.tv 对不上就是「时刻未知」，不猜。
- 倒计时的截止用单调的 `commitClockNanos()`。

门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_clock.py`。

## 导航与接线：各一种实现

导航状态、路由 → 页面的接线、bgm.tv 链接解析各只住在一处：没有抄来的导航配方，没有包打一切的 action manager，没有第二个链接解析器，
没有一半由 `AppEntries` 接、一半自己接的页面。

- `AppNavigator`（core:ui）是唯一的导航状态：每个 `TopLevelDestination` 一条返回栈，`navigate(Route)`，`goBack()`。顶层路由切 tab，
  其余路由推到当前 tab 上。
- 路由 → 页面只在 `appEntries`（app 的 `AppEntries.kt`）；`AppEntriesTest` 在路由没有页面时失败。页面接语义回调（`onSubjectClick: (Int) -> Unit`），
  不给默认值，漏接是编译错误；页面有自己的 ViewModel（`koinViewModel { parametersOf(...) }`）。`AppEntries` 从不创建 ViewModel。
  feature 不构造具体 `Route`，也不接收 `Route` 导航回调；由 `python3 tools/guards/check_viewmodel_rules.py` 统一检查。
- bgm.tv 链接变成路由只在 `inAppRoute(url)`；渲染器经 `LocalInAppLinkHandler` 导航。别处不解析 bgm.tv URL。反方向（一个实体的网页）只由 `BgmWeb`
  （core:model）拼，它和 `inAppRoute` 共用路径名；不手写 bgm.tv URL。网页只经 `LocalUriHandler.current.openUri` 打开（壳层提供
  `CustomTabUriHandler`：Custom Tab，不支持的浏览器当普通页打开）；不自己起浏览器 intent。分享只经 core:ui `Share.kt`：链接用
  `shareLink(title, url)`（文字 = 标题一行 + 链接一行），文件用 `shareFile(uri, mimeType)`；不自己拼 `ACTION_SEND`。
  门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_web_links.py`。
- 复制只经 core:ui `TextCopy.kt`（`rememberTextCopier`；长按复制 `copyOnLongPress`），不自己调 `setClipEntry`；成功不提示。
- 页面顶栏的 ⋮ 是 core:ui `PageMoreMenu(PageLink(title, url))`：网页与分享在前，页面自己的项隔一个间隔跟在后面（`extra`）。链接来自 `BgmWeb`，
  标题来自 `displayNameOf`（加载中为空：分享就只有链接）。不画 `more_vert` 按钮，不另做一套页面菜单；门禁：`python3 tools/guards/check_menus.py`。
- 角色与人物共用路由（`Route.MonoDetails(monoType, id)` 等）：把类型传下去，不按它分支选路由。
- 每个 feature 在自己的 `di/<Feature>Module.kt` 注册 ViewModel；`App.kt` 只列模块。带导航参数的 ViewModel 注册成
  `viewModel { params -> X(params.get(), get()) }`——`viewModelOf` 会到图里找参数（含有默认值的），页面一开就崩。app 的编译检查整张图
  （Koin 编译器插件，经 `chogumi.koin` 应用）。
- 注册只用经典 DSL；没有 Koin 注解（`@InjectedParam`、`@Named`……），没有 `koin-annotations` 依赖。插件自己的 DSL（`viewModel<T>()`）不是选项：
  1.2.1 的全应用检查看不见 feature 模块的 `@InjectedParam` 和默认值，把每个导航参数都报成缺依赖。

## 网络线路：一种实现

连 bgm.tv 走哪条线路（系统网络、直连、代理）只在 core:netroute：设置、核心起停、代理、对请求的改写都在那里。线路只有一个值
（`RouteMode`）：直连与代理互斥，都不开是系统网络；状态只经 `NetRoute.transition` 改。网络层只认 core:network 的
`OkHttpCustomizer`，三个接口客户端与图片加载套上 Koin 里登记的全部 customizer；页面只认 `NetRoute` / `RouteCheck` / `DirectRouteHint` /
`directRouteSupported`，代理地址与端口的输入规则只在 core:netroute（`proxyHostInput`、`proxyPortInput`、`proxyServerOf`）。直连核心
（Go，`echcore.aar`）只有 core:netroute 引用。直连与代理失败时都只报失败，绝不退回系统网络或普通 SNI（事实在[开发须知](dev/guide.md)
§1.11、§1.12）。门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_netroute.py`。

一次请求经过线路链只决定一次线路（`RequestRoute`）：改写、代理选择、连接检查共读，核心回环必须明确不经代理。
公开启动入口收到的系统通知参数不是授权；远程已读动作必须验证 `TokenStore` 签发的会话与提醒 id 凭据，不能只检查 extras 或 action 字符串。
