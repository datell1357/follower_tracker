import SwiftUI
import FollowerCore

struct TrackerSettingsView: View {
    @Bindable var model: TrackerModel
    let onReconnect: (Provider) -> Void, onDisconnect: (String) -> Void
    @State private var interval = TrackerSettings.interval
    var body: some View {
        List {
            Section("수집 요청 간격") {
                Picker("간격", selection: $interval) { Text("30분").tag(30); Text("60분").tag(60); Text("2시간").tag(120) }.pickerStyle(.segmented)
                Text("운영체제가 실행 시각을 정해요. 위젯과 네트워크·절전 상태에 따라 늦어질 수 있고, 명단 예약 수집은 하루 간격으로 요청해요.").font(.caption).foregroundStyle(.secondary)
            }
            Section("계정 관리") {
                if model.accounts.isEmpty { Text("추적 탭에서 첫 SNS를 연결해주세요.").foregroundStyle(.secondary) }
                ForEach(model.accounts) { row in VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) { ProviderMark(provider: row.account.provider); VStack(alignment: .leading) { Text(row.account.provider.title).font(.headline); Text("@" + row.account.username).font(.caption).foregroundStyle(.secondary) } }
                    HStack { Button("로그인 확인") { onReconnect(row.account.provider) }; Spacer(); Button("연결 해제", role: .destructive) { onDisconnect(row.id) } }.disabled(model.busy)
                }.padding(.vertical, 8) }
            }
            Section("기기 안에서 처리해요") {
                Text("SNS 로그인은 공식 웹페이지에서 진행해요. 세션과 명단을 팔로워 트래커 서버로 보내지 않아요. 로그인 쿠키는 Keychain에, 관계 명단은 암호화해 기기에 보관해요. 위젯에는 수치와 마지막 수집 시각을 표시해요.").font(.subheadline).foregroundStyle(.secondary)
            }
            Section { Text("팔로워 트래커 0.1.0").font(.caption); Text("계정별 수집 경로를 검증하고 있어요. 서비스의 로그인·응답 방식이 바뀌면 갱신이 멈출 수 있어요.").font(.caption).foregroundStyle(.secondary) }
        }.onChange(of: interval) { _, value in TrackerSettings.interval = value; BackgroundRefresh.schedule() }
    }
}
