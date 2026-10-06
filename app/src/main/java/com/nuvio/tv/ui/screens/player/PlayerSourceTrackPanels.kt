@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.screens.stream.StreamQualityBucket
import com.nuvio.tv.ui.screens.stream.label
import com.nuvio.tv.ui.screens.stream.streamLanguageLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

// Fork: UI of the audio panel's "Todos" scope and of the quality panel (see PlayerSourceTrackOptions.kt).

/** What the audio overlay needs to offer the languages of every source. */
internal class SourceAudioScope(
    val options: SourceTrackOptions,
    val isLoading: Boolean,
    val onLanguageSelected: (String) -> Unit,
)

@Composable
internal fun sourceLanguageLabel(key: String): String = streamLanguageLabel(
    key = key,
    portugueseLabel = stringResource(R.string.stream_filter_language_portuguese),
    unknownLabel = stringResource(R.string.stream_filter_language_unknown),
)

@Composable
private fun qualityLabel(bucket: StreamQualityBucket): String =
    bucket.label(stringResource(R.string.stream_filter_quality_other))

/** "Stream atual" / "Todos" chips; moving focus across them switches the scope. */
@Composable
internal fun SourceScopeChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        modifier = modifier.padding(bottom = NuvioTheme.spacing.sm),
    ) {
        labels.forEachIndexed { index, label ->
            SourceStatusFilterChip(
                name = label,
                isSelected = index == selectedIndex,
                onClick = { onSelected(index) },
                onFocusSelect = { onSelected(index) },
            )
        }
    }
}

/**
 * "Todos" scope of the audio panel: one row per language found in the sources. [focusRequester]
 * goes on the language playing (else the first one), the target of Left from the audio controls.
 */
@Composable
internal fun SourceLanguageOptionsContent(
    scope: SourceAudioScope,
    rightFocusRequester: FocusRequester?,
    focusRequester: FocusRequester? = null,
) {
    val languages = scope.options.languages
    if (languages.isEmpty()) {
        SourcePanelMessage(
            text = stringResource(
                if (scope.isLoading) R.string.player_source_tracks_loading else R.string.player_source_tracks_none
            )
        )
        return
    }
    val focusKey = (languages.firstOrNull { it.isCurrent } ?: languages.first()).key
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = Modifier
            .heightIn(max = 620.dp)
            .fillMaxWidth(),
    ) {
        items(items = languages, key = { it.key }) { option ->
            val count = pluralStringResource(R.plurals.player_source_tracks_sources, option.streamCount, option.streamCount)
            SourceOptionCard(
                title = sourceLanguageLabel(option.key),
                detail = option.bestQuality?.let {
                    stringResource(R.string.player_source_tracks_best_quality, qualityLabel(it), count)
                } ?: count,
                isCurrent = option.isCurrent,
                focusRequester = focusRequester?.takeIf { option.key == focusKey },
                rightFocusRequester = rightFocusRequester,
                onClick = { scope.onLanguageSelected(option.key) },
            )
        }
    }
}

/**
 * Quality panel. "Áudio atual" lists the qualities that keep the audio playing; "Todas" lists every
 * quality and, when the one picked has no source with that audio, asks which audio to switch to.
 */
@Composable
internal fun SourceQualityOverlay(
    visible: Boolean,
    options: SourceTrackOptions,
    isLoading: Boolean,
    onQualitySelected: (StreamQualityBucket, String?) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentLanguage = options.currentLanguage
    var showAll by remember(visible) { mutableStateOf(false) }
    var missingAudioBucket by remember(visible) { mutableStateOf<StreamQualityBucket?>(null) }
    val listFocusRequester = remember { FocusRequester() }
    val qualities = if (showAll || currentLanguage == null) options.allQualities else options.qualitiesWithCurrentAudio

    LaunchedEffect(visible, missingAudioBucket, qualities.isNotEmpty()) {
        if (!visible) return@LaunchedEffect
        delay(120)
        runCatching { listFocusRequester.requestFocus() }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = stringResource(R.string.player_source_tracks_quality_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
            )
            if (currentLanguage != null) {
                SourceScopeChips(
                    labels = listOf(
                        stringResource(R.string.player_source_tracks_scope_current_audio, sourceLanguageLabel(currentLanguage)),
                        stringResource(R.string.player_source_tracks_scope_all_qualities),
                    ),
                    selectedIndex = if (showAll) 1 else 0,
                    onSelected = { index ->
                        showAll = index == 1
                        missingAudioBucket = null
                    },
                )
            }

            val missing = missingAudioBucket
            when {
                missing != null -> {
                    val bucketLanguages = options.allQualities.firstOrNull { it.bucket == missing }?.languages.orEmpty()
                    SourcePanelMessage(
                        text = stringResource(
                            R.string.player_source_tracks_missing_audio,
                            qualityLabel(missing),
                            currentLanguage?.let { sourceLanguageLabel(it) }.orEmpty(),
                        )
                    )
                    Text(
                        text = stringResource(R.string.player_source_tracks_switch_audio_to),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bucketLanguages.forEachIndexed { index, language ->
                            SourceOptionCard(
                                title = sourceLanguageLabel(language),
                                detail = null,
                                isCurrent = false,
                                focusRequester = if (index == 0) listFocusRequester else null,
                                onClick = { onQualitySelected(missing, language) },
                            )
                        }
                        SourceOptionCard(
                            title = stringResource(R.string.player_source_tracks_cancel),
                            detail = null,
                            isCurrent = false,
                            focusRequester = if (bucketLanguages.isEmpty()) listFocusRequester else null,
                            onClick = { missingAudioBucket = null },
                        )
                    }
                }

                qualities.isEmpty() -> SourcePanelMessage(
                    text = stringResource(
                        if (isLoading) R.string.player_source_tracks_loading else R.string.player_source_tracks_none
                    )
                )

                else -> {
                    val focusIndex = qualities.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
                        modifier = Modifier
                            .heightIn(max = 620.dp)
                            .fillMaxWidth(),
                    ) {
                        items(items = qualities, key = { it.bucket.name }) { option ->
                            SourceOptionCard(
                                title = qualityLabel(option.bucket),
                                detail = pluralStringResource(
                                    R.plurals.player_source_tracks_sources,
                                    option.streamCount,
                                    option.streamCount,
                                ),
                                isCurrent = option.isCurrent,
                                focusRequester = if (qualities.indexOf(option) == focusIndex) listFocusRequester else null,
                                onClick = {
                                    val language = currentLanguage
                                    if (!onQualitySelected(option.bucket, language) && language != null) {
                                        missingAudioBucket = option.bucket
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePanelMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.md),
    )
}

/** Same look as the audio track cards; the option playing now is highlighted and checked. */
@Composable
private fun SourceOptionCard(
    title: String,
    detail: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (rightFocusRequester != null) Modifier.focusProperties { right = rightFocusRequester } else Modifier),
        colors = CardDefaults.colors(
            containerColor = if (isCurrent) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (isCurrent) NuvioTheme.colors.Secondary else Color.Transparent,
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md),
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md),
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        val primaryTextColor = if (isCurrent) NuvioTheme.colors.OnSecondary else Color.White
        val secondaryTextColor = if (isCurrent) NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.72f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = primaryTextColor)
                if (!detail.isNullOrBlank()) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall, color = secondaryTextColor)
                }
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.player_source_tracks_current),
                    tint = NuvioTheme.colors.OnSecondary,
                )
            }
        }
    }
}
