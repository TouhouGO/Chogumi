package io.github.touhougo.chogumi.core.common

/**
 * 惰性列表的 key：以 [keyOf] 的文本与该文本的出现次序为身份，长度编码使任意文本都不会与序号碰撞。
 * 不同文本的 key 不受排序、筛选其他值影响；相同文本的项按当前出现次序区分，重排或删除它们会改变序号。
 */
fun <T> occurrenceKeys(items: List<T>, keyOf: (T) -> Any): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val key = keyOf(item).toString()
        val nth = seen.merge(key, 1, Int::plus)!!
        "${key.length}:$key:$nth"
    }
}
