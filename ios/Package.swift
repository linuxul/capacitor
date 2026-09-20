// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "capacitor-swift-pm",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "Capacitor",
            targets: ["Capacitor"]
        )
    ],
    targets: [
        .target(
            name: "Capacitor",
            path: "Capacitor/Capacitor",
            exclude: ["Info.plist"],
            resources: [
                .copy("assets/native-bridge.js"),
                .copy("PrivacyInfo.xcprivacy")
            ]
        )
    ]
)
