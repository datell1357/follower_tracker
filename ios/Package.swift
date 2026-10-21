// swift-tools-version: 6.0
import PackageDescription

// Runs actual shared storage/HTTP code on macOS without a simulator, App Group, or real Keychain.
let package = Package(
    name: "FollowerStorageExperiments",
    platforms: [.macOS(.v15)],
    dependencies: [.package(path: "FollowerCore")],
    targets: [
        .target(name: "FollowerTracker", dependencies: [.product(name: "FollowerCore", package: "FollowerCore")],
            path: ".", exclude: [
                "App", "Widget", "Config", "FollowerCore", "FollowerTracker.xcodeproj", "scripts", "Tests",
                "Shared/TrackerWidgetContent.swift", "Shared/TrackerStyle.swift", "Shared/NetworkAvailability.swift",
                "Shared/PrivacyInfo.xcprivacy", "Shared/OverviewWidgetPageIntent.swift", "Shared/SecureSession.swift",
                "Shared/WidgetUpdates.swift", "Shared/ProviderBrand.swift", "Shared/TrackerSettings.swift"
            ], sources: ["Shared/TrackerRepository.swift", "Shared/SessionCollector.swift", "TestsSupport/RuntimeStubs.swift"],
            linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(name: "FollowerTrackerTests", dependencies: ["FollowerTracker"],
            path: "Tests", exclude: ["AutoConnectionRuntimeTests.swift", "DesignRuntimeTests.swift"],
            sources: ["SyncCooldownRuntimeTests.swift", "FacebookPageStorageRuntimeTests.swift",
                "FacebookPageHTTPRuntimeTests.swift", "ResourceEfficiencyRuntimeTests.swift"])
    ],
    swiftLanguageModes: [.v6]
)
