package dev.datell.followertracker.ui

import android.app.Application
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.sync.*
import dev.datell.followertracker.widget.TrackerWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TrackerState(val accounts: List<AccountOverview> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val message: String? = null, val selectedKey: String? = null,
    val report: RelationshipReport? = null, val storageError: Boolean = false,
    val relationshipChanges: List<RelationshipChange> = emptyList())

class TrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    val state = MutableStateFlow(TrackerState())
    private var reloadVersion = 0L
    private var operation: Job? = null
    init {
        viewModelScope.launch {
            try { graph.repository.accounts.collect { reload() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(loading = false, storageError = true, message = "저장된 기록을 읽지 못했어요. 앱을 다시 열어 확인해주세요.") } }
        }
    }
    private suspend fun reload() {
        val version = ++reloadVersion
        try {
            val rows = graph.repository.overviews()
            val key = state.value.selectedKey?.takeIf { key -> rows.any { it.account.key == key } } ?: rows.firstOrNull()?.account?.key
            val report = key?.let { graph.repository.report(it) }
            val changes = key?.let { graph.repository.relationshipChanges(it) }.orEmpty()
            if (version == reloadVersion) state.update { it.copy(accounts = rows, selectedKey = key, report = report, relationshipChanges = changes, loading = false, storageError = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (version == reloadVersion) state.update { it.copy(loading = false, storageError = true, message = "저장된 기록을 읽지 못했어요. 데이터를 보존했어요.") }
        }
    }
    fun select(key: String) { state.update { it.copy(selectedKey = key, report = null, relationshipChanges = emptyList()) }; viewModelScope.launch { reload() } }
    fun dismissMessage() = state.update { it.copy(message = null) }
    fun refresh(key: String? = null) = action {
        val accounts = graph.repository.accounts().filter { key == null || it.key == key }
        for (account in accounts) graph.coordinator.refresh(account.key)
        reload()
    }
    fun relationships() = action {
        state.value.selectedKey?.let { graph.coordinator.relationships(it) }
        reload()
    }
    fun connect(provider: Provider, payload: String?, userAgent: String, completed: () -> Unit): Job = action {
        withContext(Dispatchers.IO) {
            graph.sessions.save(provider, SessionMetadata(userAgent, graph.sessions.identity(provider), System.currentTimeMillis()))
        }
        val expected = graph.repository.accounts().firstOrNull { it.provider == provider }
        val observation = if (provider in setOf(Provider.INSTAGRAM, Provider.REDDIT)) graph.collector.native(provider, expected)
        else {
            val captured = graph.collector.captured(provider, payload ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED), expected)
            withContext(Dispatchers.IO) { graph.sessions.save(provider, SessionMetadata(userAgent, captured.first.stableId, System.currentTimeMillis())) }
            if (provider == Provider.TIKTOK) try { graph.collector.native(provider, captured.first) }
            catch (failure: CollectionFailure) { if (failure.status == SyncStatus.FORMAT_CHANGED) captured else throw failure }
            else captured
        }
        currentCoroutineContext().ensureActive()
        graph.repository.saveObservation(observation.first, observation.second)
        completed()
        if (provider == Provider.INSTAGRAM) SyncScheduler.initialLists(getApplication(), observation.first.key)
        TrackerWidget().updateAll(getApplication())
        reload()
    }
    fun disconnect(key: String) = action {
        val account = graph.repository.account(key) ?: return@action
        WorkManager.getInstance(getApplication()).cancelUniqueWork("initial-list-$key")
        graph.repository.disconnect(key)
        withContext(Dispatchers.IO) { graph.sessions.disconnect(account.provider) }
        TrackerWidget().updateAll(getApplication())
        reload()
    }
    private fun action(block: suspend () -> Unit): Job {
        operation?.takeUnless { it.isCompleted }?.let { return it }
        return viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CollectionFailure) { state.update { it.copy(message = failure.status.label + ". 공식 로그인 페이지에서 계정을 확인해주세요.") } }
            catch (_: Exception) { state.update { it.copy(message = "작업을 마치지 못했어요. 기존 기록을 유지했어요.") } }
            finally { state.update { it.copy(busy = false) } }
        }.also { operation = it }
    }
}
