package com.mytv.cn.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mytv.cn.BuildConfig
import com.mytv.cn.data.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.RELEASE_REPOSITORY}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MYTV/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val root = JSONObject(response.body?.string().orEmpty())
            val tag = root.optString("tag_name")
            if (!isNewer(tag.removePrefix("V").removePrefix("v"), BuildConfig.VERSION_NAME)) return@withContext null
            val assets = root.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?: return@withContext null
            ReleaseInfo(
                tag = tag,
                name = root.optString("name", tag),
                body = root.optString("body"),
                apkUrl = apk.optString("browser_download_url"),
                digest = apk.optString("digest").removePrefix("sha256:")
            )
        }
    }

    suspend fun download(release: ReleaseInfo, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach(File::delete)
        val target = File(directory, "MYTV-${release.tag}.apk")
        val request = Request.Builder().url(release.apkUrl)
            .header("User-Agent", "MYTV/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败 (${response.code})" }
            val body = response.body ?: error("下载内容为空")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        progress(copied, body.contentLength())
                    }
                }
            }
        }
        if (release.digest.isNotBlank()) {
            check(sha256(target).equals(release.digest, true)) { "APK 校验失败" }
        }
        target
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("apk", uri)
        }
        context.startActivity(intent)
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
