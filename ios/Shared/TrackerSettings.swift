import Foundation

enum TrackerSettings {
    private static var defaults: UserDefaults? {
        let group = Bundle.main.object(forInfoDictionaryKey: "SharedAppGroup") as? String ?? "group.dev.datell.followertracker"
        return UserDefaults(suiteName: group)
    }
    static let interval = 15
    static var lastLists: Int64 {
        get { Int64(defaults?.double(forKey: "lastLists") ?? 0) }
        set { defaults?.set(Double(newValue), forKey: "lastLists") }
    }
}
