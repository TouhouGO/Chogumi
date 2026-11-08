# 流程：谁来定、合并前、设备、复盘

一次改动怎么被检查、合并与复盘，设备上的活怎么跑。代码规则在 [code-standards.md](../code-standards.md)。

## 谁来定

现阶段代码由 agent 写、审、合、发：做不做、怎么做（二选一、交互细节、先后、阈值、删不删死代码）由做事的 agent 自己定，在 PR 正文写理由，
不等所有者。所有者只管方向，和不可逆的事：改写历史、删远端分支、发版、花钱、对外发消息。写下的决定（规范、[开发须知](guide.md)、
[LESSONS.md](../../LESSONS.md)）不顺手翻；要翻就单独一个 PR 说明理由。

## 合并前：`tools/ci/check.sh`，按改动选范围

`tools/ci/check.sh` 是所有检查的唯一入口；CI（`.github/workflows/ci.yml`）跑的就是它，本地一次与 CI 永远一致。谁凭什么合：

- **我们自己的 PR**（所有者或项目线程开的）：下面范围的本地一次跑绿就合，不等 CI。CI 继续在 PR 上跑：合入后红了，立刻开新 PR 修。
  Gradle 拉不到依赖的会话没有本地结果可凭：等 CI 绿再合，或把这一跑交给 Mac 上的会话。
- **外部贡献者的 PR**：CI（`guards`、`tests`、`builds` 三个任务）全绿才合。

范围：

- **PR 上**：`tools/ci/check.sh changed origin/main`。先跑门禁（`lint`）与 OpenAPI 合同（`contracts`），再由 `tools/ci/scope.py`
  （唯一的范围判定）按改动的文件选下一步，并打印理由。改动的文件是工作区相对分叉点的全部改动：已提交、没提交的，和没被忽略的新文件
  （Gradle 跑的是工作区，范围也按工作区选）：
  - **整仓**（全部单测、debug 包、每个测试包、R8 的 benchmark 包、lint）：改到构建本身（build-logic、`gradle/`、根构建脚本、
    `gradle.properties`）、只进正式包的东西（`app/build.gradle.kts`、R8 规则 `*.pro`、`app/src/release/`：按模块不打 release 与
    benchmark 包）、Room schema、主题或 tokens、检查本身（`tools/ci`、`.github/workflows`、`.github/actions`），或没有单元测试的 Gradle 模块；
  - **按模块**：改到的模块，加上单元测试经 `RepoFiles` 按路径读到改动文件的模块（改名算两个路径）；然后作为
    `tools/ci/check.sh modules <模块>…`，跑这些模块、依赖它们的模块、单测扫整仓的模块（`tools/ci/affected.py`）的单测与 androidTest 编译，
    以及 debug 包；
  - **到此为止**：只改模块以外的文件（文档、`tools/`、`.github` 里的 issue 与 PR 模板），也没有单元测试读它们。
- **合入 main 之后**：整仓 `tools/ci/check.sh`，兜住按改动漏掉的（经拼出来的路径读文件的测试、只有测试包才感觉到的公开 API 变化）。
  公开后每次合入都跑；私有仓库的 Actions 按分钟计费，手动触发（`workflow_dispatch`，mode 选 `full`）。main 红了先修它，再合别的。
- **推之前**：本地跑同一条 `tools/ci/check.sh changed origin/main`，来回调一个模块时用 `modules <模块>…` 更快。范围判错了改 `scope.py`，
  不在某个 PR 里绕过去。红的 CI 就是红的测试（[代码规范](../code-standards.md)「测试」）：不静默重跑。

CI 把一轮拆成三个并行任务：`guards`（门禁与合同，不用 Gradle）和 Gradle 检查的两半，`tests`（`check.sh --part tests`：单测任务）与
`builds`（`--part builds`：其余）。Gradle 构建缓存（`org.gradle.caching`）随 Actions 缓存走，只有 main 上的运行写、PR 只读：每天夜里
`check.sh warm` 编译全部模块、单测只演不跑（公开后每次合入后的整仓也写），PR 只重编自己改到的。本地不加 `--part`，一次 Gradle 跑完两半。
工作流里引用的外部 Action 钉到完整的 40 位提交号（Docker Action 钉 `sha256` digest），`actions/checkout` 写明 `persist-credentials: false`，
工作流顶层只给 `permissions: contents: read`（发版任务另给 `contents: write`）：
门禁 `python3 tools/guards/check_action_pins.py`。

