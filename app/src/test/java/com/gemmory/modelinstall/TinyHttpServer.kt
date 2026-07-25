package com.gemmory.modelinstall

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 file server for downloader tests.
 *
 * `com.sun.net.httpserver` is not on the Android unit-test classpath, and
 * pulling in a mock web server just for range requests is more machinery than
 * this needs.
 */
class TinyHttpServer(private val payload: ByteArray) {

    var supportsRange: Boolean = true
    var truncateAfterBytes: Int? = null
    var statusCode: Int = 200

    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private var running = true

    val port: Int get() = serverSocket.localPort
    val url: String get() = "http://127.0.0.1:$port/model"

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    fun close() {
        running = false
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) = socket.use { client ->
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return@use

        var rangeStart = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true) && supportsRange) {
                rangeStart = line.substringAfter("bytes=").substringBefore('-').trim().toIntOrNull() ?: 0
            }
        }
        require(requestLine.startsWith("GET")) { "only GET is supported" }

        val output = client.getOutputStream()
        if (statusCode != 200) {
            output.write("HTTP/1.1 $statusCode Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return@use
        }

        val body = payload.copyOfRange(rangeStart, payload.size)
        val sent = truncateAfterBytes?.coerceAtMost(body.size) ?: body.size
        val partial = rangeStart > 0 && supportsRange

        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            if (partial) {
                append("Content-Range: bytes $rangeStart-${payload.size - 1}/${payload.size}\r\n")
            }
            append("Content-Length: $sent\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())
        output.write(body, 0, sent)
        output.flush()
    }
}
