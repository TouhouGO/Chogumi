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