workflow 只跑钉死的代码、不把令牌留在工作区：外部 `uses` 钉四十位提交 SHA（`docker://` 与 docker 型 local action 的镜像钉 `@sha256:`），
`actions/checkout` 写 `persist-credentials: false`，顶层权限只有 `contents: read`，只有 release.yml 的 release job 拿 `contents: write`。
门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_action_pins.py`。

测试数只抄这一跑最后 `count_tests.py` 的输出（CI 里在 `tests` 任务日志末尾），不沿用上一轮的数做加法。
计数只读 Gradle 登记的完整 JUnit XML：损坏、缺计数、计数矛盾、零条结果、全部跳过，或入选模块有测试源码却一份结果都没有，都不能报成功；
部分跳过单列，仍须在验证说明里解释。
模块是否有单测按实际 `Test` 任务判断，只有一个名叫 `test` 的生命周期任务不算。

### 攒批：发布前私有仓库的 Actions 按分钟计费

- 代码改动攒成一个 PR 再开，不一个问题一个 PR；一个线程一个 PR 仍然成立，但一个 PR 可以收几件相关的小事，正文逐件写为什么、改了什么、怎么验证。
- 攒改动期间 PR 开成草稿：草稿不跑 CI。准备合并时标 ready for review，CI 那时跑一轮，之后每次 push 再跑。
- 只改文档、`tools/`（不含 `tools/ci`）与 issue / PR 模板的 PR 只跑门禁（`scope.py` 判「到此为止」），随时可以开。`tests`、`builds` 两个任务照样起来，
  装好 JDK 与 Gradle 缓存、读一次依赖图才判出「到此为止」，每个也要几分钟。
- PR 上每次 push 取消还在跑的上一轮：改齐再 push，审阅意见攒着一次改完再推。
- 会判成整仓的改动（`.github/workflows`、`.github/actions`、`tools/ci`、构建脚本、`gradle/`、R8 规则、Room schema、主题）尽量并进同一个 PR。
- 公开之后 runner 免费，再提高频率。

### PR 正文

标题是一句中文：做了什么、关键点。正文按模板（`.github/PULL_REQUEST_TEMPLATE.md`）：为什么、改了什么、怎么验证、没做什么。「怎么验证」
就是[代码规范](../code-standards.md)「验收标准」的答卷：

- 跑了哪个范围、在哪个提交上，贴 `count_tests.py` 的输出；
- 回归测试在改动之前的代码上红的那一行原文；
- 改界面：448dp、380dp 字号 1.1、800dp，浅深两色的截图，改动到达的每个页面、tab 与状态；
- 改共享原语：每个调用方为什么仍然成立；
- 没跑的逐项写「没跑」：仪器测试、真机、整仓检查、真实服务端。云端线程写「没跑：待 Mac」，并写清要跑什么（分支、模块、测试类、页面与宽度）。

**文档跟着改动走。** 改动涉及的功能、设置、架构，在同一个 PR 里更新 `docs/` 与[开发须知](guide.md)的对应段落（「改什么去哪里」的一行、
服务端事实、框架的坑、规范里点名的实现）。PR 正文「改了什么」列出改了哪些文档；涉及的文档没改的，写明为什么（例如只是内部重构、文档里没写到这一层）。

合并：不等审查 bot（Codex，开 PR 或标 ready 后约一分钟出结果），本地检查绿就合。它的每条行内意见事后都对着代码核实：成立的放进下一批修
（回归测试照样先红），在那条意见下回复修在哪个 PR 和红的那一行；不成立的回复理由。合并前已经出来的意见照样核实，成立的在这个 PR 里修。
审过之后又推了提交就重新审，合并时核对 head sha 是审过的那个；squash，删分支。合入之前落后 main 的分支先 `git merge origin/main`、
正常推、在合并后的结果上重跑选定的范围；推过的分支不 rebase。一个合入后的 PR 之后的改动是新的改动：新分支，永不 force-push。

### 共享原语与一个 PR 一件事

- **共享原语。** 改很多地方依赖的东西的含义（`cachedNetworkCall`、`Load.advance`、`collectSection`、`Pager`、常用测试夹具的签名）：PR 正文
  列每个调用方，一行写它为什么仍然成立；每一类消费方有一个测试（几条流的 `combine` 算一类）。夹具签名的改动自己一个 PR，其余分支之后合 main。
- **一个 PR 一件独立的事。** 不把一个 PR 叠在无关的 PR 上（发版管线叠在依赖升级上），不留一摞互相等的草稿：每个检查绿了就合，下一个合 main。
  攒批是一个 PR 里分条做几件相关的事，不是一摞 PR。
- **仓库只放代码与规则，不放工作记录。** 没有进度表、发现编号、状态文件，没有只在一台机器或一次会话里存在的路径（临时脚本、家目录、项目共享文件夹）。
  门禁：`python3 tools/guards/check_process_tags.py`。

### 新门禁

新门禁经 `tools/guards/sources.py` 读 Kotlin（`main_sources`、`test_sources`、`strip_comments`），不自己写 glob 或按行判注释。自测里每条规则
有一个违规样例和一个放行样例，再加几种明显的改写（起别名的 import、全限定名、拆行的调用、字符串里的 `//`）。放在 `tools/guards/` 下、名为 `check_*.py` 就会被 `check.sh lint` 跑到；
它有 `test_check_*.py`（`tools/ci/test_check.py` 查），并且能在 Python 3.9（Mac 的 `python3`）上跑（`tools/ci/test_python39.py`）。
文档之间的链接与 AGENTS.md 的路由覆盖由 `python3 tools/guards/check_doc_links.py` 守。
判断调用、类型与比较用共用的 `normalize_code`：别名与反引号名字（`` `TextField` ``）先规范化，注释和普通字符串不算代码，字符串模板里的表达式仍要检查。
确实检查文案或 URL 的规则保留字面量视图（`strip_comments` / `normalize_kotlin`），不把两种扫描混为一谈。

