package kotlinx.files.test

import kotlinx.files.*
import kotlinx.io.core.*
import kotlinx.io.errors.*
import kotlin.test.*

class ListingTest : TestBase() {

    @Test
    fun testNonExistentListing() {
        val path = testDirectory("non-existent-listing")
        assertFailsWith<IOException> { path.list() }
    }

    @Test
    fun testEmptyListing() {
        val directory = testDirectory("empty-listing").createDirectory()
        assertTrue(directory.list().isEmpty())
    }

    @Test
    fun testListing() {
        val directory = testDirectory("listing").createDirectory()
        Path(directory.toString(), "1.txt").createFile()
        Path(directory.toString(), "2").createDirectory()

        val expected = listOf(testFile("listing/1"), testDirectory("listing/2")).toSet()
        val actual = directory.list().toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun testNestedListing() {
        val directory = testDirectory("nested-listing").createDirectory()
        Path(directory.toString(), "1.txt").createFile()
        val nested = Path(directory.toString(), "2").createDirectory()
        Path(nested.toString(), "3.txt").createFile()

        val expected = listOf(testFile("nested-listing/1"), testDirectory("nested-listing/2")).toSet()
        assertEquals(expected, directory.list().toSet())
    }
}