package io.github.touhougo.chogumi.core.data.repository

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 线程安全的 HTTP 请求记录器，用于替代 Ktor [MockEngine.requestHistory]（非线程安全 ArrayList）。
 *
 * ## 根因说明
 *
 * Ktor [MockEngine] 内部的 `requestHistory` 为普通的 `ArrayList<HttpRequestData>`。
 * 当 ViewModel / Repository 在后台协程并发发起多个网络请求（例如并行拉取各分类数据），
 * 而测试线程在 `awaitUntil` 中遍历 `requestHistory` 判定条件时，
 * 并发读写会导致 [java.util.ConcurrentModificationException] 或丢失部分请求记录。
 *
 * 本类底层采用 [ConcurrentLinkedQueue]，写入并发安全；遍历一律基于快照。
 * 用法：在 [MockEngine] 的 handler 首行调用 [record]。
 */
class ConcurrentRequestRecorder : Iterable<HttpRequestData> {
    private val records = ConcurrentLinkedQueue<HttpRequestData>()

    /** 记录一次请求（线程安全）。 */
    fun record(request: HttpRequestData) {
        records.add(request)
    }

    fun isNotEmpty(): Boolean = !records.isEmpty()

    /** 一轮请求已完成后清空记录，让下一轮独立断言。 */
    fun clear() = records.clear()

    /** 遍历当前记录的快照，遍历期间的并发写入不影响本次迭代。 */
    override fun iterator(): Iterator<HttpRequestData> = records.toList().iterator()
}
