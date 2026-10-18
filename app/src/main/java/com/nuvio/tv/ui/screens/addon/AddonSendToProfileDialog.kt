package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

// Fork: pick the profiles an installed addon is sent to. Same look as CopyProfileSettingsDialog.
@Composable
internal fun AddonSendToProfileDialog(
    state: AddonSendDialogState,
    onToggle: (profileId: Int) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val firstProfileFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val firstAvailableId = state.targets.firstOrNull { it.id !in state.alreadyInstalled }?.id

    LaunchedEffect(state.isChecking) {
        repeat(2) { withFrameNanos { } }
        runCatching {
            if (!state.isChecking && firstAvailableId != null) {
                firstProfileFocusRequester.requestFocus()
            } else {
                cancelFocusRequester.requestFocus()
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.addon_send_title, state.addonName),
        subtitle = stringResource(R.string.addon_send_subtitle),
        width = 560.dp
    ) {
        if (state.isChecking) {
            Text(
                text = stringResource(R.string.addon_send_checking),
                color = NuvioTheme.colors.TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                state.targets.forEach { profile ->
                    val alreadyInstalled = profile.id in state.alreadyInstalled
                    TargetProfileButton(
                        text = if (alreadyInstalled) {
                            stringResource(R.string.addon_send_already_has, profile.label())
                        } else {
                            profile.label()
                        },
                        checked = alreadyInstalled || profile.id in state.selected,
                        enabled = !alreadyInstalled && !state.isSending,
                        modifier = if (profile.id == firstAvailableId) {
                            Modifier.focusRequester(firstProfileFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = { onToggle(profile.id) }
                    )
                }
            }
        }
        state.failedProfileNames?.let { names ->
            Text(
                text = stringResource(R.string.addon_send_failed, names),
                color = NuvioTheme.colors.Error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.components.dialog.actionSpacing)
        ) {
            DialogActionButton(
                text = stringResource(R.string.profile_cancel),
                primary = false,
                enabled = !state.isSending,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester),
                onClick = onDismiss
            )
            DialogActionButton(
                text = if (state.isSending) {
                    stringResource(R.string.addon_send_sending)
                } else {
                    stringResource(R.string.addon_send_action)
                },
                primary = true,
                enabled = !state.isChecking && !state.isSending && state.selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = onSend
            )
        }
    }
}

@Composable
private fun TargetProfileButton(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = if (primary) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (primary) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.FocusContent
        )
    ) {
        Text(
            text = text,
            fontWeight = if (primary) FontWeight.SemiBold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UserProfile.label(): String {
    return if (isPrimary) {
        stringResource(R.string.profile_copy_settings_primary_source, name)
    } else {
        name
    }
}
