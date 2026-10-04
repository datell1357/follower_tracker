import SwiftUI
import FollowerCore

struct ProviderBrand {
    let provider: Provider
    private var tones: (accent: Color, start: Color, end: Color, stripe: [Color]) {
        let adaptive = TrackerStyle.adaptive
        func fixed(_ value: UInt32) -> Color { adaptive(value, value) }
        switch provider {
        case .instagram:
            return (adaptive(0xA83077, 0xFFADD4), adaptive(0xF7E9F5, 0x2D1F30), adaptive(0xFFF3E5, 0x30271F), [fixed(0x833AB4), fixed(0xFD1D1D), fixed(0xFCAF45)])
        case .tiktok:
            return (adaptive(0x14636A, 0x8CDDDD), adaptive(0xE7F7F6, 0x123032), adaptive(0xFAE7EE, 0x31202A), [fixed(0x25F4EE), fixed(0x171A20), fixed(0xFE2C55)])
        case .x:
            return (adaptive(0x20242B, 0xE8EAEF), adaptive(0xEAEDEF, 0x26282E), adaptive(0xF7F8FA, 0x202228), [adaptive(0x101217, 0xFFFFFF), adaptive(0x67707D, 0xAEB4BE)])
        case .facebook:
            return (adaptive(0x064CB8, 0x9DBEFF), adaptive(0xE9F2FF, 0x192B47), adaptive(0xF6F9FF, 0x1C2433), [fixed(0x0866FF), fixed(0x4B91FF)])
        case .reddit:
            return (adaptive(0xA93600, 0xFFB088), adaptive(0xFFEBE0, 0x39251E), adaptive(0xFFF5EF, 0x2E2521), [fixed(0xFF4500), fixed(0xFF8717)])
        }
    }
    var accent: Color { tones.accent }
    var background: LinearGradient { LinearGradient(colors: [tones.start, tones.end], startPoint: .topLeading, endPoint: .bottomTrailing) }
    var stripe: LinearGradient { LinearGradient(colors: tones.stripe, startPoint: .leading, endPoint: .trailing) }
    var iconBackground: LinearGradient {
        switch provider {
        case .instagram:
            LinearGradient(colors: [TrackerStyle.adaptive(0x833AB4, 0x833AB4), TrackerStyle.adaptive(0xE1306C, 0xE1306C), TrackerStyle.adaptive(0xFCAF45, 0xFCAF45)], startPoint: .topLeading, endPoint: .bottomTrailing)
        case .x, .tiktok:
            LinearGradient(colors: [.black, .black], startPoint: .top, endPoint: .bottom)
        case .facebook, .reddit:
            LinearGradient(colors: [accent.opacity(0.10), accent.opacity(0.10)], startPoint: .top, endPoint: .bottom)
        }
    }
    var assetName: String {
        switch provider {
        case .instagram: "ProviderInstagram"
        case .tiktok: "ProviderTikTok"
        case .x: "ProviderX"
        case .facebook: "ProviderFacebook"
        case .reddit: "ProviderReddit"
        }
    }
}

struct ProviderCardBackground: View {
    let provider: Provider
    var body: some View {
        let brand = ProviderBrand(provider: provider)
        let shape = RoundedRectangle(cornerRadius: 24)
        ZStack(alignment: .top) {
            shape.fill(brand.background)
            Rectangle().fill(brand.stripe).frame(height: 4)
        }.clipShape(shape).overlay(shape.strokeBorder(brand.accent.opacity(0.18), lineWidth: 1))
    }
}
