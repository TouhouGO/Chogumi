package io.github.touhougo.chogumi.core.network

/**
 * 旧站短信页的样本（testFixtures 资源 `pm/`）：按网页结构构造的虚构页面，用户与内容都是虚构值，表单校验值写成 `FORMHASH`。
 * 解析测试与仓库测试共用同一批页面。
 */
object PmSamples {
    /** `pm/[name]` 的内容。 */
    fun read(name: String): String =
        requireNotNull(PmSamples::class.java.getResource("/pm/$name")) { "没有样本 pm/$name" }.readText()
}
