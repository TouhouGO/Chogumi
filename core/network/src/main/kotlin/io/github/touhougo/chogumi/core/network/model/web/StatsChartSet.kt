package io.github.touhougo.chogumi.core.network.model.web

/**
 * 网页「透视」页里的一张图：评分 1–10 分在某个维度上的拆分。
 *
 * [counts] 的键是网页给这一维度各组的编号（如收藏状态 `"1"`…`"5"`、放送阶段 `"1"`…`"3"`），值是这一组
 * 1–10 分各多少人（下标 0 是 1 分，恒为 10 项）。键的含义由使用方按维度解释，这里不翻译。
 */
data class StatsChartSet(val counts: Map<String, List<Int>>)
