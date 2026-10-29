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
package com.pyamsoft.tetherfi.server.clients

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.IPV4_ADDRESS_REGEX
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * Checks whether a client that we have already seen is still reachable on the local network.
 *
 * This is how the Connections list notices that a device turned Wi-Fi off or left the hotspot
 * without having to restart the hotspot.
 */
internal interface ClientProbe {

  /**
   * Returns TRUE if the client answered, or if we have no way to judge it (for example it is a
   * hostname instead of an IPv4 address). Returns FALSE only if we positively could not reach it.
   */
  @CheckResult suspend fun isReachable(hostNameOrIp: String): Boolean
}

@Singleton
internal class ClientProbeImpl
@Inject
internal constructor(
    private val dispatchers: AppDispatchers,
) : ClientProbe {

  override suspend fun isReachable(hostNameOrIp: String): Boolean =
      withContext(context = dispatchers.io) {
        // Only probe literal IPv4 addresses. Never trigger a DNS lookup from here
        if (!IPV4_ADDRESS_REGEX.matches(hostNameOrIp)) {
          return@withContext true
        }

        try {
          // Public API. On Android this sends an ICMP echo, and falls back to a TCP echo
          // connection attempt. No root and no special permission is required.
          InetAddress.getByName(hostNameOrIp).isReachable(PROBE_TIMEOUT_MS)
        } catch (e: CancellationException) {
          throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
          Timber.w { "Client probe failed for $hostNameOrIp: ${e.message}" }
          false
        }
      }

  private companion object {
    private const val PROBE_TIMEOUT_MS = 1_500
  }
}
