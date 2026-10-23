package dev.datell.followertracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.datell.followertracker.appGraph
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.AccountOverview
import dev.datell.followertracker.sync.*
import dev.datell.followertracker.widget.WidgetUpdates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TrackerState(val accounts: List<AccountOverview> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val message: String? = null, val selectedKey: String? = null,
    val report: RelationshipReport? = null, val storageError: Boolean = false,
    val relationshipChanges: List<RelationshipChange> = emptyList(),
    val facebookConnection: FacebookConnectionUi? = null)

data class FacebookConnectionUi(val pages: List<FacebookPageChoice> = emptyList(), val waiting: Boolean = true,
    val message: String? = null)

class TrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    val state = MutableStateFlow(TrackerState())
    private var reloadVersion = 0L
    private var operation: Job? = null
    private var widgetRefresh: Job? = null
    private var facebookPoll: Job? = null
    private var cleanup: Job? = null
    val managedServiceConfigured get() = graph.managedApi.configured
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
            try { WidgetUpdates.refreshNow(getApplication()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(message = "위젯 새로고침을 요청하지 못했어요. 앱을 다시 열어 확인해주세요.") } }
        }.also { widgetRefresh = it }
    }
    fun resumeRequestedTracking(): Job {
        RapidTracking.signalChange()
        return viewModelScope.launch {
            try { RapidTracking.recoverRequested(getApplication()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(message = "자동 추적을 복구하지 못했어요. 수집 설정에서 상태를 확인해주세요.") } }
        }
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
    fun connectYouTube(address: String, completed: () -> Unit): Job = action(::officialApiFailureMessage) {
        val expected = graph.repository.accounts().firstOrNull { it.provider == Provider.YOUTUBE }
        val (account, metric) = graph.managedApi.youtube(address, expected)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                graph.managedStore.bind(account)
                graph.officialApis.removeAccount(account)
            }
            graph.repository.saveObservation(account, metric, writeIf = graph.managedStore.guard())
        }
        finishOfficialConnection(completed)
    }
    fun startFacebookLogin(pageKey: String? = null, launchBrowser: (String) -> Unit): Job = action(::officialApiFailureMessage) {
        val expected = pageKey?.let { graph.repository.account(it) ?: throw CollectionFailure(SyncStatus.CHECK_REQUIRED) }
        val url = graph.managedApi.startFacebook(expected)
        currentCoroutineContext().ensureActive()
        state.update { it.copy(facebookConnection = FacebookConnectionUi()) }
        try { launchBrowser(url) }
        catch (_: Exception) {
            graph.managedStore.cancelLogin()
            state.update { it.copy(facebookConnection = null) }
            throw ServiceConnectionFailure("BROWSER_UNAVAILABLE")
        }
    }
    fun pauseFacebookConnection() { facebookPoll?.cancel(); facebookPoll = null }
    fun resumeFacebookConnection() {
        flushManagedCleanup()
        if (facebookPoll?.isActive == true) return
        val pending = try { graph.managedStore.pending() } catch (_: Exception) {
            state.update { it.copy(message = "저장된 연결 정보를 읽지 못했어요. 기존 기록은 유지했어요.") }; return
        } ?: return
        facebookPoll = viewModelScope.launch {
            state.update { it.copy(facebookConnection = FacebookConnectionUi()) }
            try {
                repeat(60) {
                    val result = graph.managedApi.facebookStatus(pending)
                    if (graph.managedStore.pending() != pending) return@launch
                    when (result.status) {
                        "selected" -> { selectFacebookPage(checkNotNull(result.selectedId)); return@launch }
                        "ready" -> {
                            state.update { it.copy(facebookConnection = FacebookConnectionUi(result.pages, false,
                                if (result.pages.isEmpty()) "선택할 페이지가 없어요. 로그인한 계정의 페이지 관리 권한과 허용한 페이지를 확인해주세요." else null)) }
                            return@launch
                        }
                        "cancelled", "failed" -> throw ServiceConnectionFailure(result.error ?: "LOGIN_CANCELLED")
                    }
                    delay(2000)
                }
                state.update { it.copy(facebookConnection = FacebookConnectionUi(waiting = false,
                    message = "로그인 확인을 기다리고 있어요. Facebook에서 완료한 뒤 다시 확인해주세요.")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (graph.managedStore.pending() == pending) state.update { it.copy(facebookConnection = FacebookConnectionUi(
                    waiting = false, message = serviceFailureMessage(error as? ServiceConnectionFailure ?: ServiceConnectionFailure("OFFLINE")))) }
            }
        }
    }
    fun cancelFacebookConnection() {
        pauseFacebookConnection()
        graph.managedStore.cancelLogin()
        state.update { it.copy(facebookConnection = null, message = null) }
        flushManagedCleanup()
    }
    fun selectFacebookPage(id: String): Job = action(::officialApiFailureMessage) {
        val pending = graph.managedStore.pending() ?: throw ServiceConnectionFailure("LOGIN_EXPIRED")
        val expected = pending.expectedKey?.let { graph.repository.account(it) }
            ?: graph.repository.account("FACEBOOK:PAGE:$id")
        val observation = graph.managedApi.selectPage(pending, id, expected)
        currentCoroutineContext().ensureActive()
        if (graph.managedStore.pending() != pending) throw ServiceConnectionFailure("LOGIN_EXPIRED")
        withContext(NonCancellable) {
            graph.collector.cancelProfileSession(Provider.FACEBOOK)
            withContext(Dispatchers.IO) {
                graph.managedStore.bind(observation.first)
                graph.officialApis.removeAccount(observation.first)
            }
            graph.repository.saveObservation(observation.first, observation.second, writeIf = graph.managedStore.guard())
            withContext(Dispatchers.IO) { graph.managedStore.pending(null) }
        }
        finishOfficialConnection { state.update { it.copy(facebookConnection = null) } }
    }
    private fun flushManagedCleanup() {
        if (cleanup?.isActive == true) return
        cleanup = viewModelScope.launch {
            try { graph.managedApi.flushRevocations() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Durable deletion queue is retried on the next foreground resume. */ }
        }
    }
    private suspend fun finishOfficialConnection(completed: () -> Unit) {
        completed()
        WidgetUpdates.request(getApplication())
        reload()
        RapidTracking.signalChange()
        RapidTracking.recoverRequested(getApplication())
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
        RapidTracking.signalChange()
        RapidTracking.recoverRequested(getApplication())
    }
    fun logout(provider: Provider) = action {
        val accounts = graph.repository.accounts().filter { it.provider == provider }
        withContext(NonCancellable) {
            if (provider == Provider.FACEBOOK) pauseFacebookConnection()
            withContext(Dispatchers.IO) { graph.managedStore.clear(provider) }
            withContext(Dispatchers.IO) { graph.officialApis.clear(provider) }
            withContext(Dispatchers.IO) { graph.sessions.invalidate(provider) }
            graph.collector.cancelProfileSession(provider)
            // Facebook may still have login cookies from before its Page switched to the API.
            if (provider != Provider.YOUTUBE || accounts.any { !it.isPublicYouTube })
                withContext(Dispatchers.IO) { graph.sessions.disconnect(provider) }
            graph.repository.markLoggedOut(provider)
            accounts.forEach { WorkManager.getInstance(getApplication()).cancelUniqueWork("initial-list-${it.key}") }
        }
        if (graph.repository.accounts().none { RefreshPolicy.supportsCountRefresh(it) && !it.status.blocksAutomaticRetry })
            RapidTracking.stop(getApplication())
        WidgetUpdates.request(getApplication())
        reload()
        RapidTracking.signalChange()
        if (provider == Provider.FACEBOOK) state.update { it.copy(facebookConnection = null) }
        flushManagedCleanup()
        state.update { it.copy(message = if (accounts.isNotEmpty() && accounts.all { row -> row.isPublicYouTube })
            "채널 추적을 중지했어요. 채널 링크를 다시 연결하면 이전 기록을 볼 수 있어요."
            else if (accounts.isNotEmpty() && accounts.all { row -> row.countTransport == CountTransport.OFFICIAL_API })
                "연결을 종료했어요. 채널·페이지를 다시 연결하면 이전 기록을 볼 수 있어요."
            else "${provider.title}에서 로그아웃했어요. 다시 로그인하면 이전 기록을 볼 수 있어요.") }
    }
    fun disconnect(key: String) = action {
        val account = graph.repository.account(key) ?: return@action
        withContext(Dispatchers.IO) { graph.managedStore.remove(account) }
        withContext(Dispatchers.IO) { graph.officialApis.removeAccount(account) }
        graph.collector.releaseProfileResources(account.provider)
        WorkManager.getInstance(getApplication()).cancelUniqueWork("initial-list-$key")
        graph.repository.disconnect(key)
        if (graph.repository.accounts().none { it.provider == account.provider })
            withContext(Dispatchers.IO) { graph.sessions.disconnect(account.provider) }
        WidgetUpdates.request(getApplication())
        reload()
        RapidTracking.signalChange()
        flushManagedCleanup()
    }
    private fun action(failureMessage: (CollectionFailure) -> String = ::connectionFailureMessage, block: suspend () -> Unit): Job {
        operation?.takeUnless { it.isCompleted }?.let { return it }
        return viewModelScope.launch {
            state.update { it.copy(busy = true, message = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ServiceConnectionFailure) { state.update { it.copy(message = serviceFailureMessage(failure)) } }
            catch (failure: CollectionFailure) { state.update { it.copy(message = failureMessage(failure)) } }
            catch (_: Exception) { state.update { it.copy(message = "작업을 마치지 못했어요. 기존 기록을 유지했어요.") } }
            finally { state.update { it.copy(busy = false) } }
        }.also { operation = it }
    }
}
