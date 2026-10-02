import SwiftUI

struct TrackerWidgetGallery: View {
    let accounts: [AccountOverview]
    let storageError: Bool
    var busy = false
    let onConnect: () -> Void
    @SceneStorage("tracker.widgetPreviewAccount") private var selection = "all"
    private var validSelection: String { accounts.contains { $0.id == selection } ? selection : "all" }
    private var rows: [AccountOverview] { accounts.filter { validSelection == "all" || $0.id == validSelection } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("홈 화면에서도,\n가장 빠르게.").font(.title.bold())
                    Text("마지막으로 읽은 수와 실제 수집 시각을 함께 확인해요.").font(.subheadline).foregroundStyle(TrackerStyle.muted)
                }.padding(.vertical, 8)
                Picker("미리 볼 계정", selection: Binding(get: { validSelection }, set: { selection = $0 })) {
                    Text("모든 계정").tag("all")
                    ForEach(accounts) { row in Text(row.account.provider.title + " · @" + row.account.username).tag(row.id) }
                }.pickerStyle(.menu).frame(minHeight: 48).disabled(accounts.isEmpty || storageError)
                TrackerSectionTitle(title: "작은 위젯")
                HStack {
                    Spacer()
                    TrackerWidgetContent(rows: rows, storageError: storageError).padding(16).frame(width: 172, height: 184)
                        .background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
                    Spacer()
                }.dynamicTypeSize(.medium)
                TrackerSectionTitle(title: "넓은 위젯")
                TrackerWidgetContent(rows: rows, storageError: storageError, size: .medium).padding(16).frame(height: 180)
                    .background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24)).dynamicTypeSize(.medium)
                Text("저장된 기록의 미리보기예요. 실제 위젯의 계정과 크기는 홈 화면에서 설정해요.").font(.caption).foregroundStyle(TrackerStyle.muted)
                TrackerSectionTitle(title: "위젯 추가하기")
                VStack(alignment: .leading, spacing: 18) {
                    step("1", "홈 화면의 빈 곳을 길게 눌러요.")
                    step("2", "편집 또는 + 버튼에서 위젯 추가를 열고 ‘팔로워 트래커’를 찾아요.")
                    step("3", "원하는 크기를 추가한 뒤 위젯을 길게 눌러 표시할 계정을 선택해요.")
                }.trackerPanel()
                TrackerInfoPanel(title: "위젯을 누르면 홈으로", detail: "iOS가 갱신 시각을 정해요. 숫자가 같을 때도 수집 시각을 확인하세요. 위젯을 누르면 앱 홈에서 계정 상태를 볼 수 있어요.", icon: "hand.tap")
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
