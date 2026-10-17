package dev.datell.followertracker.sync

import dev.datell.followertracker.core.SyncStatus
import org.junit.Assert.*
import org.junit.Test

class CountRefreshStatusTest {
    @Test fun aStoredFormatFailureCannotBeReportedAsACompletedBatch() {
        val message = countBatchMessage(listOf(SyncStatus.READY, SyncStatus.FORMAT_CHANGED), true, false)
        assertEquals("일부 SNS 갱신 오류 · 다른 계정은 계속 추적해요", message)
    }

    @Test fun aStoredFormatFailureRemainsVisibleWhileWaitingForItsNextAttempt() {
        assertEquals("일부 SNS 갱신 오류 · 다른 계정은 계속 추적해요",
            countBatchMessage(listOf(SyncStatus.READY, SyncStatus.FORMAT_CHANGED), false, false))
    }

    @Test fun successRequiresFreshDataAndNoCollectionFailure() {
        assertEquals("수집 완료 · 1분 간격으로 확인해요",
            countBatchMessage(listOf(SyncStatus.READY, SyncStatus.READY), true, false))
        assertEquals("마지막 기록을 유지하고 있어요",
            countBatchMessage(listOf(SyncStatus.READY), false, false))
        assertEquals("일부 SNS 갱신 오류 · 다른 계정은 계속 추적해요",
            countBatchMessage(listOf(SyncStatus.READY), true, true))
    }

    @Test fun authenticationAndServerLimitsKeepTheirSpecificMessages() {
        assertEquals("일부 SNS 연결 확인 필요 · 가능한 계정은 계속 추적해요",
            countBatchMessage(listOf(SyncStatus.READY, SyncStatus.CHECK_REQUIRED), true, false))
        assertEquals("SNS 요청 제한 · 대기 후 다시 확인해요",
            countBatchMessage(listOf(SyncStatus.READY, SyncStatus.RATE_LIMITED), true, false))
        assertEquals("일시 오류 · 잠시 후 다시 확인해요",
            countBatchMessage(listOf(SyncStatus.READY, SyncStatus.OFFLINE), true, false))
    }
}
