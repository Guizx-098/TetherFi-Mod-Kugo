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

package com.pyamsoft.tetherfi.info

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.ui.inject.rememberComposableInjector
import com.pyamsoft.pydroid.ui.util.rememberNotNull
import com.pyamsoft.tetherfi.ui.ServerViewState

/** On mount hooks */
@Composable
private fun MountHooks(
    viewModel: InfoViewModeler,
) {
  SaveStateDisposableEffect(viewModel)
}

@Composable
fun InfoEntry(
    modifier: Modifier = Modifier,
    appName: String,
    lazyListState: LazyListState,
    serverViewState: ServerViewState,
    onShowQRCode: () -> Unit,
    onShowSlowSpeedHelp: () -> Unit,
) {
  val component = rememberComposableInjector { InfoInjector() }
  val viewModel = rememberNotNull(component.viewModel)

  MountHooks(
      viewModel = viewModel,
  )

  InfoScreen(
      modifier = modifier,
      state = viewModel,
      lazyListState = lazyListState,
      appName = appName,
      serverViewState = serverViewState,
      onShowQRCode = onShowQRCode,
      onShowSlowSpeedHelp = onShowSlowSpeedHelp,
      onTogglePasswordVisibility = { viewModel.handleTogglePasswordVisibility() },
      onToggleShowOptions = { viewModel.handleToggleOptions(it) },
  )
}
