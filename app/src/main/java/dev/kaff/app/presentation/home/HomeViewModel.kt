package dev.kaff.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(val standbyOn: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<HomeUiState> = settings.settings
        .map { HomeUiState(standbyOn = it.standbyMode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
