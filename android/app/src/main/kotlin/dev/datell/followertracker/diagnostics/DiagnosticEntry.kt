package dev.datell.followertracker.diagnostics

import dev.datell.followertracker.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
internal enum class DiagnosticKind {
    PROCESS_START, ATTEMPT_START, ATTEMPT_FINISH, ATTEMPT_CANCELLED, ATTEMPT_FAILURE, ATTEMPT_SKIPPED,
    READ_COMPLETE, READ_FAILURE, PROFILE_BROWSER, PROFILE_FAILURE, HTTP_RESPONSE,
    OBSERVATION_STORED, OBSERVATION_DISCARDED, STATUS_SAVED,
    SESSION_SAVED, SESSION_INVALIDATED, SESSION_CLEARED,
    TRACKING_STARTED, TRACKING_STOPPED, TRACKING_DESTROYED, TRACKING_OFFLINE, RETRY_WAIT,
    RECOVERY, WORK_STARTED, WORK_FINISHED, WIDGET_UPDATE_FAILURE, ENTRIES_DROPPED,
}

@Serializable
internal enum class DiagnosticOperation { COUNT, RELATIONSHIPS }

@Serializable
internal enum class DiagnosticStage {
    SESSION_METADATA, SESSION_AUTH, SESSION_OWNER, START_BLANK, START_ORIGIN, START_AUTH,
    NAVIGATION_ORIGIN, NAVIGATION_AUTH, FALLBACK_LOOP, FINISH_BLANK, FINISH_ORIGIN,
    HTTP, NETWORK, SSL, RENDERER, CAPTURE, LOAD_TIMEOUT, CAPTURE_TIMEOUT, CREATED, REUSED, LOCAL_BACKOFF, SERVICE_COOLDOWN,
    AUTH_BLOCKED, LOGGED_OUT, UNSUPPORTED, OFFLINE, STALE_SESSION, CONNECTION_CHANGED,
    USER_START, RECOVERY_START, USER_STOP, START_DENIED, OS_TIMEOUT, TIME_LIMIT, NO_ACCOUNTS,
    CONNECTION_REQUIRED, INTERRUPTED, STORAGE, SYNC, HEALTHY, DEFERRED, TIMEOUT, BOOT, UPDATE, RAPID_ACTIVE;

    companion object {
        /** Only fixed codes enter the log. Never retain a URL, body or exception message. */
        fun profile(value: String): DiagnosticStage =
            if (value.matches(Regex("HTTP_[1-5][0-9]{2}"))) HTTP else entries.firstOrNull { it.name == value } ?: CAPTURE
    }
}

@Serializable
internal enum class DiagnosticCaptureError {
    EXACT_COUNT_MISSING, OWN_PROFILE_REQUIRED, OWNER_CONTEXT_MISSING, IDENTITY_MISSING, DOCUMENT_CHANGED,
    PAGE_REQUIRED, PAGE_IDENTITY_MISSING, ROUNDED_COUNT_ONLY, REAUTH_REQUIRED, CHECK_REQUIRED, HTTP, UNKNOWN;

    companion object {
        fun from(value: String): DiagnosticCaptureError = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** An allowlist, not a general message logger. Account/session identifiers and metrics have no field. */
@Serializable
internal data class DiagnosticEntry(
    val kind: DiagnosticKind,
    val provider: Provider? = null,
    val accountType: AccountType? = null,
    val operation: DiagnosticOperation? = null,
    val attempt: Long? = null,
    val stage: DiagnosticStage? = null,
    val status: SyncStatus? = null,
    val background: Boolean? = null,
    val premium: Boolean? = null,
    val httpStatus: Int? = null,
    val elapsedMs: Long? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val observedAt: Long? = null,
    val serviceRetryAt: Long? = null,
    val localRetryAt: Long? = null,
    val failureCount: Int? = null,
    val droppedEntries: Long? = null,
    val captureError: DiagnosticCaptureError? = null,
) {
    init {
        require(attempt == null || attempt > 0)
        require(httpStatus == null || httpStatus in 100..599)
        require(elapsedMs == null || elapsedMs >= 0)
        require(rxBytes == null || rxBytes >= 0)
        require(txBytes == null || txBytes >= 0)
        require(failureCount == null || failureCount in 0..5)
        require(droppedEntries == null || droppedEntries >= 0)
    }
}

@Serializable
internal data class DiagnosticRecord(val atMillis: Long, val run: String, val entry: DiagnosticEntry) {
    init { require(run.matches(Regex("[a-f0-9]{32}"))) }
}

internal class DiagnosticAttempt(val id: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticAttempt>
}

internal suspend fun <T> traceCollection(account: Account, background: Boolean, operation: DiagnosticOperation,
    block: suspend () -> T): T {
    val attempt = DebugDiagnostics.nextAttempt() ?: return block()
    fun entry(kind: DiagnosticKind, status: SyncStatus? = null) = DiagnosticEntry(kind, account.provider,
        account.accountType, operation, attempt, status = status, background = background)
    DebugDiagnostics.record(entry(DiagnosticKind.ATTEMPT_START))
    return try {
        withContext(DiagnosticAttempt(attempt)) { block() }
            .also { DebugDiagnostics.record(entry(DiagnosticKind.ATTEMPT_FINISH)) }
    } catch (cancelled: CancellationException) {
        DebugDiagnostics.record(entry(DiagnosticKind.ATTEMPT_CANCELLED))
        throw cancelled
    } catch (failure: Exception) {
        DebugDiagnostics.record(entry(DiagnosticKind.ATTEMPT_FAILURE, (failure as? CollectionFailure)?.status))
        throw failure
    }
}

internal fun logSkippedRefresh(account: Account, now: Long, background: Boolean, operation: DiagnosticOperation,
    offline: Boolean = false) {
    val stage = when {
        account.loggedOut -> DiagnosticStage.LOGGED_OUT
        (account.nextAllowedAt ?: 0) > now -> DiagnosticStage.SERVICE_COOLDOWN
        account.status.blocksAutomaticRetry -> DiagnosticStage.AUTH_BLOCKED
        (account.transientRetry?.nextAttemptAt ?: 0) > now -> DiagnosticStage.LOCAL_BACKOFF
        offline -> DiagnosticStage.OFFLINE
        else -> DiagnosticStage.UNSUPPORTED
    }
    DebugDiagnostics.record(DiagnosticEntry(DiagnosticKind.ATTEMPT_SKIPPED, account.provider, account.accountType,
        operation, stage = stage, status = account.status, background = background,
        serviceRetryAt = account.nextAllowedAt, localRetryAt = account.transientRetry?.nextAttemptAt))
}
