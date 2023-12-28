/*
 * Copyright 2020-2023 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Main")

package com.exactpro.th2.http.client

import com.exactpro.th2.common.schema.factory.CommonFactory
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger { }

fun main(args: Array<String>) = try {
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val factory = runCatching {
        CommonFactory.createFromArguments(*args)
    }.getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    Application(factory) { resource, destructor ->
        resources += resource to destructor
    }.start()

    LOGGER.info { "Successfully started" }

    ReentrantLock().run {
        val condition = newCondition()
        resources += "await-shutdown" to { withLock(condition::signalAll) }
        withLock(condition::await)
    }

    LOGGER.info { "Finished running" }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}