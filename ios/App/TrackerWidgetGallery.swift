import SwiftUI

struct TrackerWidgetGallery: View {
    let accounts: [AccountOverview]
    let storageError: Bool
    var busy = false
    let onConnect: () -> Void
    @SceneStorage("tracker.widgetPreviewAccount") private var selection = "all"
    private var validSelection: String { accounts.contains { $0.id == selection } ? selection : "all" }
    private var rows: [AccountOverview] { validSelection == "all" ? Array(accounts.prefix(1)) : accounts.filter { $0.id == validSelection } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Picker("미리 볼 계정", selection: Binding(get: { validSelection }, set: { selection = $0 })) {
                    Text("첫 연결 계정").tag("all")
                    ForEach(accounts) { row in Text(row.account.provider.title + " · @" + row.account.username).tag(row.id) }
                }.pickerStyle(.menu).frame(minHeight: 48).disabled(accounts.isEmpty || storageError)
                TrackerSectionTitle(title: "내 계정 · 최소 크기")
                HStack {
                    Spacer()
                    TrackerWidgetContent(rows: rows, storageError: storageError).frame(width: 172, height: 184)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                    Spacer()
                }.dynamicTypeSize(.medium)
                TrackerSectionTitle(title: "소셜 모아보기 · 대형")
                TrackerWidgetContent(rows: accounts, storageError: storageError, size: .large).frame(height: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 24)).dynamicTypeSize(.medium)
                Text("숫자와 함께 마지막 실제 수집 시각을 확인하세요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                TrackerSectionTitle(title: "위젯 추가하기")
                VStack(alignment: .leading, spacing: 18) {
                    step("1", "홈 화면의 빈 곳을 길게 눌러요.")
                    step("2", "편집 또는 + 버튼에서 위젯 추가를 열고 ‘팔로워 트래커’를 찾아요.")
                    step("3", "내 계정 또는 소셜 모아보기를 추가해요. 내 계정은 위젯 편집에서 SNS를 선택해요.")
                }.trackerPanel()
                TrackerInfoPanel(title: "위젯을 누르면 계정으로", detail: "iOS가 갱신 시각을 정해요. 숫자가 같을 때도 수집 시각을 확인하세요. 위젯을 누르면 앱의 계정 탭에서 수집 상태를 볼 수 있어요.", icon: "hand.tap")
                if accounts.isEmpty && !storageError { Button("SNS 연결하기", systemImage: "plus", action: onConnect).buttonStyle(TrackerPrimaryButtonStyle()).disabled(busy) }
            }.foregroundStyle(TrackerStyle.ink).padding(20)
        }.background(TrackerStyle.background)
    }
    private func step(_ number: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number).font(.caption.weight(.bold)).foregroundStyle(TrackerStyle.blue).frame(width: 28, height: 28).background(TrackerStyle.blueSurface, in: Circle()).accessibilityHidden(true)
            Text(text).font(.subheadline).fixedSize(horizontal: false, vertical: true)
        }.accessibilityElement(children: .combine)
    }
}

#Preview("위젯 · 미연결") { TrackerWidgetGallery(accounts: [], storageError: false, onConnect: {}) }
