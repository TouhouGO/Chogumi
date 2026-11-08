# Glance 按类名反射创建点击回调（RunCallbackAction：getDeclaredConstructor().newInstance()），
# 它自带的规则只留类名不留构造函数：正式包压缩后构造函数被删，点小组件上的按钮什么也不发生。
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
