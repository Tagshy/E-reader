package com.leitura.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReleaseUpdaterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val updater = ReleaseUpdater(context)
    private val root = "https://github.com/Tagshy/E-reader/releases/download/test/"
    private fun fixture(code: Int, app: String = BuildConfig.APPLICATION_ID): (String) -> String {
        val release = JSONObject().put("assets", JSONArray()
            .put(JSONObject().put("name", "update.json").put("browser_download_url", root + "update.json"))
            .put(JSONObject().put("name", "Leitura.apk").put("browser_download_url", root + "Leitura.apk")))
        val manifest = JSONObject().put("applicationId", app).put("versionCode", code)
            .put("versionName", "Teste").put("apkName", "Leitura.apk").put("sha256", "a".repeat(64))
        return { url -> if (url == ReleaseUpdater.LATEST) release.toString() else manifest.toString() }
    }
    @Test fun offersNewerVersionButNeverDowngrades() {
        assertEquals(BuildConfig.VERSION_CODE.toLong() + 1, updater.checkForUpdate(fixture(BuildConfig.VERSION_CODE + 1))!!.versionCode)
        assertNull(updater.checkForUpdate(fixture(BuildConfig.VERSION_CODE)))
        assertNull(updater.checkForUpdate(fixture(BuildConfig.VERSION_CODE - 1)))
    }
    @Test fun refusesManifestForAnotherApplication() {
        assertTrue(runCatching { updater.checkForUpdate(fixture(BuildConfig.VERSION_CODE + 1, "other.app")) }.isFailure)
    }
    @Test fun offlineFailureDoesNotAlterLibrary() {
        val before = LibraryStore(context).load()
        assertTrue(runCatching { updater.checkForUpdate { error("offline") } }.isFailure)
        assertEquals(before, LibraryStore(context).load())
    }
    @Test fun installerSharesOnlyTheUpdateFileWithReadAccess() {
        val file = File(context.cacheDir, "updates/Leitura.apk").apply { parentFile!!.mkdirs(); writeText("test") }
        val intent = updater.installIntent(file)
        assertEquals("content", intent.data!!.scheme)
        assertEquals(context.packageName + ".updates", intent.data!!.authority)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val outside = File(context.cacheDir, "private.txt").apply { writeText("not shared") }
        assertTrue(runCatching { updater.installIntent(outside) }.isFailure)
    }
}
