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
package com.pyamsoft.tetherfi.ui.trouble

import android.content.Context
import androidx.annotation.CheckResult
import androidx.annotation.StringRes
import com.pyamsoft.tetherfi.server.broadcast.rndis.RNDISInitializeException
import com.pyamsoft.tetherfi.ui.R
import java.net.BindException

/** What went wrong when the hotspot could not start, in words a person can act on. */
internal data class StartErrorDescription(
    /** The specific cause, as plain language */
    val summary: String,
    /** The raw message from the system, if it adds something to the summary */
    val detail: String?,
)

/**
 * Turns the error into one specific cause.
 *
 * The raw message is kept as the detail whenever we recognize the error, so that nothing is hidden.
 * If we do not recognize the error, the raw message is the summary.
 */
@CheckResult
internal fun describeStartError(
    context: Context,
    error: Throwable,
    fallback: String,
): StartErrorDescription {
  // USB tethering errors already explain themselves
  if (error is RNDISInitializeException) {
    return StartErrorDescription(summary = error.candidateMessage, detail = null)
  }

  val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
  val text = chain.joinToString(separator = " | ") { it.message.orEmpty() }
  val raw = error.message.orEmpty().ifBlank { null }

  @StringRes
  val cause: Int? =
      when {
        chain.any { it is BindException } ||
            text.contains("Address already in use", ignoreCase = true) ||
            text.contains("EADDRINUSE", ignoreCase = true) ||
            text.contains("bind failed", ignoreCase = true) -> R.string.trouble_cause_port
        text.contains("Broadcast Error: Busy") -> R.string.trouble_cause_p2p_busy
        text.contains("P2P Unsupported") -> R.string.trouble_cause_p2p_unsupported
        text.contains("No Service Requests") -> R.string.trouble_cause_p2p_no_service
        text.contains("Broadcast Error: Error") -> R.string.trouble_cause_p2p_error
        text.contains("Unable to create Wi-Fi Direct Channel") -> R.string.trouble_cause_channel
        text.contains("Missing required Permissions") -> R.string.trouble_cause_permission
        text.contains("did not return") -> R.string.trouble_cause_no_info
        else -> null
      }

  if (cause != null) {
    return StartErrorDescription(summary = context.getString(cause), detail = raw)
  }

  return StartErrorDescription(summary = raw ?: fallback, detail = null)
}

private const val MAX_CAUSE_DEPTH = 10
