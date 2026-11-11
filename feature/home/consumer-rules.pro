# Glance 按类名反射创建点击回调（RunCallbackAction：getDeclaredConstructor().newInstance()），
# 它自带的规则只留类名不留构造函数：正式包压缩后构造函数被删，点小组件上的按钮什么也不发生。
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Glance 用 provider 的运行时类名登记、查找实例：这些类不能混淆或合并，成员仍可优化。
-keep class io.github.touhougo.chogumi.feature.home.widget.ProgressWidget
-keep class io.github.touhougo.chogumi.feature.home.widget.SubjectWidget
-keep class io.github.touhougo.chogumi.feature.home.widget.HeatWidget
-keep class io.github.touhougo.chogumi.feature.home.widget.TodayWidget
-keep class io.github.touhougo.chogumi.feature.home.widget.WeekWidget
