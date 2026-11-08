package io.github.touhougo.chogumi.core.model

/** 乐观收藏后的人数：收藏 +1、取消 −1（不低于 0）。角色 / 人物页与目录页共用这一条。 */
fun collectCountAfter(count: Int, collect: Boolean): Int = if (collect) count + 1 else (count - 1).coerceAtLeast(0)
