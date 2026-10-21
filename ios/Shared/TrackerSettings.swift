import Foundation

enum TrackerSettings {
    private static var defaults: UserDefaults? {
        let group = Bundle.main.object(forInfoDictionaryKey: "SharedAppGroup") as? String ?? "group.dev.datell.followertracker"
        return UserDefaults(suiteName: group)
    }
    static let interval = 15
    static var overviewPage: Int { max(0, defaults?.integer(forKey: "widgetOverviewPage") ?? 0) }
    static func showOverviewPage(_ page: Int) throws {
        guard let defaults else { throw StorageFailure.unavailable }
        defaults.set(max(0, page), forKey: "widgetOverviewPage")
    }
    static var lastLists: Int64 {
        get { Int64(defaults?.double(forKey: "lastLists") ?? 0) }
        set { defaults?.set(Double(newValue), forKey: "lastLists") }
    }
}
