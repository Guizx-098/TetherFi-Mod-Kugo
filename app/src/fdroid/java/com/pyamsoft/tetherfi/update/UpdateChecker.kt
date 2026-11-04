/*
 * Copyright 2026 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pyamsoft.tetherfi.update

import android.content.Context
import androidx.annotation.CheckResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
)

/** Looks for a newer release of this app on its own GitHub page, and downloads it. */
internal object UpdateChecker {

  private const val LATEST_URL =
      "https://api.github.com/repos/Guizx-098/TetherFi-Mod-Kugo/releases/latest"

  private const val CONNECT_TIMEOUT_MS = 10_000
  private const val READ_TIMEOUT_MS = 20_000
  private const val BUFFER_SIZE = 16 * 1024

  @CheckResult
  suspend fun fetchLatest(): UpdateInfo =
      withContext(context = Dispatchers.IO) {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = CONNECT_TIMEOUT_MS
          connection.readTimeout = READ_TIMEOUT_MS
          connection.setRequestProperty("Accept", "application/vnd.github+json")
          connection.setRequestProperty("User-Agent", "KugoHotspot")

          val code = connection.responseCode
          if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub HTTP $code")
          }

          val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
          val tag = json.getString("tag_name")
          val assets = json.getJSONArray("assets")

          for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
              return@withContext UpdateInfo(
                  versionName = tag.removePrefix("v"),
                  notes = json.optString("body", ""),
                  apkUrl = asset.getString("browser_download_url"),
                  apkName = name,
              )
            }
          }

          throw IOException("APK not found in $tag")
        } finally {
          connection.disconnect()
        }
      }

  @CheckResult
  fun installedVersion(context: Context): String =
      try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
      } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        "0"
      }

  /** Compares versions like 1.0.5 and 1.0.10 number by number */
  @CheckResult
  fun isNewer(
      remote: String,
      local: String,
  ): Boolean {
    val r = parse(remote)
    val l = parse(local)
    for (i in 0 until maxOf(r.size, l.size)) {
      val a = r.getOrElse(i) { 0 }
      val b = l.getOrElse(i) { 0 }
      if (a != b) {
        return a > b
      }
    }

    return false
  }

  @CheckResult
  private fun parse(version: String): List<Int> =
      version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

  /** Downloads the APK to the app cache. Cancel the coroutine to stop it. */
  @CheckResult
  suspend fun download(
      context: Context,
      info: UpdateInfo,
      onProgress: (Int) -> Unit,
  ): File =
      withContext(context = Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Only keep the file we are about to download
        dir.listFiles()?.forEach { it.delete() }

        val file = File(dir, info.apkName)
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = CONNECT_TIMEOUT_MS
          connection.readTimeout = READ_TIMEOUT_MS
          connection.setRequestProperty("User-Agent", "KugoHotspot")

          val code = connection.responseCode
          if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("Download HTTP $code")
          }

          val total = connection.contentLengthLong
          var done = 0L
          var lastPercent = -1

          connection.inputStream.use { input ->
            file.outputStream().use { output ->
              val buffer = ByteArray(BUFFER_SIZE)
              while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) {
                  break
                }

                output.write(buffer, 0, read)
                done += read

                if (total > 0) {
                  val percent = (done * 100 / total).toInt()
                  if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                  }
                }
              }
            }
          }

          file
        } catch (e: Throwable) {
          file.delete()
          throw e
        } finally {
          connection.disconnect()
        }
      }
}
