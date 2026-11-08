package io.github.touhougo.chogumi.core.ui.theme

import android.app.Activity
import android.content.Context
import android.view.Display
import androidx.core.content.ContextCompat
import io.github.touhougo.chogumi.core.model.DisplayModeSpec
import io.github.touhougo.chogumi.core.model.RefreshRate
import io.github.touhougo.chogumi.core.model.ScreenModes

/** 这个 Context 所在的屏此刻的模式与它报的全部模式。设置页列档位、Activity 请求模式都读这一个。 */
fun Context.screenModes(): ScreenModes {
    val display = ContextCompat.getDisplayOrDefault(this)
    return ScreenModes(current = display.mode.toSpec(), supported = display.supportedModes.map { it.toSpec() })
}

/**
 * 刷新率落到窗口上的唯一一处：按存值（[RefreshRate]）给窗口的 `preferredDisplayModeId`，跟随系统时是 0（没有偏好）。
 * 只是请求：系统的省电、温控与厂商设置仍可以压下它。Activity 每次创建（转屏、折叠屏换屏都会重建）与存值变了时各调一次。
 */
fun Activity.requestRefreshRate(stored: Int) {
    val modeId = RefreshRate.modeIdFor(stored, screenModes())
    val attributes = window.attributes
    if (attributes.preferredDisplayModeId == modeId) return
    attributes.preferredDisplayModeId = modeId
    window.attributes = attributes
}

private fun Display.Mode.toSpec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
