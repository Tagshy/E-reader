package com.leitura.app

import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    private val books = listOf(Book("a", "Duna", favorite = true, page = 4, pageCount = 10, lastRead = 100),
        Book("b", "Jogos Vorazes", lastRead = 200), Book("c", "Fundação"))
    @Test fun searchCombinesWithFavorites() {
        assertEquals(listOf(books[0]), visibleBooks(books, "  DUNA ", "Favoritos"))
        assertTrue(visibleBooks(books, "Jogos", "Favoritos").isEmpty())
    }
    @Test fun recentIncludesOnlyOpenedBooksInOrder() {
        assertEquals(listOf(books[1], books[0]), visibleBooks(books, "", "Recentes"))
    }
    @Test fun progressHandlesFirstLastAndUnknownPages() {
        assertEquals(0.5f, books[0].progress, 0.001f)
        assertEquals(0f, Book("x", "x", pageCount = 10).progress)
        assertEquals(1f, Book("x", "x", page = 9, pageCount = 10, lastRead = 1).progress)
        assertEquals(0f, books[1].progress)
    }
}
