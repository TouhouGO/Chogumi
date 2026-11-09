# 第三方来源说明

Chogumi 以 GPL-3.0 发布。下表列出随 APK 分发的第三方代码与素材，与 App 的「设置 › 关于」页的清单对应；代码库版本以 Gradle 依赖目录（`gradle/libs.versions.toml`）为准，各自保留原许可证。App 随包附 GPL-3.0、Apache-2.0、MIT（含各库的版权行）、GPL-2.0 加 Classpath Exception、CC BY 4.0、BSD-3-Clause 的许可原文（`feature/settings/src/main/assets/licenses/`）。

## 随 APK 分发的第三方清单

| 标识 | 名称 | 许可或版权 | 来源 |
|---|---|---|---|
| androidx-jetpack | AndroidX 与 Jetpack Compose | Apache-2.0 | https://developer.android.com/jetpack |
| kotlin-kotlinx | Kotlin 与 kotlinx | Apache-2.0 | https://kotlinlang.org |
| ktor-okhttp-okio | Ktor、OkHttp 与 Okio | Apache-2.0 | https://ktor.io |
| coil | Coil | Apache-2.0 | https://coil-kt.github.io/coil/ |
| koin | Koin | Apache-2.0 | https://insert-koin.io |
| material-symbols | Material Symbols | Apache-2.0 | https://fonts.google.com/icons |
| material-kolor | Material Kolor | MIT | https://github.com/jordond/materialkolor |
| colormath | Colormath | MIT | https://github.com/ajalt/colormath |
| jsoup | jsoup | MIT | https://jsoup.org |
| slf4j | SLF4J | MIT | https://www.slf4j.org |
| go-gomobile | Go 与 gomobile（直连核心） | BSD-3-Clause | https://go.dev |
| desugar-jdk-libs | desugar_jdk_libs | GPL-2.0 with Classpath Exception | https://github.com/google/desugar_jdk_libs |
| bangumi-smileys | Bangumi 表情 | 版权归 Bangumi 番组计划，未声明开源许可 | https://lain.bgm.tv/img/smiles/ |
| platform-logos | 平台标志（Nintendo Switch、PS5、Windows、Xbox） | 图形 CC0 1.0（Simple Icons），商标归各自所有者 | https://simpleicons.org |
| android-robot | Android 机器人 | Google 创作并分享，按 CC BY 3.0 使用 | https://developer.android.com/distribute/marketing-tools/brand-guidelines |

## 运行时取用的第三方数据

不随 APK 分发、App 运行时从网上取用并显示给用户的数据，与「设置 › 关于」页「运行时取用的数据」对应，许可要求署名。

| 标识 | 名称 | 许可或版权 | 来源 |
|---|---|---|---|
| bangumi-data | bangumi-data（放送时刻） | CC-BY-4.0 | https://github.com/bangumi-data/bangumi-data |

- 用途：每日放送页按每周首播时刻分组、首页追更时间表给在追的作品标时刻；只用 `broadcast` 与 bangumi 站点 id 两个字段
- 取法：GitHub 仓库 master 上的 `dist/data.json`（原始文件地址），带 ETag、6 小时最多取一次（`AiringTimeRepository`）；数据不落入 APK，
  只在本机数据库里存条目 id 与首播时刻
- 许可：CC BY 4.0（原文随包，`CC-BY-4.0.txt`）；App 没有改动数据本身，只按条目取其中的首播时刻

## 直连核心（Go 与 gomobile）

- 内容：`core/netroute/libs/echcore.aar`（只含 arm64-v8a 的 `libgojni.so` 与 gomobile 生成的 Java 胶水类），由本仓库的 Go 源码
  `core/netroute/go/` 经 `tools/echcore/build.sh` 生成；链进去的是 Go 标准库与运行时，以及 `golang.org/x/mobile` 的绑定运行时
  （`go.Seq` 等类），版本钉在 `core/netroute/go/go.mod`
- 许可：Go 与 golang.org/x/mobile 都是 BSD-3-Clause（Copyright 2009 The Go Authors，原文随包，`BSD-3-Clause.txt`）；
  `core/netroute/go/` 里的代码是本项目的，随项目 GPL-3.0

## Material Symbols 图标

- 来源：Google Material Symbols Rounded，取自 `google/material-design-icons` 的固定提交
  （提交号与图标清单见 `tools/icons/material_symbols.py`，图标都由该脚本生成）
- 许可证：Apache License 2.0
- 位置：`core/res/src/main/res/drawable/*_24.xml`（矢量图，路径数据原样，只统一了文件外壳）

## Bangumi 表情图片

- 来源：bgm.tv 站内表情（`https://lain.bgm.tv/img/smiles/`），版权归 Bangumi 番组计划
- 位置：`core/res/src/main/res/drawable-nodpi/smiley_*.gif`，只内置点赞可选的 12 个，其余运行时按需下载
- 用途：在 App 内显示 Bangumi 用户发布内容里的表情与贴贴

## 平台标志

- 来源：Simple Icons 的路径数据（`nintendoswitch` 取自 13.0.0，`windows11` 与 `xbox` 取自 11.0.0，`playstation5` 与 `android` 取自 16.36.0），
  路径只把参数用空格分开（安卓的矢量解析读不了紧挨着的弧线标志），填色为该图标收录的品牌色
- 位置：`core/res/src/main/res/drawable/platform_*.xml`；用途：探索页「按平台浏览」的方块，标明这一块是哪个平台
- 许可：Simple Icons 的图形按 CC0 1.0 发布；Android 机器人由 Google 创作并分享，按 CC BY 3.0（https://creativecommons.org/licenses/by/3.0/）使用
- 商标：各标志是其所有者的商标（任天堂、索尼互动娱乐、微软、Google），本项目与它们没有关联，只用来标明平台；
  所有者要求撤下时从这里删掉

## 项目自有应用图标

- 位置：`core/res/src/main/res/mipmap-*/ic_launcher_*`
- 版权：项目自有素材，© 2026 TouhouGO；不属于第三方内容，不列入上表

## 仅存于源码仓库的 Bangumi API 契约

`tools/openapi/` 的合同快照来自 Bangumi 官方 API 定义，仅存于源码仓库，不随 APK 分发；v0 与 p1 的上游仓库、固定提交、文件与摘要记录在 `tools/openapi/sources.json`。服务端源码仅用于核实 API 行为，未将服务端实现代码移入客户端。

| 快照 | 上游仓库 | 上游许可 |
|---|---|---|
| v0（`tools/openapi/raw/v0/`） | https://github.com/bangumi/server | AGPL-3.0（上游固定提交里的 `LICENSE.txt`） |
| p1（`tools/openapi/raw/p1/`） | https://github.com/bangumi/server-private | 上游未声明许可：固定提交里没有许可文件，`package.json` 也没有 `license` 字段；本项目不为它另行声明许可 |
