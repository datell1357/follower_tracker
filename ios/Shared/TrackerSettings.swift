import Foundation

enum TrackerSettings {
    private static var defaults: UserDefaults? {
        let group = Bundle.main.object(forInfoDictionaryKey: "SharedAppGroup") as? String ?? "group.dev.datell.followertracker"
        return UserDefaults(suiteName: group)
    }
    static var interval: Int {
        get { let value = defaults?.integer(forKey: "interval") ?? 0; return [30, 60, 120].contains(value) ? value : 60 }
        set { guard [30, 60, 120].contains(newValue) else { return }; defaults?.set(newValue, forKey: "interval") }
    }
    static var lastLists: Int64 {
        get { Int64(defaults?.double(forKey: "lastLists") ?? 0) }
        set { defaults?.set(Double(newValue), forKey: "lastLists") }
    }
}
