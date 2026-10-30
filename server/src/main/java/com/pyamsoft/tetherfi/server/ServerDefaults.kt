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

package com.pyamsoft.tetherfi.server

import android.os.Build
import androidx.annotation.CheckResult
import androidx.annotation.ChecksSdkIntAtLeast

object ServerDefaults {

  /** The default network name. Kugo Hotspot is a new app, so there are no old installs to keep. */
  const val WIFI_SSID = "KugoHotspot"

  /** Default port for HTTP server */
  const val HTTP_PORT = 8228

  /** Default port for SOCKS server */
  const val SOCKS_PORT = 8229

  val WIFI_NETWORK_BAND = ServerNetworkBand.LEGACY

  /**
   * Android Wi-Fi Direct requires the network name to start with "DIRECT-xy", where xy are two
   * letters or digits. The two characters after "DIRECT-" are the first two characters of the name
   * the person chooses, so the person must start the name with at least two letters.
   */
  const val WIFI_SSID_PREFIX = "DIRECT-"

  /**
   * A name is valid if it starts with two ASCII letters. Android accepts digits too, but we ask for
   * letters only to stay on the safe side.
   */
  @JvmStatic
  @CheckResult
  fun isValidSsidName(ssid: String): Boolean {
    if (ssid.length < 2) {
      return false
    }

    return ssid.take(2).all { it in 'a'..'z' || it in 'A'..'Z' }
  }

  @JvmStatic
  @CheckResult
  fun asWifiSsid(ssid: String): String {
    return "${WIFI_SSID_PREFIX}${ssid}"
  }

  private const val API_CUSTOM_CONFIG_SUPPORT = 29

  @CheckResult
  @ChecksSdkIntAtLeast(api = API_CUSTOM_CONFIG_SUPPORT)
  fun canUseCustomConfig(): Boolean {
    return Build.VERSION.SDK_INT >= API_CUSTOM_CONFIG_SUPPORT
  }
}
