package codesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// docs/features/003-browse-tree.md: Paths
class RepoPathTest {
    @Test
    fun `empty string is the root`() {
        assertTrue(RepoPath.parse("")!!.isRoot)
    }

    @Test
    fun `canonical paths are accepted unchanged`() {
        listOf(
            "orders",
            "orders/top_products.sql",
            "a/b/c/d.sql",
            ".github",
            "..hidden/x",
            "x..",
            "with space/and\\backslash",
            "ünïcode/文件.sql",
        ).forEach { raw ->
            assertEquals(raw, RepoPath.parse(raw)?.value, "should accept '$raw'")
        }
    }

    @Test
    fun `non-canonical paths are rejected`() {
        listOf(
            "/",
            "/orders",
            "orders/",
            "orders//x.sql",
            ".",
            "..",
            "./orders",
            "orders/.",
            "../etc/passwd",
            "orders/../../etc",
            "orders/./x.sql",
            "a\u0000b",
        ).forEach { raw ->
            assertNull(RepoPath.parse(raw), "should reject '$raw'")
        }
    }
}
