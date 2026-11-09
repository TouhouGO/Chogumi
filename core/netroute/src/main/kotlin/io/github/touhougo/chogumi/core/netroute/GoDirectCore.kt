package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.ech.echcore.ConfigSink
import io.github.touhougo.chogumi.ech.echcore.Echcore
import io.github.touhougo.chogumi.ech.echcore.Logger

/** Go 写的直连核心（core/netroute/go，gomobile 生成）。 */
internal object GoDirectCore : DirectCore {
    override fun start(
        addresses: List<String>,
        config: ByteArray,
        edgeBudgetMillis: Long,
        onConfig: (ByteArray) -> Unit,
        log: ((String) -> Unit)?,
    ): CoreEndpoint {
        val sink = object : ConfigSink {
            override fun save(config: ByteArray) = onConfig(config)
        }
        val logger = log?.let { write ->
            object : Logger {
                override fun log(line: String) = write(line)
            }
        }
        val endpoint = Echcore.start(addresses.joinToString(","), config, edgeBudgetMillis, sink, logger)
        return CoreEndpoint(endpoint.port.toInt(), endpoint.token)
    }

    override fun resetNetwork() {
        Echcore.resetNetwork()
    }

    override fun stop() {
        Echcore.stop()
    }
}
