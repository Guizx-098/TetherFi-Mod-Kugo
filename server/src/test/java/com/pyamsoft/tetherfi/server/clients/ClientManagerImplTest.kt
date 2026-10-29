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

import android.annotation.SuppressLint
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.server.TweakPreferences
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

  override fun instant(): Instant = current

  fun advance(duration: Duration) {
    current = current.plus(duration.toJavaDuration())
  }

  fun nowLocalDateTime(): LocalDateTime = LocalDateTime.now(this)
}

private class FakeInAppRatingPreferences : InAppRatingPreferences {
  var deviceConnectedCount = 0
    private set

  override fun listenShowInAppRating(): Flow<Boolean> = MutableStateFlow(false)

  override fun markHotspotUsed() = Unit

  override fun markAppOpened() = Unit

  override fun markDeviceConnected() {
    deviceConnectedCount += 1
  }
}

private class FakeTweakPreferences : TweakPreferences {
  override fun listenForStartIgnoreVpn(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreVpn(ignore: Boolean) = Unit

  override fun listenForStartIgnoreLocation(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreLocation(ignore: Boolean) = Unit

  override fun listenForShutdownWithNoClients(): Flow<Boolean> = MutableStateFlow(false)

  override fun setShutdownWithNoClients(shutdown: Boolean) = Unit

  override fun listenForWakeLock(): Flow<Boolean> = MutableStateFlow(false)

  override fun setWakeLock(wakelock: Boolean) = Unit
}

/** A probe whose answers the test controls. Anything not listed is reachable. */
private class FakeClientProbe : ClientProbe {
  val unreachable = mutableSetOf<String>()

  override suspend fun isReachable(hostNameOrIp: String): Boolean = hostNameOrIp !in unreachable
}

private fun newManager(
    clock: Clock,
    dispatchers: AppDispatchers,
    inAppRatingPreferences: InAppRatingPreferences = FakeInAppRatingPreferences(),
    tweakPreferences: TweakPreferences = FakeTweakPreferences(),
    nickNamePreferences: NickNamePreferences = FakeNickNamePreferences(),
    clientProbe: ClientProbe = FakeClientProbe(),
): ClientManagerImpl =
    ClientManagerImpl(
        inAppRatingPreferences = inAppRatingPreferences,
        clock = clock,
        shutdownBus = EventBus.create(),
        dispatchers = dispatchers,
        tweakPreferences = tweakPreferences,
        nickNamePreferences = nickNamePreferences,
        clientProbe = clientProbe,
    )

class FakeNickNamePreferences(initial: Map<String, String> = emptyMap()) : NickNamePreferences {
  val store = initial.toMutableMap()

  override suspend fun listNickNames(): Map<String, String> = store.toMap()

  override suspend fun saveNickName(key: String, nickName: String) {
    if (nickName.isBlank()) store.remove(key) else store[key] = nickName
  }
}

class ClientManagerImplTest {

  @Test
  fun `nickname survives clear and reconnect`() = runTest {
    val prefs = FakeNickNamePreferences()
    val manager = newManager(
        clock = Clock.systemUTC(),
        dispatchers = AppDispatchers.create(),
        nickNamePreferences = prefs)
    manager.loadSavedNickNames()

    val client = manager.ensure("192.168.22.1")
    manager.updateNickName(client, "Vitor")
    assertEquals("Vitor", manager.ensure("192.168.22.1").nickName)

    // Hotspot stopped / client purged
    manager.clear()
    assertEquals("Vitor", manager.ensure("192.168.22.1").nickName)
    assertEquals("Vitor", prefs.store["192.168.22.1"])
  }

  @Test
  fun `nickname survives app restart`() = runTest {
    val manager = newManager(
        clock = Clock.systemUTC(),
        dispatchers = AppDispatchers.create(),
        nickNamePreferences = FakeNickNamePreferences(mapOf("192.168.22.1" to "Vitor")))
    manager.loadSavedNickNames()
    assertEquals("Vitor", manager.ensure("192.168.22.1").nickName)
    // A different IP never inherits the name
    assertEquals("", manager.ensure("192.168.22.2").nickName)
  }

  @Test
  fun `blank nickname clears only that client`() = runTest {
    val prefs = FakeNickNamePreferences(mapOf("192.168.22.1" to "Vitor", "192.168.22.2" to "Ana"))
    val manager = newManager(
        clock = Clock.systemUTC(),
        dispatchers = AppDispatchers.create(),
        nickNamePreferences = prefs)
    manager.loadSavedNickNames()
    val client = manager.ensure("192.168.22.1")
    manager.ensure("192.168.22.2")

    manager.updateNickName(client, "")

    assertEquals(mapOf("192.168.22.2" to "Ana"), prefs.store)
  }

  @Test
  fun `only report new client once`() = runTest {
    val ratingPreferences = FakeInAppRatingPreferences()
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            inAppRatingPreferences = ratingPreferences,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    val first = manager.ensure("1.2.3.4")
    val second = manager.ensure("1.2.3.4")

    assertSame(first, second)
    assertEquals(1, ratingPreferences.deviceConnectedCount)
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `block marks client blocked and unblock reverses it`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.2.3.4")

    assertFalse(manager.isBlocked(client))

    manager.block(client)
    assertTrue(manager.isBlocked(client))
    assertEquals(1, manager.listenForBlocked().first().size)

    manager.unblock(client)
    assertFalse(manager.isBlocked(client))
    assertEquals(0, manager.listenForBlocked().first().size)
  }

  @Test
  fun `isBlocked is true when over transfer limit without being manually blocked`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val overLimitClient =
        TetherClient.testCreate(
            hostNameOrIp = "1.2.3.4",
            clock = MutableClock(Instant.EPOCH),
            nickName = "",
            transferLimit = TransferAmount(amount = 1, unit = TransferUnit.KB),
            totalBytes = ByteTransferReport(internetToProxy = 0L, proxyToInternet = 2048L),
        )

    assertTrue(manager.isBlocked(overLimitClient))
  }

  @Test
  fun `seen updates last seen time for known client and is a no-op for unknown`() = runTest {
    val start = Instant.EPOCH
    val clock = MutableClock(start)
    val manager =
        newManager(
            clock = clock,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    val client = manager.ensure("1.2.3.4")
    val seenAt = client.mostRecentlySeen

    clock.advance(5.minutes)
    manager.seen(client)

    val updated = manager.listenForClients().first().single()
    assertTrue(updated.mostRecentlySeen.isAfter(seenAt))

    val unknown = TetherClient.create(hostNameOrIp = "9.9.9.9", clock = clock)
    manager.seen(unknown)
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `reportTransfer merges bytes for known client and is a no-op for unknown`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.2.3.4")

    manager.reportTransfer(
        client,
        ByteTransferReport(internetToProxy = 100L, proxyToInternet = 50L),
    )
    manager.reportTransfer(
        client,
        ByteTransferReport(internetToProxy = 10L, proxyToInternet = 5L),
    )

    val updated = manager.listenForClients().first().single()
    assertEquals(110L, updated.transferFromInternet.bytes)
    assertEquals(55L, updated.transferToInternet.bytes)

    val unknown = TetherClient.create(hostNameOrIp = "9.9.9.9", clock = MutableClock(Instant.EPOCH))
    manager.reportTransfer(unknown, ByteTransferReport(internetToProxy = 1L, proxyToInternet = 1L))
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `updateNickName updates known client and is a no-op for unknown`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.2.3.4")

    manager.updateNickName(client, "Bob")
    assertEquals("Bob", manager.listenForClients().first().single().nickName)

    val unknown = TetherClient.create(hostNameOrIp = "9.9.9.9", clock = MutableClock(Instant.EPOCH))
    manager.updateNickName(unknown, "Nope")
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `updateTransferLimit updates known client and is a no-op for unknown`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.2.3.4")
    val limit = TransferAmount(amount = 5, unit = TransferUnit.MB)

    manager.updateTransferLimit(client, limit)
    assertEquals(limit, manager.listenForClients().first().single().transferLimit)

    val unknown = TetherClient.create(hostNameOrIp = "9.9.9.9", clock = MutableClock(Instant.EPOCH))
    manager.updateTransferLimit(unknown, limit)
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `updateBandwidthLimit updates known client and is a no-op for unknown`() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.2.3.4")
    val limit = TransferAmount(amount = 5, unit = TransferUnit.MB)

    manager.updateBandwidthLimit(client, limit)
    assertEquals(limit, manager.listenForClients().first().single().bandwidthLimit)

    val unknown = TetherClient.create(hostNameOrIp = "9.9.9.9", clock = MutableClock(Instant.EPOCH))
    manager.updateBandwidthLimit(unknown, limit)
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `purge ages out old clients`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val manager =
        newManager(
            clock = clock,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    @SuppressLint("CheckResult") manager.ensure("1.1.1.1")

    clock.advance(10.minutes)

    @SuppressLint("CheckResult") manager.ensure("2.2.2.2")

    val cutoff = clock.nowLocalDateTime().minusMinutes(5)
    manager.purgeOldClients(cutoff)

    val remaining = manager.listenForClients().first()
    assertEquals(1, remaining.size)
    assertEquals("2.2.2.2", (remaining.single() as IpAddressClient).ip)
  }

  @Test
  fun `block survives purge even after client goes idle`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val manager =
        newManager(
            clock = clock,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    val client = manager.ensure("1.1.1.1")
    manager.block(client)

    // Before time advances, we have both
    assertEquals(1, manager.listenForClients().first().size)
    assertEquals(1, manager.listenForBlocked().first().size)

    // Then time advances but not pass deadline
    manager.purgeOldClients(clock.nowLocalDateTime().minusMinutes(5))
    assertEquals(1, manager.listenForClients().first().size)
    assertEquals(1, manager.listenForBlocked().first().size)

    // A blocked client can never generate activity, so it will always look "idle" -- the purge
    // must not un-block it just because time passed
    clock.advance(10.minutes)
    manager.purgeOldClients(clock.nowLocalDateTime().minusMinutes(5))
    assertEquals(1, manager.listenForClients().first().size)
    assertEquals(1, manager.listenForBlocked().first().size)

    // Still only goes away via an explicit unblock
    manager.unblock(client)
    assertEquals(0, manager.listenForBlocked().first().size)
    manager.purgeOldClients(clock.nowLocalDateTime().minusMinutes(5))
    assertEquals(0, manager.listenForClients().first().size)
  }

  @Test
  fun clear() = runTest {
    val manager =
        newManager(
            clock = MutableClock(Instant.EPOCH),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = manager.ensure("1.1.1.1")
    manager.block(client)

    manager.clear()

    assertEquals(0, manager.listenForClients().first().size)
    assertEquals(0, manager.listenForBlocked().first().size)
  }

  @Test
  fun `client that left the network is removed from the list but keeps its nickname`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val probe = FakeClientProbe()
    val prefs = FakeNickNamePreferences()
    val manager =
        newManager(
            clock = clock,
            dispatchers = AppDispatchers.create(),
            nickNamePreferences = prefs,
            clientProbe = probe,
        )
    manager.loadSavedNickNames()

    val client = manager.ensure("192.168.49.20")
    manager.updateNickName(client, "Vitor")
    manager.ensure("192.168.49.21")

    // Everyone answers, nothing changes
    assertFalse(manager.checkPresence())
    assertEquals(2, manager.listenForClients().first().size)

    // The first device leaves, but it just used the proxy so it is not removed yet
    probe.unreachable.add("192.168.49.20")
    assertFalse(manager.checkPresence())
    assertEquals(2, manager.listenForClients().first().size)

    // After the traffic grace period it must fail several checks in a row before it is removed
    clock.advance(1.minutes)
    assertTrue(manager.checkPresence())
    assertTrue(manager.checkPresence())
    assertEquals(2, manager.listenForClients().first().size)
    assertFalse(manager.checkPresence())

    val remaining = manager.listenForClients().first()
    assertEquals(listOf("192.168.49.21"), remaining.map { it.key() })

    // The saved nickname was not erased...
    assertEquals("Vitor", prefs.store["192.168.49.20"])

    // ...and is restored automatically when the device comes back
    probe.unreachable.clear()
    assertEquals("Vitor", manager.ensure("192.168.49.20").nickName)
    assertEquals(2, manager.listenForClients().first().size)
  }

  @Test
  fun `one missed check does not remove a client`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val probe = FakeClientProbe()
    val manager =
        newManager(clock = clock, dispatchers = AppDispatchers.create(), clientProbe = probe)
    manager.ensure("192.168.49.20")
    clock.advance(1.minutes)

    probe.unreachable.add("192.168.49.20")
    assertTrue(manager.checkPresence())
    assertTrue(manager.checkPresence())

    // It answers again, so the failure count starts over
    probe.unreachable.clear()
    assertFalse(manager.checkPresence())
    probe.unreachable.add("192.168.49.20")
    assertTrue(manager.checkPresence())
    assertTrue(manager.checkPresence())

    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `client using the proxy is never removed even if it does not answer pings`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val probe = FakeClientProbe()
    val manager =
        newManager(clock = clock, dispatchers = AppDispatchers.create(), clientProbe = probe)
    val client = manager.ensure("192.168.49.20")
    probe.unreachable.add("192.168.49.20")

    repeat(10) {
      clock.advance(10.seconds)
      manager.seen(client)
      manager.checkPresence()
    }

    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `a device that is gone is not shown again until it reconnects`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val probe = FakeClientProbe()
    val manager =
        newManager(clock = clock, dispatchers = AppDispatchers.create(), clientProbe = probe)
    manager.ensure("192.168.49.20")
    clock.advance(1.minutes)
    probe.unreachable.add("192.168.49.20")
    repeat(3) { manager.checkPresence() }
    assertTrue(manager.listenForClients().first().isEmpty())

    // Checking an empty list is harmless and does not bring anyone back
    assertFalse(manager.checkPresence())
    assertTrue(manager.listenForClients().first().isEmpty())

    // Reconnect: exactly one entry, no duplicates
    manager.ensure("192.168.49.20")
    manager.ensure("192.168.49.20")
    assertEquals(1, manager.listenForClients().first().size)
  }

  @Test
  fun `idle client that still answers survives the old purge`() = runTest {
    val clock = MutableClock(Instant.EPOCH)
    val manager = newManager(clock = clock, dispatchers = AppDispatchers.create())
    manager.ensure("192.168.49.20")

    clock.advance(10.minutes)
    // The client is idle but answers probes
    assertFalse(manager.checkPresence())
    manager.purgeOldClients(clock.nowLocalDateTime().minusMinutes(5))

    assertEquals(1, manager.listenForClients().first().size)
  }
}
