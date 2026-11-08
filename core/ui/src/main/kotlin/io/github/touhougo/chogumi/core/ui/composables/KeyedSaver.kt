package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * 跟着 [key] 走的存档（全 App 一种写法）：存「key + 值」，恢复时 key 对不上就不恢复（返回 null，由初始值新建）。
 *
 * 只把 key 当 `rememberSaveable` 的输入不够：恢复存档时不比输入。离屏的页（左右翻页翻走的、压在子页下面的）在离开期间
 * key 变了，再出现时会拿回旧值。网格的滚动位置（[rememberOrderedGridState]）与章节方格选中的段都经这里。
 *
 * @param save 值 → 能存进 Bundle 的一串值（不含 key）；null = 这一份不存（连 key 也不存：值是初始值时不占存档，
 *   一长串列表项各存一个 key 会撑大 Bundle），恢复时由初始值新建。
 * @param restore [save] 存下的那一串 → 值。
 */
fun <T : Any> keyedListSaver(key: Any, save: (T) -> List<Any>?, restore: (List<Any>) -> T): Saver<T, Any> = listSaver(
    // 交出空表时 listSaver 什么都不存
    save = { value -> save(value)?.let { listOf(key) + it }.orEmpty() },
    restore = { saved -> if (saved.first() == key) restore(saved.drop(1)) else null },
)
