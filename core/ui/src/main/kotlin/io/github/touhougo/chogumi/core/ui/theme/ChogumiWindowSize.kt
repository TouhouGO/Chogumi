package io.github.touhougo.chogumi.core.ui.theme

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.compositionLocalOf

/** 全局下发的窗口宽度尺寸类（默认 Compact，平板 Medium/Expanded 下触发自适应分流） */
val LocalWindowWidthSizeClass = compositionLocalOf { WindowWidthSizeClass.Compact }
