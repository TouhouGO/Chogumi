package io.github.touhougo.chogumi.core.netroute

import android.os.Build

/**
 * 这台设备能不能用直连。直连核心只有 arm64-v8a 一份；包里四个 ABI 目录都在（别的库带着），系统总是选设备首选的那个，
 * 所以首选是 arm64-v8a 时核心一定在，否则一定不在。不在时入口都不画。
 *
 * 单独一个文件：顶层值随所在文件的类一起初始化，和别的顶层函数放在一起，调那些函数也会去读 [Build]。
 */
val directRouteSupported: Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
