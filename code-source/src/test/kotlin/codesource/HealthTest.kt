package codesource

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// docs/api.md: GET /health
class HealthTest {
    @Test
    fun `health reports ok as JSON`() = testApplication {
        application { module() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.Json))
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }
}
