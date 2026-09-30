// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FollowerCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "FollowerCore", targets: ["FollowerCore"])],
    targets: [
        .target(name: "FollowerCore"),
        .testTarget(name: "FollowerCoreTests", dependencies: ["FollowerCore"])
    ],
    swiftLanguageModes: [.v5]
)
