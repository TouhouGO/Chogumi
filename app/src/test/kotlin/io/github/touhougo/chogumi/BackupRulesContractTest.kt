package io.github.touhougo.chogumi

import io.github.touhougo.chogumi.core.data.token.TOKENS_DATASTORE_NAME
import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * 系统备份只留偏好设置：两份备份规则（旧系统的 full-backup-content，新系统的云备份与换机迁移）
 * 排除的是同一组，且令牌及其写入临时文件、数据库、表情缓存、崩溃记录、WebView 存储都在里面。
 */
class BackupRulesContractTest {
    private val expected = setOf(
        "file:datastore/${TOKENS_DATASTORE_NAME}.preferences_pb",
        "file:datastore/${TOKENS_DATASTORE_NAME}.preferences_pb.tmp",
        "file:datastore/subject-widget-restores.preferences_pb",
        "file:datastore/subject-widget-restores.preferences_pb.tmp",
        "database:.",
        "file:bangumi_smiley/",
        "file:crash/",
        "root:app_webview/",
    )

    @Test
    fun `三处备份规则都排除令牌及其写入临时文件、数据库、表情缓存、崩溃记录与 WebView 存储`() {
        val full = parse("app/src/main/res/xml/backup_rules.xml")
        val extraction = parse("app/src/main/res/xml/data_extraction_rules.xml")
        assertEquals("full-backup-content", expected, excludes(full))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = extraction.getElementsByTagName(section).item(0) as Element
            assertEquals(section, expected, excludes(element))
        }
    }

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(RepoFiles.file(path)).documentElement

    private fun excludes(element: Element): Set<String> {
        val nodes = element.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }.toSet()
    }
}
