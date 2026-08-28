package com.v2ray.ang.root

import java.util.concurrent.TimeUnit

internal data class ProcessCompletion(
    val finished: Boolean,
    val exitCode: Int?,
    val output: String,
)

/** API-24-compatible timed wait that drains stdout concurrently to avoid pipe deadlocks. */
internal fun Process.awaitWithOutput(timeoutSeconds: Long): ProcessCompletion {
    val output = StringBuilder()
    val reader = Thread(
        {
            runCatching {
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        }
                    }
                }
            }
        },
        "root-process-output",
    ).apply {
        isDaemon = true
        start()
    }

    val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(0L))
    val startedAt = System.nanoTime()
    var finished = false
    while (!finished && System.nanoTime() - startedAt < timeoutNanos) {
        finished = runCatching { exitValue() }.isSuccess
        if (!finished) {
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
    if (!finished) finished = runCatching { exitValue() }.isSuccess
    if (!finished) destroy()

    try {
        reader.join(1_000L)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    val capturedOutput = synchronized(output) { output.toString() }
    return ProcessCompletion(
        finished = finished,
        exitCode = if (finished) runCatching { exitValue() }.getOrNull() else null,
        output = capturedOutput,
    )
}
