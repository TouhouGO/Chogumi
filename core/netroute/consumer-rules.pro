# 直连核心（gomobile）：Go 侧经 JNI 按类名调这些类，不能改名或删
-keep class go.** { *; }
-keep class io.github.touhougo.chogumi.ech.** { *; }
