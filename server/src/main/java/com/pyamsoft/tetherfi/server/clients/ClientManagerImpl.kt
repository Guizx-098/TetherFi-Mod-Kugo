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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.server.clients

import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
internal class ClientManagerImpl
@Inject
internal constructor(
    private val inAppRatingPreferences: InAppRatingPreferences,
    private val clock: Clock,
    private val shutdownBus: EventBus<ServerShutdownEvent>,
    private val tweakPreferences: TweakPreferences,
    private val nickNamePreferences: NickNamePreferences,
    private val clientProbe: ClientProbe,
    private val dispatchers: AppDispatchers,
) :
    BlockedClientTracker,
    BlockedClients,
    AllowedClients,
    ClientEraser,
    StartedClients,
    ClientResolver,
    ClientPresenceRefresher,
    ClientEditor {

  private val blockedClients = MutableStateFlow<Map<String, TetherClient>>(emptyMap())
  private val allowedClients = MutableStateFlow<Map<String, TetherClient>>(emptyMap())

  // Nicknames survive client purges, hotspot restarts (clear()) and app restarts.
  // Loaded from disk in started(), and kept in sync on every edit.
  private val savedNickNames = MutableStateFlow<Map<String, String>>(emptyMap())

  private val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

  // Presence tracking. This is only bookkeeping for "is this device still on the network" and is
  // never persisted. Saved nicknames live in savedNickNames and are not touched by any of this.
  //
  // The last time a probe confirmed that a client answered
  private val reachableAt = ConcurrentHashMap<String, LocalDateTime>()

  // How many checks in a row a client has failed to answer
  private val probeFailures = ConcurrentHashMap<String, Int>()

  // Conflated: any number of "please check now" requests collapse into one pending check
  private val presenceRequests = Channel<Unit>(Channel.CONFLATED)

  @CheckResult
  private fun markLastSeenNow(client: TetherClient): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(mostRecentlySeen = LocalDateTime.now(clock))
        is HostNameClient -> client.copy(mostRecentlySeen = LocalDateTime.now(clock))
      }

  @CheckResult
  private fun editNickName(client: TetherClient, nickName: String): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(nickName = nickName)
        is HostNameClient -> client.copy(nickName = nickName)
      }

  @CheckResult
  private fun editTransferLimit(client: TetherClient, limit: TransferAmount?): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(transferLimit = limit)
        is HostNameClient -> client.copy(transferLimit = limit)
      }

  @CheckResult
  private fun editBandwidthLimit(client: TetherClient, limit: TransferAmount?): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(bandwidthLimit = limit)
        is HostNameClient -> client.copy(bandwidthLimit = limit)
      }

  @CheckResult
  private fun updateTransferReport(client: TetherClient, report: ByteTransferReport): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(totalBytes = client.mergeReport(report))
        is HostNameClient -> client.copy(totalBytes = client.mergeReport(report))
      }

  private fun onNewClientSeen(client: TetherClient) {
    Timber.d { "First time seeing client: $client" }
    inAppRatingPreferences.markDeviceConnected()
  }

  @VisibleForTesting
  internal fun purgeOldClients(cutoffTime: LocalDateTime) {
    Timber.d { "Attempt to purge old clients before $cutoffTime" }

    // "Live" client must have activity within 2 minutes
    //
    // A client which is "blocked" MUST ALWAYS SURVIVE
    // otherwise it will be removed and the block will be "forgotten"
    allowedClients.updateAndGet { list ->
      list.filter { entry ->
        val client = entry.value
        // NEVER purge a blocked client out of the list
        if (isBlockedClient(client)) {
          Timber.d { "Client is current blocked. Keep it around to avoid unblocking: $client" }
          return@filter true
        }

        // A client that still answers our probes is still connected, even if it is idle
        val stillReachable = reachableAt[client.key()]?.let { it >= cutoffTime } == true
        if (stillReachable) {
          return@filter true
        }

        val newEnough = client.mostRecentlySeen >= cutoffTime
        if (!newEnough) {
          Timber.d { "Client is too old: $client. Last seen ${client.mostRecentlySeen}" }
        }
        return@filter newEnough
      }
    }

    // NOTE(Peter): We NEVER filter out block clients by timer otherwise it causes clients to
    // "unblock"

    // Don't call shutdownWithNoClients here, we want to call it only on its own schedule
  }

  private suspend fun shutdownWithNoClients() {
    if (isShutdownWithNoClientsEnabled()) {
      if (allowedClients.value.isEmpty()) {
        Timber.d { "No clients are connected. Shutdown Proxy!" }
        shutdownBus.emit(ServerShutdownEvent(throwable = null))
      }
    }
  }

  private fun CoroutineScope.watchForOldClients() {
    val scope = this

    jobs.add(
        scope.launch(context = dispatchers.default) {
          Timber.d { "Watch client count and purge old clients" }
          onTimerElapsed(OLD_CLIENT_TIMER_PERIOD) { purgeOldClients(it) }
        }
    )
  }

  private fun CoroutineScope.watchForDisconnectedClients() {
    val scope = this

    jobs.add(
        scope.launch(context = dispatchers.default) {
          Timber.d { "Watch for clients that have left the network" }
          try {
            while (true) {
              val suspicious = checkPresence()

              // Look again quickly while we are unsure about someone, otherwise relax.
              // The wait is cut short as soon as the Wi-Fi group reports that something changed.
              val wait = if (suspicious) PRESENCE_RECHECK_PERIOD else PRESENCE_CHECK_PERIOD
              withTimeoutOrNull(wait) { presenceRequests.receive() }
            }
          } finally {
            Timber.d { "Stopped watching for clients that have left the network" }
          }
        }
    )
  }

  /**
   * Verify every listed client is still on the network, and remove the ones that are gone.
   *
   * A client is removed only when ALL of these are true:
   * - It failed [PRESENCE_MAX_FAILURES] checks in a row, and
   * - It has not sent any proxy traffic within [PRESENCE_TRAFFIC_GRACE]
   *
   * Removing a client only removes it from the live list. Its saved nickname, and any block, are
   * kept so that they are applied again when the device returns.
   *
   * @return TRUE if some client is currently under suspicion and should be re-checked soon
   */
  @VisibleForTesting
  internal suspend fun checkPresence(): Boolean {
    val snapshot = allowedClients.value.values.toList()
    if (snapshot.isEmpty()) {
      return false
    }

    val results =
        coroutineScope {
          snapshot
              .map { client ->
                async {
                  val key = client.key()
                  return@async key to clientProbe.isReachable(key)
                }
              }
              .awaitAll()
        }

    val now = LocalDateTime.now(clock)
    val trafficCutoff = now.minus(PRESENCE_TRAFFIC_GRACE.toJavaDuration())
    val gone = mutableSetOf<String>()
    var suspicious = false

    for ((key, reachable) in results) {
      // Use the freshest data, the client may have been updated or removed while we probed
      val current = allowedClients.value[key] ?: continue

      if (reachable) {
        reachableAt[key] = now
        probeFailures.remove(key)
      } else if (current.mostRecentlySeen >= trafficCutoff) {
        // It is unreachable by ping but is actively using the proxy: it is clearly connected.
        // Some devices simply do not answer pings.
        probeFailures.remove(key)
      } else {
        val failures = (probeFailures[key] ?: 0) + 1
        probeFailures[key] = failures
        if (failures >= PRESENCE_MAX_FAILURES) {
          gone.add(key)
        } else {
          suspicious = true
        }
      }
    }

    if (gone.isNotEmpty()) {
      var removed: Set<String> = emptySet()
      allowedClients.update { clients ->
        // Never remove someone who sent traffic while we were checking
        val toRemove =
            gone.filterTo(mutableSetOf()) { key ->
              val client = clients[key]
              client != null && client.mostRecentlySeen < trafficCutoff
            }
        removed = toRemove
        return@update clients.filterKeys { it !in toRemove }
      }

      removed.forEach { key ->
        Timber.d { "Client left the network, remove from list: $key" }
        reachableAt.remove(key)
        probeFailures.remove(key)
      }
    }

    return suspicious
  }

  override fun requestPresenceCheck() {
    presenceRequests.trySend(Unit)
  }

  @CheckResult
  private suspend fun isShutdownWithNoClientsEnabled(): Boolean {
    return tweakPreferences.listenForShutdownWithNoClients().first()
  }

  private suspend fun CoroutineScope.watchForNoClients() {
    val scope = this

    // Start new if needed
    if (isShutdownWithNoClientsEnabled()) {
      // Remember when we started watching
      //
      // We do this weird check because in development I noticed when starting the server
      // once a random ShutdownWithNoClients was published during startup, which caused
      // the app to break since Broadcast started but Proxy never started
      val startedAt = LocalDateTime.now(clock)

      jobs.add(
          scope.launch(context = dispatchers.default) {
            Timber.d { "Watch client count and shutdown if none" }
            onTimerElapsed(NO_CLIENTS_TIMER_PERIOD) { cutoff ->
              if (startedAt >= cutoff) {
                Timber.w { "Shutdown check received but client started AFTER cutoff - invalid" }
              } else {
                shutdownWithNoClients()
              }
            }
          },
      )
    }
  }

  @CheckResult
  private fun isBlockedClient(client: TetherClient): Boolean {
    return blockedClients.value.contains(client.key())
  }

  private suspend inline fun onTimerElapsed(period: Duration, block: (LocalDateTime) -> Unit) {
    try {
      while (true) {
        delay(period)
        val cutoffTime = LocalDateTime.now(clock).minusMinutes(period.inWholeMinutes)

        Timber.d { "Timer elapsed. Cutoff: $cutoffTime" }
        block(cutoffTime)
      }
    } finally {
      Timber.d { "Timer completed." }
    }
  }

  @CheckResult
  private fun checkBlocked(client: TetherClient): Boolean {
    // Check Blocklist
    if (isBlockedClient(client)) {
      Timber.w { "Hard blocked client: $client" }
      return true
    }

    // Check we are not over the transfer limit
    if (client.isOverTransferLimit()) {
      Timber.w { "Transfer limited client: $client" }
      return true
    }

    return false
  }

  @CheckResult
  private fun Map<String, TetherClient>.addClient(
      key: String,
      client: TetherClient,
  ): Map<String, TetherClient> {
    return this.toMutableMap().apply { put(key, client) }
  }

  @CheckResult
  private fun Map<String, TetherClient>.removeClient(key: String): Map<String, TetherClient> {
    return this.toMutableMap().apply { remove(key) }
  }

  private fun handleClientUpdate(
      client: TetherClient,
      onClientUpdated: (TetherClient) -> TetherClient,
  ) {
    val key = client.key()
    allowedClients.update { clients ->
      val existing = clients[key]
      if (existing != null) {
        return@update clients.addClient(key, onClientUpdated(existing))
      } else {
        return@update clients
      }
    }
  }

  override suspend fun started() =
      withContext(context = dispatchers.default) {
        loadSavedNickNames()
        watchForOldClients()
        watchForDisconnectedClients()
        watchForNoClients()
      }

  override fun block(client: TetherClient) {
    blockedClients.update { blocked ->
      val key = client.key()
      val existing = blocked[key]

      if (existing == null) {
        return@update blocked.addClient(key, client)
      } else {
        return@update blocked
      }
    }
  }

  override fun unblock(client: TetherClient) {
    blockedClients.update { it.removeClient(client.key()) }
  }

  override fun isBlocked(client: TetherClient): Boolean {
    return checkBlocked(client)
  }

  override fun seen(client: TetherClient) {
    handleClientUpdate(client) { markLastSeenNow(it) }
  }

  override fun reportTransfer(
      client: TetherClient,
      report: ByteTransferReport,
  ) {
    handleClientUpdate(client) { updateTransferReport(it, report) }
  }

  override suspend fun updateNickName(client: TetherClient, nickName: String) =
      withContext(context = dispatchers.default) {
        val key = client.key()
        val trimmed = nickName.trim()

        // Remember the nickname even if the client is not currently connected.
        // A blank nickname clears only this client's customization.
        savedNickNames.update { saved ->
          if (trimmed.isBlank()) saved - key else saved + (key to trimmed)
        }
        handleClientUpdate(client) { editNickName(it, trimmed) }

        try {
          nickNamePreferences.saveNickName(key, trimmed)
        } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
          e.ifNotCancellation { Timber.e(e) { "Failed to persist nickname for $key" } }
        }
      }

  override suspend fun updateTransferLimit(client: TetherClient, limit: TransferAmount?) =
      withContext(context = dispatchers.default) {
        handleClientUpdate(client) { editTransferLimit(it, limit) }
      }

  override suspend fun updateBandwidthLimit(client: TetherClient, limit: TransferAmount?) =
      withContext(context = dispatchers.default) {
        handleClientUpdate(client) { editBandwidthLimit(it, limit) }
      }

  @VisibleForTesting
  internal suspend fun loadSavedNickNames() {
    try {
      val loaded = nickNamePreferences.listNickNames()
      // Never drop an edit made in-memory before the disk load finished
      savedNickNames.update { loaded + it }
    } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
      e.ifNotCancellation { Timber.e(e) { "Failed to load saved nicknames" } }
    }
  }

  override fun clear() {
    Timber.d { "Clear client tracker" }

    allowedClients.value = emptyMap()
    blockedClients.value = emptyMap()
    reachableAt.clear()
    probeFailures.clear()
    jobs.forEach { it.cancel() }
    jobs.clear()
  }

  override fun listenForClients(): Flow<Collection<TetherClient>> {
    return allowedClients.map { it.values }
  }

  override fun listenForBlocked(): Flow<Collection<TetherClient>> {
    return blockedClients.map { it.values }
  }

  override fun ensure(hostNameOrIp: String): TetherClient {
    return allowedClients
        .updateAndGet { clients ->
          // Don't allow duplicates in the client list
          // ConnectionScreen crashes on initial state switch from empty to >0 clients
          // because a Key is double-added briefly
          if (clients.containsKey(hostNameOrIp)) {
            return@updateAndGet clients
          }

          val client =
              TetherClient.create(
                  hostNameOrIp = hostNameOrIp,
                  clock = clock,
                  nickName = savedNickNames.value[hostNameOrIp].orEmpty(),
              )

          return@updateAndGet clients.addClient(hostNameOrIp, client).also {
            onNewClientSeen(client)
          }
        }
        .getValue(hostNameOrIp)
        .requireNotNull { "Unable to ensure TetherClient exists: $hostNameOrIp" }
  }

  companion object {
    /** Purge old or inactive clients after 5 minutes of no activity */
    private val OLD_CLIENT_TIMER_PERIOD = 5.minutes

    /** Shut down the hotspot after 10 minutes of absolutely zero client activity */
    private val NO_CLIENTS_TIMER_PERIOD = 10.minutes

    /** How often we verify that listed clients are still on the network */
    private val PRESENCE_CHECK_PERIOD = 8.seconds

    /** How often we verify while some client failed its last check and may have just left */
    private val PRESENCE_RECHECK_PERIOD = 2.seconds

    /** A client must fail this many checks in a row before it is removed from the list */
    private const val PRESENCE_MAX_FAILURES = 3

    /** A client that used the proxy this recently is clearly connected, whatever the probe says */
    private val PRESENCE_TRAFFIC_GRACE = 20.seconds
  }
}
