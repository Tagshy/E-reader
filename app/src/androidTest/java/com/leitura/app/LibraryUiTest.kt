package com.leitura.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun cleanLibraryFavoritesRecentAndUpdatesNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("leitura", 0).edit().clear().commit()
        LibraryStore(context).save(listOf(Book("content://missing/duna", "Duna", favorite = true),
            Book("content://missing/jogos", "Jogos Vorazes", lastRead = 123)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            // Opening uses a coroutine delay; advance the test clock explicitly.
            compose.mainClock.advanceTimeBy(3000)
            compose.waitForIdle()
            println(compose.onRoot().printToString())
            compose.onNodeWithText("Olá, Walter").assertExists()
            compose.onNodeWithText("Sua estante").assertDoesNotExist()
            compose.onNodeWithText("Buscar na biblioteca").assertDoesNotExist()
            compose.onNodeWithContentDescription("Adicionar livro em PDF").assertExists()
            compose.onNodeWithText("Duna").assertExists()
            compose.onNodeWithText("Favoritos", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Duna").assertExists()
            compose.onNodeWithText("Jogos Vorazes").assertDoesNotExist()
            compose.onNodeWithText("Recentes", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Jogos Vorazes").assertExists()
            compose.onNodeWithText("Duna").assertDoesNotExist()
            compose.onNodeWithText("Atualizações", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Verificar atualizações").assertExists()
            compose.onNodeWithText("Recentes", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Jogos Vorazes").performClick()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("‹ Voltar").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("‹ Voltar").performClick()
            scenario.recreate()
            compose.onNodeWithText("Jogos Vorazes").assertExists()
        }
    }
}
