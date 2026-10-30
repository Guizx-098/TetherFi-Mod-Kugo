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

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ServerDefaultsTest {

  @Test
  fun `prefix is only DIRECT-`() {
    assertEquals("DIRECT-", ServerDefaults.WIFI_SSID_PREFIX)
    assertEquals("DIRECT-Vitor", ServerDefaults.asWifiSsid("Vitor"))
  }

  @Test
  fun `name must start with two ASCII letters`() {
    assertTrue(ServerDefaults.isValidSsidName("Vitor"))
    assertTrue(ServerDefaults.isValidSsidName("Kugo Hotspot"))
    assertTrue(ServerDefaults.isValidSsidName("ab"))
    assertTrue(ServerDefaults.isValidSsidName("Ab1"))
  }

  @Test
  fun `invalid names are rejected`() {
    assertFalse(ServerDefaults.isValidSsidName(""))
    assertFalse(ServerDefaults.isValidSsidName("V"))
    assertFalse(ServerDefaults.isValidSsidName("1a"))
    assertFalse(ServerDefaults.isValidSsidName("_Vitor"))
    assertFalse(ServerDefaults.isValidSsidName("V-itor"))
    assertFalse(ServerDefaults.isValidSsidName("Éa"))
  }

  @Test
  fun `default name is valid`() {
    assertTrue(ServerDefaults.isValidSsidName(ServerDefaults.WIFI_SSID))
  }
}
