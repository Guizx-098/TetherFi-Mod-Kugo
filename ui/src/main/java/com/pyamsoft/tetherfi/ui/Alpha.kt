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

package com.pyamsoft.tetherfi.ui

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults

private const val ALPHA_ENABLED = 1F
private const val SURFACE_ALPHA_DISABLED = 0.75F

@CheckResult
fun surfaceAlpha(isEnabled: Boolean): Float {
  return if (isEnabled) ALPHA_ENABLED else SURFACE_ALPHA_DISABLED
}

@CheckResult
fun textAlpha(isEnabled: Boolean): Float {
  return if (isEnabled) ALPHA_ENABLED else TypographyDefaults.ALPHA_DISABLED
}
