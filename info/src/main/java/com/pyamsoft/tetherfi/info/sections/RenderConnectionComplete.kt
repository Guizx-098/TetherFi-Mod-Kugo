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

package com.pyamsoft.tetherfi.info.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.uri.rememberUriHandler
import com.pyamsoft.tetherfi.core.GITHUB_URL
import com.pyamsoft.tetherfi.info.R

private enum class ConnectionCompleteContentTypes {
  SHARING,
  DONE,
  SLOW,
  FULL,
}

internal fun LazyListScope.renderConnectionComplete(
    itemModifier: Modifier = Modifier,
    appName: String,
    onShowSlowSpeedHelp: () -> Unit,
) {
  item(
      contentType = ConnectionCompleteContentTypes.SHARING,
  ) {
    ThisInstruction(
        modifier = itemModifier,
    ) {
      Text(
          text = stringResource(R.string.sharing_complete),
          style = MaterialTheme.typography.bodyLarge,
      )
    }
  }

  item(
      contentType = ConnectionCompleteContentTypes.DONE,
  ) {
    OtherInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      Text(
          text = stringResource(R.string.sharing_caveat, appName),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
  }
}


@Composable
@Preview(showBackground = true)
private fun PreviewConnectionComplete() {
  LazyColumn {
    renderConnectionComplete(
        appName = "TEST",
        onShowSlowSpeedHelp = {},
    )
  }
}
