/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.thymeleaf

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.io.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.util.cio.*
import org.thymeleaf.templateresolver.*
import java.util.*
import java.util.zip.*
import kotlin.test.*

class ThymeleafTest {
    @Test
    fun testName() = testApplication {
        application {
            setUpThymeleafStringTemplate()
            install(ConditionalHeaders)
            routing {
                val model = mapOf("id" to 1, "title" to "Hello, World!")

                get("/") {
                    call.respond(ThymeleafContent(STRING_TEMPLATE, model, "e"))
                }
            }
        }

        val response = client.get("/")
        val content = response.bodyAsText()
        assertNotNull(content)
        val expectedContent = listOf("<p>Hello, 1</p>", "<h1>Hello, World!</h1>")
        assertEquals(expectedContent, content.lines())

        val contentTypeText = assertNotNull(response.headers[HttpHeaders.ContentType])
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), ContentType.parse(contentTypeText))
        assertEquals("\"e\"", response.headers[HttpHeaders.ETag])
    }

    @Test
    fun testCompression() = testApplication {
        application {
            setUpThymeleafStringTemplate()
            install(Compression) {
                gzip { minimumSize(10) }
            }
            install(ConditionalHeaders)

            routing {
                val model = mapOf("id" to 1, "title" to "Hello, World!")

                get("/") {
                    call.respond(ThymeleafContent(STRING_TEMPLATE, model, "e"))
                }
            }
        }

        val response = client.get("/") {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }

        val content = GZIPInputStream(response.bodyAsChannel().toByteArray().inputStream()).reader().readText()
        val expectedContent = listOf("<p>Hello, 1</p>", "<h1>Hello, World!</h1>")
        assertEquals(expectedContent, content.lines())

        val contentTypeText = assertNotNull(response.headers[HttpHeaders.ContentType])
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), ContentType.parse(contentTypeText))
        assertEquals("\"e\"", response.headers[HttpHeaders.ETag])
    }

    @Test
    fun testWithoutEtag() = testApplication {
        application {
            setUpThymeleafStringTemplate()
            install(ConditionalHeaders)

            routing {
                val model = mapOf("id" to 1, "title" to "Hello, World!")

                get("/") {
                    call.respond(ThymeleafContent(STRING_TEMPLATE, model))
                }
            }
        }

        val response = client.get("/")
        val content = response.bodyAsText()

        assertNotNull(content)
        val expectedContent = listOf("<p>Hello, 1</p>", "<h1>Hello, World!</h1>")
        assertEquals(expectedContent, content.lines())

        val contentTypeText = assertNotNull(response.headers[HttpHeaders.ContentType])
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), ContentType.parse(contentTypeText))
        assertEquals(null, response.headers[HttpHeaders.ETag])
    }

    @Test
    fun canRespondAppropriately() = testApplication {
        application {
            setUpThymeleafStringTemplate()
            install(ConditionalHeaders)

            routing {
                val model = mapOf("id" to 1, "title" to "Bonjour le monde!")

                get("/") {
                    call.respondTemplate(STRING_TEMPLATE, model)
                }
            }
        }

        val content = client.get("/").bodyAsText()
        assertNotNull(content)

        val lines = content.lines()

        assertEquals(lines[0], "<p>Hello, 1</p>")
        assertEquals(lines[1], "<h1>Bonjour le monde!</h1>")
    }

    @Test
    fun testClassLoaderTemplateResolver() = testApplication {
        application {
            install(Thymeleaf) {
                val resolver = ClassLoaderTemplateResolver()
                resolver.setTemplateMode("HTML")
                resolver.prefix = "templates/"
                resolver.suffix = ".html"
                setTemplateResolver(resolver)
            }
            install(ConditionalHeaders)
            routing {
                val model = mapOf("id" to 1, "title" to "Hello, World!")
                get("/") {
                    call.respondTemplate("test", model)
                }
            }
        }

        val content = client.get("/").bodyAsText()

        val lines = content.lines()
        assertEquals("<p>Hello, 1</p>", lines[0])
        assertEquals("<h1>Hello, World!</h1>", lines[1])
    }

    @Test
    fun testI18nHtmlTemplate() {
        val testCases = mapOf(
            "en" to "Hello, world!",
            "es;q=0.3,en-us;q=0.7" to "Hello, world!",
            "es" to "Hola, mundo!",
            "es-419" to "Hola, mundo!",
            "default" to "Hello, world!"
        )
        testApplication {
            application {
                install(Thymeleaf) {
                    val resolver = ClassLoaderTemplateResolver()
                    resolver.setTemplateMode("HTML")
                    resolver.prefix = "templates/"
                    resolver.suffix = ".html"
                    setTemplateResolver(resolver)
                }
                install(ConditionalHeaders)
                routing {
                    get("/") {
                        if (call.request.acceptLanguage() == "default") {
                            Locale.setDefault(Locale("en"))
                            call.respond(ThymeleafContent("i18n_test", mapOf()))
                        } else {
                            val languageRanges = Locale.LanguageRange.parse(call.request.acceptLanguage())
                            val locale = Locale.lookup(languageRanges, Locale.getAvailableLocales().toList())
                            call.respond(ThymeleafContent("i18n_test", mapOf(), locale = locale))
                        }
                    }
                }
            }
            testCases.forEach { (language, result) ->
                val content = client.get("/") {
                    header(HttpHeaders.AcceptLanguage, language)
                }.bodyAsText()

                assertNotNull(content)
                val lines = content.lines()
                assertEquals("<h1>$result</h1>", lines[0])
            }
        }
    }

    @Test
    fun testFragmentReturn() = testApplication {
        application {
            install(Thymeleaf) {
                val resolver = ClassLoaderTemplateResolver()
                resolver.setTemplateMode("HTML")
                resolver.prefix = "templates/"
                resolver.suffix = ".html"
                setTemplateResolver(resolver)
            }
            install(ConditionalHeaders)
            routing {
                get("/") {
                    call.respond(ThymeleafContent("fragments", mapOf(), fragments = setOf("firstFragment")))
                }
            }
        }
        val lines = client.get("/").bodyAsText().lines()
        assertEquals("<div><p>Hello, first fragment</p></div>", lines[0])
    }

    @Test
    fun testFragmentsInsert() = testApplication {
        application {
            install(Thymeleaf) {
                val resolver = ClassLoaderTemplateResolver()
                resolver.setTemplateMode("HTML")
                resolver.prefix = "templates/"
                resolver.suffix = ".html"
                setTemplateResolver(resolver)
            }
            install(ConditionalHeaders)
            routing {
                get("/") {
                    call.respond(ThymeleafContent("fragments_insert_test", mapOf()))
                }
            }
        }
        val lines = client.get("/").bodyAsText().lines()
        assertEquals("<div><div><p>Hello, first fragment</p></div></div>", lines[0])
    }

    private fun Application.setUpThymeleafStringTemplate() {
        install(Thymeleaf) {
            setTemplateResolver(StringTemplateResolver())
        }
    }

    companion object {
        val bax = "$"
        private val STRING_TEMPLATE = """
            <p>Hello, [[$bax{id}]]</p>
            <h1 th:text="$bax{title}"></h1>
        """.trimIndent()
    }
}
