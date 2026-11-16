package com.leitura.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String)

fun trustedReleaseUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
        uri.port == -1 && uri.rawPath.startsWith("/Tagshy/E-reader/releases/download/") &&
        !uri.rawPath.contains("..") && !uri.rawPath.contains('%') &&
        uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

/** Downloads and installs only after explicit user actions; reading stays offline. */
class ReleaseUpdater(private val context: Context) {
    companion object {
        const val LATEST = "https://api.github.com/repos/Tagshy/E-reader/releases/latest"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
    private fun connection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "Leitura/" + BuildConfig.VERSION_NAME)
        return connection
    }
    private fun readText(url: String): String {
        val connection = connection(url)
        try {
            check(connection.responseCode == 200) { "Não foi possível consultar o GitHub. Tente novamente mais tarde." }
            return connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    check(text.length + n <= 1024 * 1024) { "Resposta de atualização inválida." }
                    text.append(buffer, 0, n)
                }
                text.toString()
            }
        } finally { connection.disconnect() }
    }
    fun checkForUpdate(fetch: (String) -> String = ::readText): AppUpdate? {
        val release = JSONObject(fetch(LATEST))
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.getJSONArray("assets")
        val manifestAsset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .find { it.optString("name") == "update.json" } ?: return null
        val manifestUrl = manifestAsset.getString("browser_download_url")
        check(trustedReleaseUrl(manifestUrl)) { "Origem da atualização inválida." }
        val manifest = JSONObject(fetch(manifestUrl))
        check(manifest.getString("applicationId") == BuildConfig.APPLICATION_ID) { "Atualização de outro aplicativo." }
        val code = manifest.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE.toLong()) return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .single { it.getString("name") == manifest.getString("apkName") }
        val apkUrl = apk.getString("browser_download_url")
        val hash = manifest.getString("sha256").lowercase()
        check(trustedReleaseUrl(apkUrl) && hash.matches(Regex("[a-f0-9]{64}"))) { "Atualização inválida." }
        return AppUpdate(code, manifest.getString("versionName"), apkUrl, hash)
    }
    fun download(update: AppUpdate, onProgress: (Float?) -> Unit): File {
        check(trustedReleaseUrl(update.apkUrl)) { "Origem da atualização inválida." }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "download.part.apk")
        val result = File(directory, "Leitura.apk")
        val connection = connection(update.apkUrl)
        try {
            check(connection.responseCode == 200) { "Falha ao baixar a atualização." }
            val length = connection.contentLengthLong
            check(length <= MAX_APK_BYTES) { "Atualização maior que o limite permitido." }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var previous = -2
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    check(copied <= MAX_APK_BYTES) { "Atualização maior que o limite permitido." }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    val percent = if (length > 0) (copied * 100 / length).toInt() else -1
                    if (percent != previous) {
                        onProgress(if (length > 0) copied.toFloat() / length else null)
                        previous = percent
                    }
                }
            } }
            check(copied > 0 && (length < 0 || copied == length)) { "Download incompleto. Tente novamente." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == update.sha256) { "O arquivo está incompleto ou foi alterado. Tente novamente." }
            validateApk(partial, update.versionCode)
            if (result.exists()) check(result.delete())
            check(partial.renameTo(result))
            return result
        } finally { connection.disconnect(); partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun validateApk(file: File, expectedVersion: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: error("O arquivo recebido não é um APK válido.")
        check(archive.packageName == context.packageName) { "O APK pertence a outro aplicativo." }
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(version == expectedVersion && version > BuildConfig.VERSION_CODE) { "A versão recebida não é mais recente." }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val currentSignatures = signatures(installed)
        check(signatures(archive) == currentSignatures && currentSignatures.isNotEmpty()) {
            "Esta versão tem assinatura diferente. Não desinstale o app: use apenas atualizações da mesma assinatura."
        }
    }
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signers.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
    fun installIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".updates", file), "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
