package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.db.Database
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking

fun main() {
    val config = ServerConfig.fromEnvironment()
    val dbConfig = requireNotNull(config.database) { "SIM_DATABASE_URL is not set" }
    val dataSource = Database.createDataSource(dbConfig)
    Database.migrate(dataSource)

    val services = AppServices.create(dataSource, config)
    runBlocking { services.seeder.seed(config.bootstrapAdmin) }

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config, services)
    }.start(wait = true)
}
