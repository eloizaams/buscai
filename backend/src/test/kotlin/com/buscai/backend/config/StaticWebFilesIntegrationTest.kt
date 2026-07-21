package com.buscai.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Teste de integração (Spring context real, servidor embutido de verdade — `RANDOM_PORT`) de
 * `specs/cliente-web/tasks.md` T2 — prova que:
 * 1. `web/index.html` foi de fato copiado por `copyWebStatic` (`backend/build.gradle.kts`) para
 *    `build/resources/main/static/` e é servido same-origin em `GET /` **sem** `X-Api-Key`
 *    (isento via [ApiSecurityPaths]).
 * 2. A isenção não vazou para nenhuma rota de API: `GET /chat` e `GET /books` sem `X-Api-Key`
 *    continuam `401` — o risco real desta task era isentar demais por acidente.
 *
 * `RANDOM_PORT` + `java.net.http.HttpClient` puro (não `MockMvc`/`webEnvironment=MOCK`, nem
 * `TestRestTemplate` — módulo `spring-boot-resttestclient` não é dependência do projeto) porque
 * `GET /` é resolvido pelo `WelcomePageHandlerMapping` do Spring Boot como um `forward:index.html`
 * interno — um servidor embutido real resolve esse forward até o `ResourceHttpRequestHandler` de
 * verdade (devolvendo o conteúdo do arquivo), enquanto o dispatcher simulado do `MockMvc` não segue
 * o forward até um handler de recurso estático real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["buscai.api-key=static-web-test-key"])
class StaticWebFilesIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `GET raiz sem X-Api-Key devolve 200 com o conteudo do index html`() {
        val response = get("/")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("buscai"), "esperava o placeholder de web index html no corpo")
    }

    @Test
    fun `GET chat sem X-Api-Key continua 401 mesmo com arquivos estaticos isentos`() {
        val response = get("/chat")

        assertEquals(401, response.statusCode())
    }

    @Test
    fun `GET books sem X-Api-Key continua 401 mesmo com arquivos estaticos isentos`() {
        val response = get("/books")

        assertEquals(401, response.statusCode())
    }
}
