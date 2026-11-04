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
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.annotation.CheckResult
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

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

  /**
   * Turns on the built-in Android hotspot by calling the Wi-Fi service directly as the Shizuku user.
   *
   * The "cmd wifi start-softap" shell command only accepts root, but the Shizuku (shell) user holds
   * the NETWORK_SETTINGS and TETHER_PRIVILEGED permissions, which is what the service itself asks
   * for. The Wi-Fi service is a hidden Android interface, so it is called by name, and the
   * result (or the exact error) is always shown to the person.
   */
  @CheckResult
  suspend fun startHotspot(
      ssid: String,
      password: String,
  ): ShizukuCommandResult =
      withContext(context = Dispatchers.IO) {
        try {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext ShizukuCommandResult(
                exitCode = null,
                output = "Android 11 or newer is required",
            )
          }

          val service = wifiService()
          val config = buildConfig(ssid = ssid, password = password)
          val method =
              service.javaClass.methods.firstOrNull { it.name == "startTetheredHotspot" }
                  ?: return@withContext ShizukuCommandResult(
                      exitCode = null,
                      output = "startTetheredHotspot not found.\n" + listHotspotMethods(service),
                  )

          val args: Array<Any?> =
              when (method.parameterTypes.size) {
                1 -> arrayOf(config)
                2 -> arrayOf(config, SHELL_PACKAGE)
                else ->
                    return@withContext ShizukuCommandResult(
                        exitCode = null,
                        output =
                            "startTetheredHotspot has an unexpected shape.\n" +
                                listHotspotMethods(service),
                    )
              }

          val started = method.invoke(service, *args)
          ShizukuCommandResult(
              exitCode = if (started == true) 0 else 1,
              output = "startTetheredHotspot -> $started",
          )
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
          ShizukuCommandResult(exitCode = null, output = describe(e))
        }
      }

  @CheckResult
  suspend fun stopHotspot(): ShizukuCommandResult =
      withContext(context = Dispatchers.IO) {
        try {
          val service = wifiService()
          val method =
              service.javaClass.methods.firstOrNull { it.name == "stopSoftAp" }
                  ?: return@withContext ShizukuCommandResult(
                      exitCode = null,
                      output = "stopSoftAp not found.\n" + listHotspotMethods(service),
                  )

          val args: Array<Any?> =
              when (method.parameterTypes.size) {
                0 -> emptyArray()
                else -> arrayOf(SHELL_PACKAGE)
              }

          val stopped = method.invoke(service, *args)
          ShizukuCommandResult(
              exitCode = if (stopped == true) 0 else 1,
              output = "stopSoftAp -> $stopped",
          )
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
          ShizukuCommandResult(exitCode = null, output = describe(e))
        }
      }

  /** The Wi-Fi service, whose calls are made by Shizuku as the shell user */
  @CheckResult
  private fun wifiService(): Any {
    // The Wi-Fi interface is hidden from apps, so allow looking it up by name
    HiddenApiBypass.addHiddenApiExemptions("")

    val binder: IBinder =
        SystemServiceHelper.getSystemService("wifi") ?: error("Wi-Fi service not found")
    val wrapped = ShizukuBinderWrapper(binder)

    val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
    return stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
        ?: error("Wi-Fi service is not available")
  }

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.R)
  private fun buildConfig(
      ssid: String,
      password: String,
  ): SoftApConfiguration =
      SoftApConfiguration.Builder()
          .setSsid(ssid)
          .setPassphrase(password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
          .build()

  @CheckResult
  private fun listHotspotMethods(service: Any): String =
      service.javaClass.methods
          .filter { it.name.contains("SoftAp", ignoreCase = true) || it.name.contains("Tether", ignoreCase = true) }
          .joinToString(separator = "\n") { m ->
            "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
          }

  @CheckResult
  private fun describe(error: Throwable): String {
    val root = if (error is InvocationTargetException) error.cause ?: error else error
    return "${root.javaClass.simpleName}: ${root.message}"
  }

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

  /** The package of the shell user. Calls made through Shizuku run as this package */
  private const val SHELL_PACKAGE = "com.android.shell"
}
