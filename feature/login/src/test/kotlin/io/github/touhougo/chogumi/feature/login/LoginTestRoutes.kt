package io.github.touhougo.chogumi.feature.login

import io.github.touhougo.chogumi.core.netroute.DirectRouteHint
import kotlinx.coroutines.flow.flowOf

/** 不提示「试试直连」：设备不支持，或已经在直连。 */
internal val NoDirectRoute = DirectRouteHint { flowOf(false) }

/** 设备支持直连、现在走系统网络。 */
internal val DirectRouteAvailable = DirectRouteHint { flowOf(true) }
