package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.AddonProfileTransferService
import com.nuvio.tv.core.sync.AddonTransferOutcome
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddonSendDialogState(
    val addonUrl: String,
    val addonName: String,
    val userSetName: String?,
    val targets: List<UserProfile>,
    val isChecking: Boolean = true,
    val alreadyInstalled: Set<Int> = emptySet(),
    val selected: Set<Int> = emptySet(),
    val isSending: Boolean = false,
    val failedProfileNames: String? = null
)

/** Formatted by the screen, so it follows the app language rather than the system one. */
data class AddonSendMessage(
    val parts: List<Pair<Int, String>>,
    val isError: Boolean
)

// Fork: "Send to another profile" on an installed addon (see AddonProfileTransferService).
@HiltViewModel
class AddonProfileTransferViewModel @Inject constructor(
    private val transferService: AddonProfileTransferService
) : ViewModel() {

    val targets: StateFlow<List<UserProfile>> = transferService.eligibleTargets
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dialog = MutableStateFlow<AddonSendDialogState?>(null)
    val dialog: StateFlow<AddonSendDialogState?> = _dialog.asStateFlow()

    private val _message = MutableStateFlow<AddonSendMessage?>(null)
    val message: StateFlow<AddonSendMessage?> = _message.asStateFlow()

    private var checkJob: Job? = null
    private var messageJob: Job? = null

    fun open(addon: Addon) {
        val profiles = targets.value
        if (profiles.isEmpty()) return
        val url = addon.baseUrl
        _dialog.value = AddonSendDialogState(
            addonUrl = url,
            addonName = addon.displayName,
            userSetName = addon.displayName.takeIf { it != addon.name },
            targets = profiles
        )
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            // If the check fails, every profile stays selectable; sending checks again anyway.
            val alreadyInstalled = transferService
                .profilesWithAddon(url, profiles.map { it.id })
                .getOrDefault(emptySet())
            val available = profiles.map { it.id }.filterNot { it in alreadyInstalled }
            _dialog.update { state ->
                if (state?.addonUrl != url) return@update state
                state.copy(
                    isChecking = false,
                    alreadyInstalled = alreadyInstalled,
                    selected = if (available.size == 1) available.toSet() else emptySet()
                )
            }
        }
    }

    fun toggle(profileId: Int) {
        _dialog.update { state ->
            if (state == null || state.isChecking || state.isSending || profileId in state.alreadyInstalled) {
                return@update state
            }
            val selected = if (profileId in state.selected) {
                state.selected - profileId
            } else {
                state.selected + profileId
            }
            state.copy(selected = selected, failedProfileNames = null)
        }
    }

    fun dismiss() {
        if (_dialog.value?.isSending == true) return
        checkJob?.cancel()
        _dialog.value = null
    }

    fun send() {
        val state = _dialog.value ?: return
        if (state.isChecking || state.isSending || state.selected.isEmpty()) return
        _dialog.value = state.copy(isSending = true, failedProfileNames = null)
        viewModelScope.launch {
            val added = mutableListOf<UserProfile>()
            val alreadyHad = mutableListOf<UserProfile>()
            val failed = mutableListOf<UserProfile>()
            state.targets.filter { it.id in state.selected }.forEach { profile ->
                transferService.sendAddon(state.addonUrl, state.userSetName, profile.id)
                    .onSuccess { outcome ->
                        if (outcome == AddonTransferOutcome.ADDED) added += profile else alreadyHad += profile
                    }
                    .onFailure { failed += profile }
            }

            if (failed.isEmpty()) {
                _dialog.value = null
                val parts = buildList {
                    if (added.isNotEmpty()) add(R.string.addon_send_done to added.names())
                    if (alreadyHad.isNotEmpty()) add(R.string.addon_send_already_had to alreadyHad.names())
                }
                showMessage(AddonSendMessage(parts, isError = false))
            } else {
                // Keep the dialog open on the profiles that failed, so sending again only retries those.
                val done = (added + alreadyHad).map { it.id }.toSet()
                _dialog.update { current ->
                    current?.copy(
                        isSending = false,
                        alreadyInstalled = current.alreadyInstalled + done,
                        selected = current.selected - done,
                        failedProfileNames = failed.names()
                    )
                }
            }
        }
    }

    private fun showMessage(message: AddonSendMessage) {
        messageJob?.cancel()
        _message.value = message
        messageJob = viewModelScope.launch {
            delay(3200)
            _message.value = null
        }
    }

    private fun List<UserProfile>.names(): String = joinToString(", ") { it.name }
}
