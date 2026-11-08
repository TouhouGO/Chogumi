package io.github.touhougo.chogumi.core.model

import java.io.File

/**
 * 按需表情的本地文件：渲染层只认这个能力，取图的实现（下载、校验、落盘）在数据层。
 * 绝对 URL 与相对路径都来自 core:common 的 `SmileyCatalog`；取不到返回 null，渲染层退回显示原文。
 */
fun interface SmileyFiles {
    suspend fun ensure(url: String, remotePath: String): File?
}
