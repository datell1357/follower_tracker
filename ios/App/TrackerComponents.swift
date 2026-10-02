import SwiftUI

extension View {
    func trackerPanel(padding: CGFloat = 20) -> some View {
        self.padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .background(TrackerStyle.surface, in: RoundedRectangle(cornerRadius: 24))
    }
}

struct TrackerBadge: View {
    let text: String
    var body: some View {
        Text(text).font(.caption.weight(.semibold)).foregroundStyle(TrackerStyle.blue)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(TrackerStyle.blueSurface, in: Capsule())
    }
}

struct TrackerSectionTitle: View {
    let title: String
    var body: some View { Text(title).font(.headline).foregroundStyle(TrackerStyle.ink).padding(.top, 4) }
}

struct TrackerInfoPanel: View {
    let title: String, detail: String
    var icon = "info.circle"
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(TrackerStyle.blue).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(TrackerStyle.ink)
                Text(detail).font(.subheadline).foregroundStyle(TrackerStyle.muted).fixedSize(horizontal: false, vertical: true)
            }
        }.trackerPanel().accessibilityElement(children: .combine)
    }
}

struct TrackerMenuLabel: View {
    let title: String, subtitle: String, icon: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.title3).foregroundStyle(TrackerStyle.blue).frame(width: 28).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(TrackerStyle.ink)
                Text(subtitle).font(.caption).foregroundStyle(TrackerStyle.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(TrackerStyle.muted).accessibilityHidden(true)
        }.frame(minHeight: 48).padding(18).contentShape(Rectangle())
    }
}

struct TrackerPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.subheadline.weight(.semibold)).padding(.horizontal, 20).padding(.vertical, 14)
            .frame(minHeight: 48).foregroundStyle(TrackerStyle.onBlue)
            .background(TrackerStyle.blue, in: RoundedRectangle(cornerRadius: 16))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
    }
}

#Preview("빈 상태") { EmptyCard("첫 계정을 연결해보세요", "팔로워 수와 수집 시각을 함께 확인해요.").padding().background(TrackerStyle.background) }
#Preview("상태 안내") { TrackerInfoPanel(title: "마지막 기록을 유지했어요", detail: "요청 제한이 해제되면 다시 갱신할 수 있어요.").padding().background(TrackerStyle.background).preferredColorScheme(.dark) }
