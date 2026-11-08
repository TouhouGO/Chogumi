package io.github.touhougo.chogumi.core.data.repository

import android.content.Context
import io.github.touhougo.chogumi.core.model.DisplayScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面大小档位（[DisplayScale]）的唯一存处。
 *
 * 它不在偏好 DataStore 里：密度要在 Activity 的 `attachBaseContext` 里、第一帧之前同步定下来，DataStore 只能异步读。
 * 所以单独一个 SharedPreferences 文件（见同名工厂函数），建仓库时读一次（文件只有一个整数），之后读内存；
 * 随系统备份走（比例在别的设备上同样成立）。
 *
 * 存的是原值，不收敛：这块屏上实际用哪一档由 [DisplayScale.effective] 按屏宽定，换到大屏时用户选的放大档还在。
 * [initial] 是读到的存值，[persist] 把新值写回去。
 */
class DisplayScaleRepository(initial: Int, private val persist: (Int) -> Unit) {
    private val stored = MutableStateFlow(initial)

    /** 存下的档位；[set] 之后立刻是新值。 */
    val percent: StateFlow<Int> = stored.asStateFlow()

    fun set(percent: Int) {
        require(percent in DisplayScale.OPTIONS) { "界面大小必须是 DisplayScale.OPTIONS 里的一档，收到 $percent" }
        persist(percent)
        stored.value = percent
    }
}

/** 应用里的那一份：存在 `chogumi_display` 这个 SharedPreferences 文件里。 */
fun DisplayScaleRepository(context: Context): DisplayScaleRepository {
    val prefs = context.getSharedPreferences(DISPLAY_SCALE_FILE, Context.MODE_PRIVATE)
    return DisplayScaleRepository(prefs.getInt(DISPLAY_SCALE_KEY, DisplayScale.SYSTEM)) { percent ->
        prefs.edit().putInt(DISPLAY_SCALE_KEY, percent).apply()
    }
}

private const val DISPLAY_SCALE_FILE = "chogumi_display"
private const val DISPLAY_SCALE_KEY = "display_scale_percent"
