package com.taksolutions.simsensor.server.realtime

import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Redis pub/sub bus for several server instances. Events go to `simsensor:ex:<id>` or
 * `simsensor:dev:<id>`; each instance pattern-subscribes to `simsensor:*`.
 */
class RedisBus(redisUrl: String) : RealtimeBus {
    private val log = LoggerFactory.getLogger(RedisBus::class.java)
    private val client = RedisClient.create(redisUrl)
    private val publisher = client.connect()
    private val subscriber = client.connectPubSub()
    private val listeners = CopyOnWriteArrayList<(BusEvent) -> Unit>()

    init {
        subscriber.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(pattern: String, channel: String, message: String) {
                val event = runCatching { BusCodec.decode(message) }
                    .onFailure { log.warn("Ignoring undecodable bus message on {}", channel, it) }
                    .getOrNull() ?: return
                listeners.forEach { it(event) }
            }
        })
        subscriber.sync().psubscribe("$PREFIX*")
    }

    override fun publish(event: BusEvent) {
        publisher.async().publish(PREFIX + event.channelKey, BusCodec.encode(event))
    }

    override fun subscribe(listener: (BusEvent) -> Unit) {
        listeners += listener
    }

    override fun close() {
        listeners.clear()
        subscriber.close()
        publisher.close()
        client.shutdown()
    }

    private companion object {
        const val PREFIX = "simsensor:"
    }
}
