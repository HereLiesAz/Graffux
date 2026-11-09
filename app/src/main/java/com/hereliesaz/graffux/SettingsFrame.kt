package com.hereliesaz.graffux

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * The Settings page's frame: a full-bleed surface announced as a pane titled "Settings", its content
 * kept clear of the status and navigation bars, a display cutout and the keyboard (the reports token
 * field takes text) while the surface still fills behind them, a heading and a close button.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsFrame(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val title = stringResource(R.string.settings_title)
    Surface(
        modifier = modifier.semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() }.testTag("settings.title"),
                )
                IconButton(onClick = onClose, modifier = Modifier.testTag("settings.close")) {
                    Icon(
                        painterResource(GraffuxIcons.Close),
                        contentDescription = stringResource(R.string.settings_close),
                    )
                }
            }
            content()
        }
    }
}

/** A section's title in Settings, announced as a heading so a screen reader can jump between them. */
@Suppress("FunctionNaming")
@Composable
internal fun SectionHeader(title: String, top: Dp) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = top, bottom = 4.dp).semantics { heading() },
    )
}

