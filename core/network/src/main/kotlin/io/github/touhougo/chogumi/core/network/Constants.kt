package io.github.touhougo.chogumi.core.network

/** v0 API */
const val API_V0_HOST = "https://api.bgm.tv"

/** v0 条目搜索路径：是 POST，但只读，也是重试白名单里唯一的 POST（见 NetworkModule） */
const val V0_SEARCH_SUBJECTS_PATH = "/v0/search/subjects"

/** NextAPI（p1） */
const val NEXT_API_HOST = "https://next.bgm.tv"

/**
 * bangumi-data（社区维护的每季放送时刻表，CC BY 4.0）的整份数据，直接取 GitHub 仓库 master 上构建好的文件：
 * 只缓存 5 分钟（jsDelivr 的 npm 地址在边缘缓存 12 小时），带 ETag 没变时回 304。
 * 不是 bgm.tv 的接口，不带令牌（[io.github.touhougo.chogumi.core.network.api.BangumiDataApi]）。
 */
const val BANGUMI_DATA_URL = "https://raw.githubusercontent.com/bangumi-data/bangumi-data/master/dist/data.json"

/** 本应用的源码仓库：正式版只在它的 GitHub Release 上发布。 */
private const val RELEASES_URL = "https://github.com/TouhouGO/Chogumi/releases"

/**
 * 最新一个已发布（不含草稿与预发布）的正式版附带的 release.json（tools/release/formal_release.py 写出）：GitHub 把
 * `latest/download/<文件名>` 跳转到那一版的同名附件。不是 bgm.tv 的接口，不带令牌（[io.github.touhougo.chogumi.core.network.api.ReleaseApi]）。
 */
const val LATEST_RELEASE_MANIFEST_URL = "$RELEASES_URL/latest/download/release.json"

/** GitHub 接口上一个版本（标签 `v<versionName>`）的 Release：只读它的说明（`body`），检查更新的版本与安装包仍以 release.json 为准。 */
fun releaseApiUrl(versionName: String): String = "https://api.github.com/repos/TouhouGO/Chogumi/releases/tags/v$versionName"

/** 一个版本的附件目录：标签是 `v<versionName>`（.github/workflows/release.yml 核对标签与包的版本一致），后面接附件文件名。 */
fun releaseDownloadUrl(versionName: String, fileName: String): String = "$RELEASES_URL/download/v$versionName/$fileName"
