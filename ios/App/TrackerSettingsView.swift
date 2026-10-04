import SwiftUI

struct TrackerSettingsView: View {
    var body: some View {
        Form {
            Section("명단 수집") {
                Text("전체 명단은 하루 간격으로 요청해요. 끝까지 읽은 명단만 관계 비교에 사용해요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
            }.listRowBackground(TrackerStyle.surface)
            Section {
                Text("iOS에서는 1분마다 계속 갱신하는 모드를 제공하지 않아요. 마지막 수집 시각과 계정 상태를 확인해주세요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
            }.listRowBackground(TrackerStyle.surface)
        }.scrollContentBackground(.hidden).background(TrackerStyle.background).foregroundStyle(TrackerStyle.ink)
            .navigationTitle("수집 설정").navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("수집 설정") { NavigationStack { TrackerSettingsView() } }
