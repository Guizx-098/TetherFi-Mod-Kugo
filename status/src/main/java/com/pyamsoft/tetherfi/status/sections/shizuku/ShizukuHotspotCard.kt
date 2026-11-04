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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.StatusViewState
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * EXPERIMENTAL
 *
 * Starts the built-in Android hotspot through Shizuku, so the network name does not need the
 * "DIRECT-" prefix. It does not use the proxy at all.
 */
@Composable
internal fun ShizukuHotspotCard(
    modifier: Modifier = Modifier,
    state: StatusViewState,
) {
  val ssid by state.ssid.collectAsStateWithLifecycle()
  val password by state.password.collectAsStateWithLifecycle()

  val scope = rememberCoroutineScope()
  var shizuku by remember { mutableStateOf(ShizukuHotspot.state()) }
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<ShizukuCommandResult?>(null) }

  DisposableEffect(Unit) {
    val onPermission =
        Shizuku.OnRequestPermissionResultListener { _, _ -> shizuku = ShizukuHotspot.state() }
    val onBinder = Shizuku.OnBinderReceivedListener { shizuku = ShizukuHotspot.state() }
    val onDead = Shizuku.OnBinderDeadListener { shizuku = ShizukuHotspot.state() }

    try {
      Shizuku.addRequestPermissionResultListener(onPermission)
      Shizuku.addBinderReceivedListener(onBinder)
      Shizuku.addBinderDeadListener(onDead)
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
      // Shizuku is not available, the card will say so
    }

    onDispose {
      try {
        Shizuku.removeRequestPermissionResultListener(onPermission)
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
      } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        // Nothing to clean up
      }
    }
  }

  val passwordTooShort = password.length < MIN_PASSWORD_LENGTH
  val mode = remember(shizuku) { if (shizuku == ShizukuState.READY) ShizukuHotspot.modeName() else null }

  Card(
      modifier = modifier,
      border =
          BorderStroke(
              width = 2.dp,
              color = MaterialTheme.colorScheme.primaryContainer,
          ),
      shape = MaterialTheme.shapes.large,
  ) {
    Column(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.keylines.baseline),
    ) {
      Text(
          text = stringResource(R.string.shizuku_title),
          style =
              MaterialTheme.typography.titleMedium.copy(
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.W700,
              ),
      )
      Text(
          text = stringResource(R.string.shizuku_description),
          style = MaterialTheme.typography.bodyMedium,
      )

      Text(
          text =
              when (shizuku) {
                ShizukuState.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
                ShizukuState.NEEDS_PERMISSION -> stringResource(R.string.shizuku_needs_permission)
                ShizukuState.READY ->
                    if (mode != null) stringResource(R.string.shizuku_ready_mode, mode)
                    else stringResource(R.string.shizuku_ready)
              },
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W700),
      )

      when (shizuku) {
        ShizukuState.NOT_RUNNING -> {
          OutlinedButton(
              onClick = { shizuku = ShizukuHotspot.state() },
          ) {
            Text(text = stringResource(R.string.shizuku_check_again))
          }
        }
        ShizukuState.NEEDS_PERMISSION -> {
          Button(
              onClick = { ShizukuHotspot.requestPermission() },
          ) {
            Text(text = stringResource(R.string.shizuku_request_permission))
          }
        }
        ShizukuState.READY -> {
          if (passwordTooShort) {
            Text(
                text = stringResource(R.string.shizuku_password_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
          }

          Row(
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.keylines.baseline),
          ) {
            Button(
                enabled = !busy && !passwordTooShort && ssid.isNotBlank(),
                onClick = {
                  busy = true
                  scope.launch {
                    result = ShizukuHotspot.startHotspot(ssid = ssid, password = password)
                    busy = false
                  }
                },
            ) {
              Text(text = stringResource(R.string.shizuku_start))
            }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                  busy = true
                  scope.launch {
                    result = ShizukuHotspot.stopHotspot()
                    busy = false
                  }
                },
            ) {
              Text(text = stringResource(R.string.shizuku_stop))
            }
          }

          OutlinedButton(
              enabled = !busy,
              onClick = {
                busy = true
                scope.launch {
                  result = ShizukuHotspot.diagnose()
                  busy = false
                }
              },
          ) {
            Text(text = stringResource(R.string.shizuku_diagnose))
          }
        }
      }

      result?.also { res ->
        val code = res.exitCode
        Text(
            text =
                if (code == null) {
                  stringResource(R.string.shizuku_result_failed, res.output)
                } else {
                  stringResource(
                      R.string.shizuku_result_code,
                      code,
                      res.output.ifBlank { stringResource(R.string.shizuku_result_empty) },
                  )
                },
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
        )
      }
    }
  }
}

private const val MIN_PASSWORD_LENGTH = 8
