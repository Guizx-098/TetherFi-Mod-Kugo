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

package com.pyamsoft.tetherfi.qr

import androidx.activity.ComponentActivity
import coil3.ImageLoader
import com.pyamsoft.pydroid.ui.inject.ComposableInjector
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.ui.qr.QRCodeViewModeler
import javax.inject.Inject

internal class QRCodeInjector(
    private val ssid: String,
    private val password: String,
) : ComposableInjector() {

  @JvmField @Inject internal var viewModel: QRCodeViewModeler? = null

  @JvmField @Inject internal var imageLoader: ImageLoader? = null

  override fun onInject(activity: ComponentActivity) {
    ObjectGraph.ActivityScope.retrieve(activity)
        .plusQR()
        .create(
            ssid = ssid,
            password = password,
        )
        .inject(this)
  }

  override fun onDispose() {
    viewModel = null
    imageLoader = null
  }
}
