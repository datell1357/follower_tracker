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
import dev.datell.followertracker.widget.WidgetUpdates
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
    private var widgetRefresh: Job? = null
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
    fun refreshWidgets(): Job {
        widgetRefresh?.takeUnless { it.isCompleted }?.let { return it }
        return viewModelScope.launch {
            try { TrackerWidget().updateAll(getApplication()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(message = "위젯 새로고침을 요청하지 못했어요. 앱을 다시 열어 확인해주세요.") } }
        }.also { widgetRefresh = it }
    }
    fun refresh(key: String? = null) = action {
        val accounts = graph.repository.accounts().filter { key == null || it.key == key }
        for (account in accounts) graph.coordinator.refresh(account.key)
        reload()
    }
    fun relationships() = action {
        state.value.selectedKey?.let { graph.coordinator.relationships(it) }
        reload()
    }
    fun connect(provider: Provider, payload: String?, userAgent: String,
        facebookPage: Boolean = false, expectedPageKey: String? = null, completed: () -> Unit): Job = action {
        graph.collector.releaseProfileResources(provider)
        withContext(Dispatchers.IO) {
            graph.sessions.save(provider, SessionMetadata(userAgent, graph.sessions.identity(provider), System.currentTimeMillis()))
        }
        val accounts = graph.repository.accounts()
        val pageId = if (facebookPage && payload != null)
            (ResponseParser.objectBody(payload)["stableId"] as? kotlinx.serialization.json.JsonPrimitive)?.content else null
        val expected = if (facebookPage) accounts.firstOrNull { it.provider == provider && it.accountType == AccountType.PAGE &&
            (expectedPageKey?.let { key -> it.key == key } ?: (it.stableId == pageId)) }
        else accounts.firstOrNull { it.provider == provider && it.accountType == AccountType.PROFILE }
        if (facebookPage && payload == null) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val observation = if (payload == null) graph.collector.native(provider, expected)
        else {
            val captured = graph.collector.captured(provider, payload, expected, facebookPage)
            if (expectedPageKey != null && captured.first.key != expectedPageKey) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            withContext(Dispatchers.IO) { graph.sessions.save(provider, SessionMetadata(userAgent,
                captured.first.sessionOwnerId ?: captured.first.stableId, System.currentTimeMillis())) }
            captured
        }
        currentCoroutineContext().ensureActive()
        graph.repository.saveObservation(observation.first, observation.second)
        completed()
        if (provider == Provider.INSTAGRAM && observation.first.status != SyncStatus.FOREGROUND_ONLY)
            SyncScheduler.initialLists(getApplication(), observation.first.key)
        WidgetUpdates.request(getApplication())
        reload()
    }
    fun disconnect(key: String) = action {
        val account = graph.repository.account(key) ?: return@action
        graph.collector.releaseProfileResources(account.provider)
        WorkManager.getInstance(getApplication()).cancelUniqueWork("initial-list-$key")
        graph.repository.disconnect(key)
        if (graph.repository.accounts().none { it.provider == account.provider })
            withContext(Dispatchers.IO) { graph.sessions.disconnect(account.provider) }
        WidgetUpdates.request(getApplication())
        reload()
    }
    private fun action(block: suspend () -> Unit): Job {
        operation?.takeUnless { it.isCompleted }?.let { return it }
        return viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CollectionFailure) { state.update { it.copy(message = connectionFailureMessage(failure)) } }
            catch (_: Exception) { state.update { it.copy(message = "작업을 마치지 못했어요. 기존 기록을 유지했어요.") } }
            finally { state.update { it.copy(busy = false) } }
        }.also { operation = it }
    }
}
