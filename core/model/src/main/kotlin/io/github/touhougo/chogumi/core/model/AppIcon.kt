package io.github.touhougo.chogumi.core.model

/**
 * 桌面上的应用图标（设置 →「应用图标」）。每一个对应 app 清单里的一个启动入口（activity-alias），
 * 同一时刻只开着一个；开着哪一个就是当前图标，不另存偏好。
 */
enum class AppIcon {
    SAKURA_PINK,
    SAKURA_RAINBOW,
    TV_NAVY,
    TV_PINK,
    TV_ON_PINK,
    ;

    companion object {
        /** 清单里默认开着的入口。 */
        val DEFAULT = SAKURA_PINK
    }
}
