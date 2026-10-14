package com.notebudget.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.feature.folders.FolderDraft
import com.notebudget.app.feature.folders.FolderEditor
import com.notebudget.app.feature.folders.rememberFolderDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(private val folders: FolderUseCases) : ViewModel() {
    private val _error = MutableStateFlow<DomainError?>(null)
    val error: StateFlow<DomainError?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Creates the first folder (D03). On success the folder list stops being empty and the
     * app switches to the main screen on its own.
     */
    fun create(draft: FolderDraft) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = folders.create(draft.name, draft.colorHex, draft.styleId, draft.icon)
            _error.value = (result as? DomainResult.Failure)?.error
            _busy.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}

/** First launch: nobody can create a note before a folder exists (§6.2, D03, D47). */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = appViewModel { OnboardingViewModel(it.folderUseCases) },
) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.default_folder_name)
    val draftState = rememberFolderDraft(FolderDraft(name = defaultName))
    val draft = draftState.value

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyLarge)
            FolderEditor(
                draft = draft,
                onDraftChange = {
                    if (error != null) viewModel.clearError()
                    draftState.value = it
                },
                error = error,
            )
            Button(
                onClick = { viewModel.create(draft) },
                enabled = draft.name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.folder_create_button))
            }
        }
    }
}
