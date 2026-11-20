// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PocketLabSharedPackage",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "PocketLabShared",
            targets: ["PocketLabShared"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "PocketLabShared",
            path: "Artifacts/PocketLabShared.xcframework"
        ),
    ]
)