## 仪器测试与设备

仪器测试（`src/androidTest`）不在 `tools/ci/check.sh` 里，CI 也不跑它们：只编译（整仓编它们的 APK，按模块编它们的 Kotlin），抓得住签名与类型的腐烂，
抓不住坏掉的断言。

它们只在所有者的 Mac 上跑，模拟器池在那里；其他碰设备的事（装包、截图、录屏、logcat、点应用）也是。云端会话没有模拟器：改动需要设备时在那一步
停下，写清要跑什么（分支、模块、测试类、要看的页面与宽度），工作在 Mac 上的会话里继续。不在云端装模拟器，不跳过设备步骤，不把它报成做完了。

池、它的 AVD 与每台的锁只住在 `tools/device/device.sh`（[开发须知](guide.md) §4.2）。别处不写设备名或锁路径；门禁（`tools/ci/check.sh lint`）：
`python3 tools/guards/check_device_names.py`。
- 碰设备之前先拿一台：`SERIAL=$(tools/device/device.sh claim "<线程>，<分支>")`。它打印一台在线且空闲的设备并上锁；退出码 1 是都被占着——
  先做不碰设备的活，过会儿再拿。不拿、不删、不等别的线程的锁。
- 这一步的每条设备命令都带 `ANDROID_SERIAL=$SERIAL`（Gradle 与 adb 都认）。Mac 上常挂着不该碰的设备。
- 用完立刻释放，失败也一样，占用者写得和 claim 时一样：`tools/device/device.sh release $SERIAL "<线程>，<分支>"`。写得不一样的释放会被拒绝。
- 验收网络之前先确认设备的网络可信：模拟器是 `device.sh start` 起的（不指定 DNS，qemu 的 NI 为 0），`adb shell ping -c 1 next.bgm.tv`
  解析到的地址与 Mac 上 `dscacheutil -q host -a name next.bgm.tv` 一致（[开发须知](guide.md) §4.2）。解析错了也是网络失败的样子：那台上的失败不是应用的事，先修网络再验。（转正 2026-11-04）

改到 androidTest 测过的界面布局、tokens 或组件签名时：
- 提交前在认领的设备上跑改到的每个界面模块的连接测试：`ANDROID_SERIAL=$SERIAL ./gradlew :<module>:connectedDebugAndroidTest`
  （工具细节在[开发须知](guide.md) §4.2）。
- 测试数只从 JUnit XML 数。

截图或逐帧录屏是给人看的抓取，不是测试：标 `@VisualCapture`（core:ui testFixtures），图只经 `saveScreenshot` 写。默认的连接测试跳过抓取，
它们永远不计数；要跑用 `-Pchogumi.captures`（[开发须知](guide.md) §4.2）。其余每个 `@Test` 断言一件会失败的事——从构造上就成立的检查
（同一个固定窗口截两张尺寸一样）不是。门禁（`tools/ci/check.sh lint`）：`python3 tools/guards/check_test_assertions.py`。

## 复盘

每批合入之后（或每天一次）由合并的那条线程或协调者的例程把这批 commit 过一遍，只找三种事：

1. **返修的**：同一处第二次改（修完又修、合入当天就被下一个 PR 改回）。
2. **不该合的**：合入后才发现违反规范、推翻了写下的决定、带进了死代码或第二份实现。
3. **验证太糙的**：没跑的写成跑了、红的那一行没贴、截图没覆盖改动到达的页面、推断当成了实测。

每找到一件在 [LESSONS.md](../../LESSONS.md)「在看的」记一条，格式在那个文件开头：一句规则，为什么，出事的 PR 与后果，修好的 PR，做对的例子标
Done right，末尾 Seen N×（日期）。已有同一条就加一个日期。同一条在 3 个不同日子出现过，搬进 [代码规范](../code-standards.md) 或这份文件
（标「转正」与日期），LESSONS.md 里只留一行指过去。复盘发现的缺陷照常开 PR 修，不在错题本里等。
