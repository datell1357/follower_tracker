import SwiftUI

struct TrackerSettingsView: View {
    @State private var interval = TrackerSettings.interval
    var body: some View {
        Form {
            Section("수집 요청 간격") {
                Picker("갱신 간격", selection: $interval) { Text("30분").tag(30); Text("60분").tag(60); Text("2시간").tag(120) }
                    .pickerStyle(.menu).frame(minHeight: 48)
                Text("운영체제가 실제 실행 시각을 정해요. 네트워크와 절전 상태에 따라 늦어질 수 있어요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
            }.listRowBackground(TrackerStyle.surface)
            Section("명단 수집") {
                Text("전체 명단은 하루 간격으로 요청해요. 끝까지 읽은 명단만 관계 비교에 사용해요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
            }.listRowBackground(TrackerStyle.surface)
            Section {
                Text("iOS에서는 1분마다 계속 갱신하는 모드를 제공하지 않아요. 마지막 수집 시각과 계정 상태를 확인해주세요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
            }.listRowBackground(TrackerStyle.surface)
        }.scrollContentBackground(.hidden).background(TrackerStyle.background).foregroundStyle(TrackerStyle.ink)
            .navigationTitle("수집 설정").navigationBarTitleDisplayMode(.inline)
            .onChange(of: interval) { _, value in TrackerSettings.interval = value; BackgroundRefresh.schedule() }
    }
}

#Preview("수집 설정") { NavigationStack { TrackerSettingsView() } }
