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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.R
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data class UpToDate(val version: String) : UpdateState

  data class Available(val info: UpdateInfo) : UpdateState

  data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState

  data class Ready(val info: UpdateInfo, val file: File) : UpdateState

  data class Failed(val message: String) : UpdateState
}

@Stable
private class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

  var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    private set

  var dialogOpen by mutableStateOf(false)
    private set

  var needsPermission by mutableStateOf(false)
    private set

  var current: String = UpdateChecker.installedVersion(context)
    private set

  private var job: Job? = null

  /** @param silent when TRUE nothing is shown unless a newer version exists */
  fun check(silent: Boolean) {
    if (state is UpdateState.Checking || state is UpdateState.Downloading) {
      return
    }

    state = UpdateState.Checking
    job =
        scope.launch {
          try {
            val info = UpdateChecker.fetchLatest()
            current = UpdateChecker.installedVersion(context)

            if (UpdateChecker.isNewer(remote = info.versionName, local = current)) {
              state = UpdateState.Available(info)
              dialogOpen = true
            } else {
              state = UpdateState.UpToDate(current)
            }
          } catch (e: CancellationException) {
            throw e
          } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            state = if (silent) UpdateState.Idle else UpdateState.Failed(e.message.orEmpty())
          }
        }
  }

  fun download(info: UpdateInfo) {
    needsPermission = false
    state = UpdateState.Downloading(info = info, percent = 0)
    job =
        scope.launch {
          try {
            val file =
                UpdateChecker.download(context, info) { percent ->
                  state = UpdateState.Downloading(info = info, percent = percent)
                }
            state = UpdateState.Ready(info = info, file = file)
          } catch (e: CancellationException) {
            state = UpdateState.Available(info)
            throw e
          } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            state = UpdateState.Failed(e.message.orEmpty())
            dialogOpen = true
          }
        }
  }

  fun cancelDownload() {
    job?.cancel()
  }

  fun install(file: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
      needsPermission = true
      return
    }

    needsPermission = false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  fun openPermissionSettings() {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  fun dismiss() {
    dialogOpen = false
  }

  fun open() {
    dialogOpen = true
  }
}

@Composable
private fun rememberUpdateController(): UpdateController {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  return remember(context, scope) { UpdateController(context.applicationContext, scope) }
}

/** Checks for a newer version when the app opens, and asks the person what to do. */
@Composable
fun UpdateHost() {
  val controller = rememberUpdateController()

  LaunchedEffect(controller) { controller.check(silent = true) }

  UpdateDialog(controller = controller)
}

/** A card with a button to check for updates by hand. */
@Composable
fun UpdateSettingsCard(
    modifier: Modifier = Modifier,
) {
  val controller = rememberUpdateController()
  val state = controller.state

  Card(
      modifier = modifier,
      border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primaryContainer),
      shape = MaterialTheme.shapes.large,
  ) {
    Column(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.keylines.baseline),
    ) {
      Text(
          text = stringResource(R.string.update_section),
          style =
              MaterialTheme.typography.titleMedium.copy(
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.W700,
              ),
      )

      Text(
          text =
              when (state) {
                is UpdateState.Checking -> stringResource(R.string.update_checking)
                is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, state.version)
                is UpdateState.Failed -> stringResource(R.string.update_error, state.message)
                is UpdateState.Available ->
                    stringResource(R.string.update_message, state.info.versionName, controller.current)
                else -> stringResource(R.string.update_current, controller.current)
              },
          style = MaterialTheme.typography.bodyMedium,
      )

      OutlinedButton(
          enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
          onClick = {
            if (state is UpdateState.Available || state is UpdateState.Ready) {
              controller.open()
            } else {
              controller.check(silent = false)
            }
          },
      ) {
        Text(text = stringResource(R.string.update_check))
      }
    }
  }

  UpdateDialog(controller = controller)
}

@Composable
private fun UpdateDialog(controller: UpdateController) {
  if (!controller.dialogOpen) {
    return
  }

  val state = controller.state

  AlertDialog(
      onDismissRequest = {
        if (state !is UpdateState.Downloading) {
          controller.dismiss()
        }
      },
      title = { Text(text = stringResource(R.string.update_title)) },
      text = {
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.keylines.baseline),
        ) {
          when (state) {
            is UpdateState.Available -> {
              Text(
                  text =
                      stringResource(
                          R.string.update_message,
                          state.info.versionName,
                          controller.current,
                      ),
              )
              NotesText(notes = state.info.notes)
            }
            is UpdateState.Downloading -> {
              Text(text = stringResource(R.string.update_downloading, state.percent))
              LinearProgressIndicator(
                  progress = { state.percent / 100f },
                  modifier = Modifier.fillMaxWidth(),
              )
            }
            is UpdateState.Ready -> {
              Text(text = stringResource(R.string.update_ready))
              if (controller.needsPermission) {
                Text(
                    text = stringResource(R.string.update_permission),
                    color = MaterialTheme.colorScheme.error,
                )
              }
            }
            is UpdateState.Failed -> {
              Text(text = stringResource(R.string.update_error, state.message))
            }
            else -> {
              Text(text = stringResource(R.string.update_checking))
            }
          }
        }
      },
      confirmButton = {
        when (state) {
          is UpdateState.Available ->
              Button(onClick = { controller.download(state.info) }) {
                Text(text = stringResource(R.string.update_download))
              }
          is UpdateState.Ready ->
              if (controller.needsPermission) {
                Button(onClick = { controller.openPermissionSettings() }) {
                  Text(text = stringResource(R.string.update_allow))
                }
              } else {
                Button(onClick = { controller.install(state.file) }) {
                  Text(text = stringResource(R.string.update_install))
                }
              }
          is UpdateState.Failed ->
              TextButton(onClick = { controller.check(silent = false) }) {
                Text(text = stringResource(R.string.update_retry))
              }
          else -> {}
        }
      },
      dismissButton = {
        when (state) {
          is UpdateState.Downloading ->
              TextButton(onClick = { controller.cancelDownload() }) {
                Text(text = stringResource(R.string.update_cancel))
              }
          is UpdateState.Ready ->
              if (controller.needsPermission) {
                TextButton(onClick = { controller.install(state.file) }) {
                  Text(text = stringResource(R.string.update_install))
                }
              } else {
                TextButton(onClick = { controller.dismiss() }) {
                  Text(text = stringResource(R.string.update_later))
                }
              }
          else ->
              TextButton(onClick = { controller.dismiss() }) {
                Text(
                    text =
                        stringResource(
                            if (state is UpdateState.Failed) R.string.update_close
                            else R.string.update_later,
                        ),
                )
              }
        }
      },
  )
}

@Composable
private fun NotesText(notes: String) {
  val trimmed = notes.trim()
  if (trimmed.isEmpty()) {
    return
  }

  Text(
      text = stringResource(R.string.update_notes_title),
      fontWeight = FontWeight.W700,
      style = MaterialTheme.typography.bodyMedium,
  )
  Text(
      modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
      text = trimmed.take(MAX_NOTES_LENGTH),
      style = MaterialTheme.typography.bodySmall,
  )
}

private const val MAX_NOTES_LENGTH = 900
