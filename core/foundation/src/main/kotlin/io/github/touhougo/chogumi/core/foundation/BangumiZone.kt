package io.github.touhougo.chogumi.core.foundation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 业务口径的时区（UTC+8）：「今天」、放送星期、播出日零点、「今年」都按它算。
 * bgm.tv 面向中文用户，放送表按北京时间组织；在海外时区也不能把「今天播出」算成「昨天」。
 */
val BANGUMI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** [nowMillis] 那一刻在业务口径（[BANGUMI_ZONE]）下是哪一天。 */
fun businessDateOf(nowMillis: Long): LocalDate = Instant.ofEpochMilli(nowMillis).atZone(BANGUMI_ZONE).toLocalDate()

/**
 * 显示口径的时区（本机）：帖子、动态、加入日这类「谁在某一刻做了什么」，日子按看的人所在的时区写
 * （「昨天」「10 月 2 日」）。放送、进度、年份不用它，用 [BANGUMI_ZONE]。
 */
fun displayZone(): ZoneId = ZoneId.systemDefault()
