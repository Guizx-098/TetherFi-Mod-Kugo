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
package com.pyamsoft.tetherfi.status.sections.shizuku

import android.content.pm.PackageManager
import androidx.annotation.CheckResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Where Shizuku stands right now */
internal enum class ShizukuState {
  /** Shizuku is not installed or its service is not started */
  NOT_RUNNING,

  /** Shizuku is running, but this app was not allowed yet */
  NEEDS_PERMISSION,

  /** Shizuku is running and this app is allowed */
  READY,
}

internal data class ShizukuCommandResult(
    val exitCode: Int?,
    val output: String,
)

/**
 * EXPERIMENTAL
 *
 * Uses Shizuku to ask Android to turn the built-in hotspot on and off with any network name. This
 * is the only way for the name to not start with "DIRECT-", because that prefix is only required by
 * Wi-Fi Direct.
 *
 * Whether it works depends on the device and on how Shizuku was started (root or ADB). The result
 * of every command is shown to the person so that we can learn what a device allows.
 */
internal object ShizukuHotspot {

  const val REQUEST_CODE = 4242

  @CheckResult
  fun state(): ShizukuState {
    try {
      if (!Shizuku.pingBinder()) {
        return ShizukuState.NOT_RUNNING
      }

      if (Shizuku.isPreV11()) {
        return ShizukuState.NOT_RUNNING
      }

      return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        ShizukuState.READY
      } else {
        ShizukuState.NEEDS_PERMISSION
      }
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
      return ShizukuState.NOT_RUNNING
    }
  }

  /** The user Shizuku runs as: 0 is root, 2000 is ADB (shell) */
  @CheckResult
  fun modeName(): String? =
      try {
        when (Shizuku.getUid()) {
          0 -> "root"
          2000 -> "ADB"
          else -> null
        }
      } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        null
      }

  fun requestPermission() {
    try {
      Shizuku.requestPermission(REQUEST_CODE)
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
      // Shizuku went away, the screen will show it as not running when it refreshes
    }
  }

  @CheckResult
  suspend fun startHotspot(
      ssid: String,
      password: String,
  ): ShizukuCommandResult =
      run("cmd wifi start-softap ${quote(ssid)} wpa2 ${quote(password)}")

  @CheckResult
  suspend fun stopHotspot(): ShizukuCommandResult = run("cmd wifi stop-softap")

  /**
   * Shows what the Shizuku user is allowed to do. This tells us whether turning on the built-in
   * hotspot by calling Android directly could work on this device.
   */
  @CheckResult
  suspend fun diagnose(): ShizukuCommandResult =
      run(
          "echo '== uid'; id; " +
              "echo; echo '== build'; getprop ro.build.type; " +
              "echo; echo '== permissoes do shell'; " +
              "dumpsys package com.android.shell | " +
              "grep -E 'NETWORK_SETTINGS|TETHER_PRIVILEGED|MANAGE_WIFI' | sort -u; " +
              "echo; echo '== servicos'; service list | grep -iE 'tethering|wifi'"
      )

  @CheckResult
  private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  /**
   * In Shizuku API 13 the function that starts a process is private, so it is called by name. If
   * this ever fails, the person sees the message instead of a crash.
   */
  @CheckResult
  private fun newProcess(command: Array<String>): Process {
    val method =
        Shizuku::class
            .java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
    method.isAccessible = true
    return method.invoke(null, command, null, null) as Process
  }

  @CheckResult
  private suspend fun run(command: String): ShizukuCommandResult =
      withContext(context = Dispatchers.IO) {
        try {
          val process = newProcess(arrayOf("sh", "-c", command))

          coroutineScope {
            // Never wait forever on a command
            val watchdog = launch {
              delay(WAIT_LIMIT_MS)
              try {
                process.destroy()
              } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Nothing else to do
              }
            }

            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            watchdog.cancel()

            ShizukuCommandResult(
                exitCode = code,
                output = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trim(),
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
          ShizukuCommandResult(
              exitCode = null,
              output = e.cause?.message ?: e.message ?: e.javaClass.simpleName,
          )
        }
      }

  private const val WAIT_LIMIT_MS = 20_000L
}
