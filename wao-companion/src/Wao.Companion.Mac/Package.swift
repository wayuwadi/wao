// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WaoCompanionMac",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "WaoCompanionMac", targets: ["WaoCompanionMac"]),
    ],
    targets: [
        .executableTarget(
            name: "WaoCompanionMac",
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "WaoCompanionMacTests",
            dependencies: ["WaoCompanionMac"]
        ),
    ]
)
