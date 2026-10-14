package com.taksolutions.simsensor.server.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

private class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)

/** One Redis container (same image as production) for the whole test run. */
object TestRedis {
    private val container: RedisContainer by lazy {
        RedisContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1))
            .also { it.start() }
    }

    val url: String by lazy { "redis://${container.host}:${container.getMappedPort(6379)}" }
}
