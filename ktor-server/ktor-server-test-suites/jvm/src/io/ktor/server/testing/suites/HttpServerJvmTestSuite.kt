/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.testing.suites

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.content.*
import io.ktor.io.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import java.net.*
import java.nio.*
import java.time.ZonedDateTime
import java.util.concurrent.atomic.*
import kotlin.coroutines.*
import kotlin.test.*
import kotlin.text.toByteArray
import kotlin.time.Duration.Companion.seconds

abstract class HttpServerJvmTestSuite<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(
    hostFactory: ApplicationEngineFactory<TEngine, TConfiguration>
) : EngineTestBase<TEngine, TConfiguration>(hostFactory) {

    @Test
    open fun testPipelining() {
        createAndStartServer {
            get("/") {
                val id = call.parameters["d"]!!.toInt()
                call.respondText("Response for $id\n")
            }
        }

        val s = Socket()
        s.tcpNoDelay = true

        val builder = StringBuilder()
        for (id in 1..16) {
            builder.append("GET /?d=$id HTTP/1.1\r\n")
            builder.append("Host: localhost\r\n")
            builder.append("Connection: keep-alive\r\n")
            builder.append("\r\n")
        }

        val impudent = builder.toString().toByteArray()

        s.connect(InetSocketAddress(port))
        s.use { _ ->
            s.getOutputStream().apply {
                write(impudent)
                flush()
            }

            runBlocking {
                val actual = s.getInputStream().reader().readLines().asSequence()
                assertEquals(
                    pipelinedResponses,
                    clearSocketResponses(actual)
                )
            }
        }
    }

    @Test
    open fun testPipeliningWithFlushingHeaders() {
        val lastHandler = CompletableDeferred<Unit>()
        val processedRequests = AtomicLong()

        createAndStartServer {
            post("/") {
                val id = call.parameters["d"]!!.toInt()

                val byteStream = ByteReadChannel {
                    if (id < 16 && processedRequests.incrementAndGet() == 15L) {
                        lastHandler.complete(Unit)
                    }
                    writePacket(call.request.receiveChannel().readRemaining())
                    writeString("\n")
                }

                call.respond(object : OutgoingContent.ReadChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.OK
                    override val contentType: ContentType = ContentType.Text.Plain
                    override val headers: Headers = Headers.Empty
                    override val contentLength: Long = 14L + id.toString().length
                    override fun readFrom() = byteStream
                })
            }
        }

        val s = Socket()
        s.tcpNoDelay = true

        val builder = StringBuilder()
        for (id in 1..15) {
            builder.append("POST /?d=$id HTTP/1.1\r\n")
            builder.append("Host: localhost\r\n")
            builder.append("Connection: keep-alive\r\n")
            builder.append("Accept-Charset: UTF-8\r\n")
            builder.append("Accept: */*\r\n")
            builder.append("Content-Type: text/plain; charset=UTF-8\r\n")
            builder.append("content-length: ${13 + id.toString().length}\r\n")
            builder.append("\r\n")
            builder.append("Response for $id")
            builder.append("\r\n")
        }
        builder.append("POST /?d=16 HTTP/1.1\r\n")
        builder.append("Host: localhost\r\n")
        builder.append("Connection: close\r\n")
        builder.append("Accept-Charset: UTF-8\r\n")
        builder.append("Accept: */*\r\n")
        builder.append("Content-Type: text/plain; charset=UTF-8\r\n")
        builder.append("content-length: 15\r\n")
        builder.append("\r\n")

        var impudent = builder.toString().toByteArray()

        s.connect(InetSocketAddress(port))
        s.use {
            s.getOutputStream().apply {
                write(impudent)
                flush()
            }

            runBlocking {
                lastHandler.await()

                builder.clear()
                builder.append("Response for 16")
                builder.append("\r\n")
                impudent = builder.toString().toByteArray()

                s.getOutputStream().apply {
                    write(impudent)
                    flush()
                }
                val responses = clearSocketResponses(
                    s.getInputStream().bufferedReader(Charsets.ISO_8859_1).lineSequence()
                )
                assertEquals(pipelinedResponses, responses)
            }
        }
    }

    @Test
    fun testRequestTwiceInOneBufferWithKeepAlive() {
        createAndStartServer {
            get("/") {
                val d = call.request.queryParameters["d"]!!.toLong()
                delay(d.seconds.inWholeMilliseconds)

                call.response.header("D", d.toString())
                call.respondText("Response for $d\n")
            }
        }

        val s = Socket()
        s.tcpNoDelay = true

        val impudent = buildString {
            append("GET /?d=2 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")

            append("GET /?d=1 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()

        s.connect(InetSocketAddress(port))
        s.use { _ ->
            s.getOutputStream().apply {
                write(impudent)
                flush()
            }

            val responses = clearSocketResponses(
                s.getInputStream().bufferedReader(Charsets.ISO_8859_1).lineSequence()
            )

            assertEquals(
                """
                HTTP/1.1 200
                D: 2
                Response for 2
                HTTP/1.1 200
                D: 1
                Response for 1
                """.trimIndent().replace(
                    "\r\n",
                    "\n"
                ),
                responses
            )
        }
    }

    @Test
    fun testClosedConnection() {
        val completed = Job()

        createAndStartServer {
            get("/file") {
                try {
                    call.respond(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                val bb = ByteBuffer.allocate(512)
                                for (i in 1L..1000L) {
                                    delay(100)
                                    bb.clear()
                                    while (bb.hasRemaining()) {
                                        bb.putLong(i)
                                    }
                                    bb.flip()
                                    channel.writeByteBuffer(bb)
                                    channel.flush()
                                }

                                channel.close()
                            }
                        }
                    )
                } finally {
                    completed.cancel()
                }
            }
        }

        socket {
            outputStream.write(
                RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/file", "HTTP/1.1")
                    headerLine("Host", "localhost:$port")
                    headerLine("Connection", "keep-alive")
                    emptyLine()
                }.build().toByteArray()
            )

            outputStream.flush()

            inputStream.read(ByteArray(100))
        } // send FIN

        runBlocking {
            withTimeout(5000L) {
                completed.join()
            }
        }
    }

    @Test
    fun testConnectionReset() {
        val completed = Job()

        createAndStartServer {
            get("/file") {
                try {
                    call.respond(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                val bb = ByteBuffer.allocate(512)
                                for (i in 1L..1000L) {
                                    delay(100)
                                    bb.clear()
                                    while (bb.hasRemaining()) {
                                        bb.putLong(i)
                                    }
                                    bb.flip()
                                    channel.writeByteBuffer(bb)
                                    channel.flush()
                                }

                                channel.close()
                            }
                        }
                    )
                } finally {
                    completed.cancel()
                }
            }
        }

        socket {
            // to ensure immediate RST at close it is very important to set SO_LINGER = 0
            setSoLinger(true, 0)

            outputStream.write(
                RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/file", "HTTP/1.1")
                    headerLine("Host", "localhost:$port")
                    headerLine("Connection", "keep-alive")
                    emptyLine()
                }.build().toByteArray()
            )

            outputStream.flush()

            inputStream.read(ByteArray(100))
        } // send FIN + RST

        runBlocking {
            withTimeout(5000L) {
                completed.join()
            }
        }
    }

    @Test
    open fun testUpgrade() {
        val completed = CompletableDeferred<Unit>()

        createAndStartServer {
            get("/up") {
                call.respond(
                    object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.Upgrade, "up")
                                append(HttpHeaders.Connection, "Upgrade")
                            }

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                try {
                                    val packet = input.readRemaining()
                                    assertEquals(8, packet.availableForRead)
                                    output.writePacket(packet)
                                    output.close()

                                    while (input.awaitBytes()) {
                                        assertEquals(0, input.readRemaining().availableForRead)
                                    }

                                    completed.complete(Unit)
                                } catch (t: Throwable) {
                                    completed.completeExceptionally(t)
                                    throw t
                                }
                            }
                        }
                    }
                )
            }
        }

        socket {
            outputStream.apply {
                val p = RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/up", "HTTP/1.1")
                    headerLine(HttpHeaders.Host, "localhost:$port")
                    headerLine(HttpHeaders.Upgrade, "up")
                    headerLine(HttpHeaders.Connection, "upgrade")
                    emptyLine()
                }.build().toByteArray()
                write(p)
                flush()
            }

            val ch = ByteReadChannel {
                val s = inputStream
                val bytes = ByteArray(512)
                while (true) {
                    if (s.available() > 0) {
                        val rc = s.read(bytes)
                        writeByteArray(bytes, 0, rc)
                    } else {
                        yield()
                        val rc = s.read(bytes)
                        if (rc == -1) break
                        writeByteArray(bytes, 0, rc)
                    }

                    yield()
                }
            }

            runBlocking {
                val response = parseResponse(ch)!!

                assertEquals(HttpStatusCode.SwitchingProtocols.value, response.status)
                assertEquals("Upgrade", response.headers[HttpHeaders.Connection]?.toString())
                assertEquals("up", response.headers[HttpHeaders.Upgrade]?.toString())

                (0 until response.headers.size)
                    .map { response.headers.nameAt(it).toString() }
                    .groupBy { it }.forEach { (name, values) ->
                        assertEquals(1, values.size, "Duplicate header $name")
                    }

                outputStream.apply {
                    write(buildPacket {
                        writeLong(0x1122334455667788L)
                    }.toByteArray())
                    flush()
                }

                assertEquals(0x1122334455667788L, ch.readLong())

                close()

                completed.await()
            }
        }
    }

    @Test
    fun testHeaderAppearsSingleTime() {
        val lastModified = ZonedDateTime.now()

        createAndStartServer {
            install(DefaultHeaders) {
                header(HttpHeaders.Server, "BRS")
                header("X-Content-Type-Options", "nosniff")
            }

            get("/") {
                call.response.lastModified(lastModified)
                call.respond(HttpStatusCode.OK, "OK")
            }
        }

        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()

        socket {
            outputStream.apply {
                write(request)
                flush()
            }

            val response = inputStream.bufferedReader().readLines()

            assertTrue { "Server: BRS" in response }
            assertFalse { "Server: Ktor/debug" in response }
        }
    }

    private val pipelinedResponses = """
                    HTTP/1.1 200
                    Response for 1
                    HTTP/1.1 200
                    Response for 2
                    HTTP/1.1 200
                    Response for 3
                    HTTP/1.1 200
                    Response for 4
                    HTTP/1.1 200
                    Response for 5
                    HTTP/1.1 200
                    Response for 6
                    HTTP/1.1 200
                    Response for 7
                    HTTP/1.1 200
                    Response for 8
                    HTTP/1.1 200
                    Response for 9
                    HTTP/1.1 200
                    Response for 10
                    HTTP/1.1 200
                    Response for 11
                    HTTP/1.1 200
                    Response for 12
                    HTTP/1.1 200
                    Response for 13
                    HTTP/1.1 200
                    Response for 14
                    HTTP/1.1 200
                    Response for 15
                    HTTP/1.1 200
                    Response for 16
                """
        .trimIndent().replace("\r\n", "\n")

    protected fun clearSocketResponses(responses: Sequence<String>) =
        responses.filterNot { line ->
            line.startsWith("Date") || line.startsWith("Server") ||
                line.startsWith("Content-") || line.toIntOrNull() != null ||
                line.isBlank() || line.startsWith("Connection") || line.startsWith("Keep-Alive")
        }
            .map { it.trim() }
            .joinToString(separator = "\n")
            .replace("200 OK", "200")
            .replace("400 Bad Request", "400")
}
