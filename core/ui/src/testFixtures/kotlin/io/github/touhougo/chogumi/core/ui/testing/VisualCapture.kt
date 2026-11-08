package io.github.touhougo.chogumi.core.ui.testing

/**
 * 给人看的产物，不是测试：截图、逐帧录屏落盘，拿去对照设计稿或审阅动效。里面没有断言
 * （tools/guards/check_test_assertions.py：标了它不许断言，没标的 @Test 必须断言）。
 *
 * 默认的 connectedDebugAndroidTest 跳过它们，也就不计入测试数；出图时带 `-Pchogumi.captures` 只跑它们
 * （build-logic 的 configureAndroidCommon；命令见 docs/dev/guide.md §4.2）。标在类上是整个类都是截图。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class VisualCapture
