package codesource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// docs/features/014-search-descriptions-and-ctes.md: the relevance set,
// a start on 010's.
//
// Searches an analyst might type, over a copy of the dev sample repository
// (src/test/resources/relevance/). Each says what it should find, and what
// should rank above what, never exact orders or scores, so tuning search
// doesn't churn them. Later search changes (010) are checked against it.
class RelevanceTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.asReversed().forEach { it.close() }

    private fun servingExamples(block: suspend ApplicationTestBuilder.() -> Unit) {
        val root = File(javaClass.getResource("/relevance")!!.toURI())
        val files = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val repo = FixtureRepo().also { cleanup += it; it.commit(files) }
        val source = GitCodeSource(repo.url, null, Files.createTempDirectory("clone").toFile().resolve("repo.git"))
            .also { cleanup += it }
        testApplication {
            application { module(source) }
            assertEquals(HttpStatusCode.OK, client.post("/refresh").status)
            block()
        }
    }

    /** Every path [q] finds, best first. */
    private suspend fun HttpClient.paths(q: String): List<String> {
        val response = get("/api/v1/search") {
            parameter("q", q)
            parameter("ext", "sql")
            parameter("limit", "100")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray
            .map { it.jsonObject["path"]!!.jsonPrimitive.content }
    }

    private fun List<String>.assertFinds(vararg paths: String) =
        paths.forEach { assertTrue(it in this, "should find $it, found $this") }

    private fun List<String>.assertFirst(path: String) = assertEquals(path, firstOrNull(), "should be first in $this")

    /** Every one of [better] ranks above every one of [worse]. */
    private fun List<String>.assertAbove(better: List<String>, worse: List<String>) {
        assertFinds(*(better + worse).toTypedArray())
        assertTrue(better.maxOf(::indexOf) < worse.minOf(::indexOf), "$better should rank above $worse in $this")
    }

    private fun List<String>.assertAbove(better: String, worse: String) = assertAbove(listOf(better), listOf(worse))

    // Techniques, which examples name only in their descriptions.

    @Test
    fun `techniques an example describes`() = servingExamples {
        client.paths("running total").assertFirst("sales/monthly_revenue_with_running_total.sql")
        client.paths("self join").assertFinds("community/channel_membership_overlap.sql", "flights/round_trips.sql")
        client.paths("union all").assertFinds(
            "flights/delays/delay_causes_breakdown.sql",
            "sales/international/combined_sales_by_country.sql",
            "surveys/fcc/popular_learning_resources.sql",
        )
        client.paths("recursive").assertFinds("hierarchy/category_tree_paths.sql")
        client.paths("pivot").assertFinds("sales/deal_size_mix_by_country.sql")
        client.paths("window").assertFinds("births/state_share_of_births.sql")
        client.paths("heatmap").assertFinds("community/most_active_hours.sql")
    }

    @Test
    fun `what an example is about, from its description`() = servingExamples {
        client.paths("cancelled").assertFinds(
            "flights/cancellation_reasons.sql",
            "flights/delays/worst_days_for_delays.sql",
            "sales/problem_orders.sql",
        )
        client.paths("first order").assertFirst("customers/new_customers_by_month.sql")
    }

    // Names, which rank above descriptions.

    @Test
    fun `examples named for the words come first`() = servingExamples {
        client.paths("rolling average").assertFirst("community/new_members_rolling_average.sql")
        client.paths("life expectancy").assertFinds(
            "world_bank/life_expectancy_vs_income.sql",
            "world_bank/gender/female_life_expectancy_gap.sql",
        )
        client.paths("revenue").assertAbove(
            listOf("sales/revenue_by_product_line.sql", "sales/top_customers_by_revenue.sql"),
            listOf("orders/daily_order_totals.sql", "sales/order_status_breakdown.sql"),
        )
        client.paths("population").assertAbove(
            listOf("world_bank/population_by_region.sql", "geography/sf_population_density.sql"),
            listOf("world_bank/urbanisation_rate.sql"),
        )
        client.paths("best selling").assertAbove(
            listOf("video_games/top_selling_games.sql", "video_games/best_game_per_platform.sql"),
            listOf("orders/top_products.sql", "sales/international/topProductPerCountry.sql"),
        )
    }

    @Test
    fun `whole words rank above prefixes, and descriptions above folders`() = servingExamples {
        val delay = client.paths("delay")
        delay.assertAbove("flights/average_delay_by_airline.sql", "flights/delays/worst_days_for_delays.sql")
        delay.assertAbove("flights/departure_hour_profile.sql", "flights/delays/late-aircraft-knock-on.sql")
    }

    @Test
    fun `folders still find examples`() = servingExamples {
        client.paths("video share").assertFinds("video_games/publisher_market_share.sql")
    }

    // Ties broken by how much descriptions are about the words.

    @Test
    fun `of examples with a CTE named for the word, the one whose description also says it comes first`() =
        servingExamples {
            val ranked = client.paths("ranked")
            ranked.assertFirst("video_games/publisher_market_share.sql")
            ranked.assertFinds(
                "births/top_names_by_decade.sql",
                "sales/quarterly/best_quarter_per_product_line.sql",
                "video_games/best_game_per_platform.sql",
            )
        }

    @Test
    fun `of examples that describe the word, those more about it come first`() = servingExamples {
        // "largest share of people ... their share of children": said twice.
        client.paths("share").assertAbove(
            listOf("world_bank/ageing_populations.sql"),
            listOf("flights/on_time_rate_by_airport.sql", "sales/deal_size_mix_by_country.sql"),
        )
        // "Orders and revenue per day." against a long description that
        // mentions revenue once.
        client.paths("revenue").assertAbove(
            "orders/daily_order_totals.sql",
            "sales/international/combined_sales_by_country.sql",
        )
    }
}
