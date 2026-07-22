package io.github.zapretkvn.android.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val scopeMode: AppScopeMode = AppScopeMode.Include,
    val initialized: Boolean = false,
    val catalogLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val needsAppSelection: Boolean
        get() = initialized && allowedPackages.isEmpty()

    val missingPackages: Set<String>
        get() = if (catalogLoaded) {
            allowedPackages - apps.asSequence().map(InstalledApp::packageName).toSet()
        } else {
            emptySet()
        }
}

class AppsViewModel(
    private val selectionStore: AppSelectionStore,
    private val appCatalog: AppCatalog,
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val catalogLoaded = MutableStateFlow(false)
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    val state = combine(
        apps,
        selectionStore.selection,
        catalogLoaded,
        loading,
        error,
    ) { installedApps, selection, isCatalogLoaded, isLoading, loadError ->
        AppsUiState(
            apps = installedApps,
            allowedPackages = selection.allowedPackages,
            scopeMode = selection.mode,
            initialized = selection.initialized,
            catalogLoaded = isCatalogLoaded,
            loading = isLoading,
            error = loadError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppsUiState(),
    )

    fun refresh() {
        if (loading.value) return
        loading.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val installedApps = appCatalog.load()
                apps.value = installedApps
                catalogLoaded.value = true
                selectionStore.initializeIfNeeded(
                    installedApps
                        .asSequence()
                        .filter(InstalledApp::enabled)
                        .filter { it.suggestion != null }
                        .map(InstalledApp::packageName)
                        .toSet(),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error.value = failure.message ?: "Не удалось прочитать список приложений."
            } finally {
                loading.value = false
            }
        }
    }

    fun setAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch {
            selectionStore.setAllowed(packageName, allowed)
        }
    }

    fun setScopeMode(mode: AppScopeMode) {
        viewModelScope.launch { selectionStore.setMode(mode) }
    }

    fun removeMissingPackages() {
        val available = apps.value.asSequence().map(InstalledApp::packageName).toSet()
        viewModelScope.launch {
            selectionStore.replaceAllowlist(state.value.allowedPackages.intersect(available))
        }
    }

    class Factory(
        private val selectionStore: AppSelectionStore,
        private val appCatalog: AppCatalog,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppsViewModel(selectionStore, appCatalog) as T
    }
}
