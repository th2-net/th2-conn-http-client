package com.exactpro.th2.http.client.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

//FIXME: need to be moved into client options as createExecutorService method of interface or abstract class
fun createExecutorService(length: Int): ExecutorService = run {
    val counter = AtomicInteger(1)
    ThreadPoolExecutor(length, length, 0L, TimeUnit.MILLISECONDS, SynchronousQueue()) { runnable: Runnable? ->
        Thread(runnable).apply {
            isDaemon = true
            name = "tcp-th2-client-" + counter.incrementAndGet()
        }
    }.apply {
        setRejectedExecutionHandler { runnable, threadPoolExecutor ->
            try {
                // Wait until queue is ready, don`t throw reject
                threadPoolExecutor.queue.put(runnable)
                if (threadPoolExecutor.isShutdown) {
                    throw RejectedExecutionException("Task $runnable rejected from $threadPoolExecutor due shutdown")
                }
            } catch (e: InterruptedException) {
                throw RejectedExecutionException("Task $runnable rejected from $threadPoolExecutor", e)
            }
        }
    }
}