package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.ech.echcore.ConfigSink
import io.github.touhougo.chogumi.ech.echcore.Echcore

/** Go 写的直连核心（core/netroute/go，gomobile 生成）。 */
internal object GoDirectCore : DirectCore {
    override fun start(addresses: List<String>, config: ByteArray, onConfig: (ByteArray) -> Unit): CoreEndpoint {
        val sink = object : ConfigSink {
            override fun save(config: ByteArray) = onConfig(config)
        }
        val endpoint = Echcore.start(addresses.joinToString(","), config, sink)
        return CoreEndpoint(endpoint.port.toInt(), endpoint.token)
    }

    override fun stop() {
        Echcore.stop()
    }
}
