package com.haitang000.dakamiao

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.haitang000.dakamiao.databinding.ActivityAboutBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 关于页：版本信息、检查更新（GitHub Releases）、开源地址。 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private var downloadUrl: String? = null

    private val currentVersion: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tvVersion.text = "版本 v$currentVersion"
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnDownload.setOnClickListener {
            openUrl(downloadUrl ?: "$REPO_URL/releases/latest")
        }
        binding.rowGithub.setOnClickListener { openUrl(REPO_URL) }
    }

    private fun checkUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnDownload.visibility = View.GONE
        binding.tvUpdateStatus.text = "检查中…"
        Thread {
            val info = fetchLatest()
            runOnUiThread {
                binding.btnCheckUpdate.isEnabled = true
                if (info == null) {
                    binding.tvUpdateStatus.text =
                        "检查失败：请检查网络，或该仓库为私有（需设为公开才能检查更新）。"
                    return@runOnUiThread
                }
                val (tag, apk, html) = info
                if (isNewer(tag, currentVersion)) {
                    binding.tvUpdateStatus.text = "发现新版本 $tag（当前 v$currentVersion）"
                    downloadUrl = apk ?: html
                    binding.btnDownload.visibility = View.VISIBLE
                } else {
                    binding.tvUpdateStatus.text = "已是最新版本（v$currentVersion）"
                }
            }
        }.start()
    }

    /** 返回 (tag_name, apk下载地址?, release页地址)。失败返回 null。 */
    private fun fetchLatest(): Triple<String, String?, String>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/$REPO/releases/latest")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DaKaMiao-App")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifEmpty { return null }
            val html = json.optString("html_url", "$REPO_URL/releases/latest")
            var apk: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            Triple(tag, apk, html)
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 语义化版本比较：remote 是否比 current 新。 */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = parseVer(remote)
        val c = parseVer(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseVer(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_', '+')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REPO = "haitang000/dakamiao"
        private const val REPO_URL = "https://github.com/haitang000/dakamiao"
    }
}
