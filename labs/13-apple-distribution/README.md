# 实验 13：把 KMP framework 变成可验证的 Apple 分发物

## 实验目标

完成本实验后，你应该能用真实产物回答：

1. framework、fat framework 与 XCFramework 分别解决什么问题？
2. 为什么 `arm64 device` 和 `arm64 Simulator` 不能被 `lipo` 当成一个 slice？
3. 静态 framework 为什么仍然叫 framework？
4. `iosMain` source set 与多架构发布有什么关系？
5. direct integration 与 remote binary distribution 分别优化什么？
6. SwiftPM 的 URL、Git tag、checksum 和 framework 版本如何配合？
7. CocoaPods 什么时候仍然是正确选择？
8. 怎样用脚本证明产物架构、metadata、API 与 package manifest 正确？

## 前置条件

- 已完成[实验 12](../12-swift-api-design/README.md)。
- JDK 17、Xcode、Swift 6、Kotlin/Native 与 `rg` 可用。
- 可选：CocoaPods 1.16+，用于解析 podspec publishing example。
- 主工程位于 `../02-first-kmp-module`。
- 当前机器是 Intel Mac；Apple Silicon 可以额外执行 `iosSimulatorArm64Test`。

## 实验地图

```text
iosMain Kotlin source
       │
       ├─ iosArm64 framework ─────────────┐
       │                                  │
       ├─ iosSimulatorArm64 framework ─┐  │
       │                               ├──┼─ XCFramework
       └─ iosX64 framework ────────────┘  │
                                          │
              ┌───────────────────────────┘
              │
              ├─ local path Swift package
              ├─ ZIP + checksum + remote Swift package
              └─ ZIP + podspec + CocoaPods
```

## 第一步：识别现有 local integration

先查看 Xcode build phase：

```shell
cd ../02-first-kmp-module

rg -n 'embedAndSignAppleFrameworkForXcode' \
  iosApp/PocketLabIos.xcodeproj/project.pbxproj
```

PocketLab 使用：

```shell
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

记录它的职责：

- 根据 Xcode environment 选择 target/build type；
- 构建 Kotlin framework；
- 复制到 Xcode derived products；
- 处理当前构建所需的签名/嵌入流程。

它适合同仓开发，但没有创建可供其他仓库解析的版本、URL 或 checksum。

## 第二步：把 Apple 源码提升到 `iosMain`

原工程只有 `iosX64`：

```text
shared/src/iosX64Main
shared/src/iosX64Test
```

加入设备与 Apple Silicon Simulator target 后，共同 Apple 代码应位于：

```text
shared/src/iosMain
shared/src/iosTest
```

迁移内容包括：

- Darwin Ktor client；
- Native SQLDelight driver；
- iOS `actual` declarations；
- Apple API facade；
- interop 与 Native GC tests。

依赖也改为：

```kotlin
iosMain.dependencies {
    implementation("io.ktor:ktor-client-darwin:3.6.1")
    implementation("app.cash.sqldelight:native-driver:2.4.1")
}
```

如果只声明 target，不迁移 source set，观察 arm64 编译缺少哪些实现。

## 第三步：声明三种 Apple target

在 `shared/build.gradle.kts` 导入：

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
```

配置：

```kotlin
val appleBundle = XCFramework("PocketLabShared")

listOf(
    iosX64(),
    iosArm64(),
    iosSimulatorArm64(),
).forEach { appleTarget ->
    appleTarget.binaries.framework {
        baseName = "PocketLabShared"
        isStatic = true
        binaryOption("bundleId", "dev.kmpjourney.pocketlab.shared")
        binaryOption("bundleShortVersionString", "0.13.0")
        binaryOption("bundleVersion", "13")
        appleBundle.add(this)
    }
}
```

查看任务：

```shell
./gradlew :shared:tasks --all | \
  rg 'XCFramework|ios.*Test|embedAndSign'
```

应看到：

```text
assemblePocketLabSharedDebugXCFramework
assemblePocketLabSharedReleaseXCFramework
assemblePocketLabSharedXCFramework
```

Intel host 还会明确提示 `iosSimulatorArm64Test` 不能运行。保留这条证据，不要用配置把它伪装成已验证。

## 第四步：构建 Release XCFramework

执行：

```shell
./gradlew :shared:assemblePocketLabSharedReleaseXCFramework
```

首次冷构建需要分别 compile/link 三种 targets。当前实验机实测约 8 分 24 秒。

结果：

```text
shared/build/XCFrameworks/release/PocketLabShared.xcframework
```

不要把生成目录提交到 Git。它属于发布 artifact，应由 CI 根据源码 tag 重建并上传。

## 第五步：读外层 `Info.plist`

运行：

```shell
plutil -p \
  shared/build/XCFrameworks/release/PocketLabShared.xcframework/Info.plist
```

找到两个 `AvailableLibraries`：

```text
ios-arm64
ios-arm64_x86_64-simulator
```

核对：

| Identifier | Platform | Variant | Architectures |
|---|---|---|---|
| `ios-arm64` | ios | device | arm64 |
| `ios-arm64_x86_64-simulator` | ios | simulator | arm64, x86_64 |

结论：XCFramework 的关键能力是保存 platform/variant metadata，不只是把目录放在一起。

## 第六步：审计真实 Mach-O/archive 架构

执行：

```shell
find shared/build/XCFrameworks/release/PocketLabShared.xcframework \
  -type f -name PocketLabShared \
  -exec file {} \; \
  -exec lipo -archs {} \;
```

预期：

```text
device: arm64
simulator: x86_64 arm64
```

`file` 应显示 static archive，而不是动态 library。

这证明 `.framework` bundle 与 static/dynamic linkage 是两个维度。

## 第七步：故意制造 `lipo` 失败

尝试把两个 arm64 二进制合并：

```shell
lipo -create \
  shared/build/bin/iosArm64/releaseFramework/\
PocketLabShared.framework/PocketLabShared \
  shared/build/bin/iosSimulatorArm64/releaseFramework/\
PocketLabShared.framework/PocketLabShared \
  -output /tmp/PocketLabShared-invalid-fat
```

预期失败：

```text
have the same architectures (arm64)
and can't be in the same fat output file
```

解释：

```text
lipo 只区分 CPU architecture
XCFramework 同时区分 platform + variant + architecture
```

不要通过 `EXCLUDED_ARCHS=arm64` 永久绕开 Apple Silicon Simulator；那是在删除消费者支持，不是在修复发布物。

## 第八步：核对 bundle 版本

执行：

```shell
find shared/build/XCFrameworks/release/PocketLabShared.xcframework \
  -name Info.plist -type f -print -exec plutil -p {} \;
```

每个 framework slice 应包含：

```text
CFBundleIdentifier = dev.kmpjourney.pocketlab.shared
CFBundleShortVersionString = 0.13.0
CFBundleVersion = 13
```

练习：把外部 ZIP 文件名改成 `0.14.0`，但不改 bundle metadata。设计一个 gate 让发布失败。

## 第九步：比较 header 与体积

运行：

```shell
wc -l \
  shared/build/XCFrameworks/release/PocketLabShared.xcframework/\
*/PocketLabShared.framework/Headers/PocketLabShared.h

shasum -a 256 \
  shared/build/XCFrameworks/release/PocketLabShared.xcframework/\
*/PocketLabShared.framework/Headers/PocketLabShared.h

du -sh \
  shared/build/XCFrameworks/release/PocketLabShared.xcframework
```

当前证据：

- 两个 header 都是 2,267 行；
- SHA-256 完全一致；
- 完整 XCFramework 约 40 MB；
- ZIP 约 12 MB。

不要把 40 MB 当成 App 增量。XCFramework 同时携带 device 与两种 Simulator architectures，最终构建只选择需要的 slice。

## 第十步：验证本地 Swift binary package

模板位于：

```text
distribution/apple/Package.swift
```

它使用 path-based binary target：

```swift
.binaryTarget(
    name: "PocketLabShared",
    path: "Artifacts/PocketLabShared.xcframework"
)
```

运行完整门禁后，临时 package 位于：

```text
build/apple-distribution/SwiftPackage
```

检查：

```shell
swift package \
  --package-path build/apple-distribution/SwiftPackage \
  describe --type json
```

应得到 binary target `PocketLabShared`。

本地 path target 不需要 checksum，因为没有远程下载边界。

## 第十一步：准备远程 SwiftPM artifact

压缩时让 XCFramework 位于 ZIP 根目录：

```shell
ditto -c -k --sequesterRsrc --keepParent \
  shared/build/XCFrameworks/release/PocketLabShared.xcframework \
  build/apple-distribution/\
PocketLabShared-0.13.0.xcframework.zip
```

计算：

```shell
swift package compute-checksum \
  build/apple-distribution/\
PocketLabShared-0.13.0.xcframework.zip
```

远程 manifest 改为：

```swift
.binaryTarget(
    name: "PocketLabShared",
    url: "https://host/PocketLabShared-0.13.0.xcframework.zip",
    checksum: "<the checksum above>"
)
```

必须在最终 ZIP 上计算 checksum。重新压缩后重新计算；不要覆盖同一版本的远程 artifact。

## 第十二步：解析 CocoaPods publishing example

PocketLab 没有切换现有 Xcode integration，但保留一个 binary podspec example：

```text
distribution/apple/PocketLabShared.podspec
```

验证 metadata：

```shell
pod ipc spec distribution/apple/PocketLabShared.podspec
```

核对：

```text
ios.deployment_target = 15.0
ios.vendored_frameworks = PocketLabShared.xcframework
static_framework = true
```

示例 URL 在真正上传 release asset 前无效，所以 `pod ipc spec` 只证明 DSL/metadata 可解析，不证明发布完成。

如果项目使用 Kotlin CocoaPods plugin 连接 Xcode，不要同时保留 `embedAndSignAppleFrameworkForXcode` direct integration。

## 第十三步：运行自动分发门禁

执行：

```shell
scripts/verify-apple-distribution.sh
```

它会：

1. 构建 Release XCFramework；
2. 验证 device 与 universal Simulator slices；
3. 验证 static archive；
4. 验证 bundle identifier/version；
5. 验证 Swift-friendly header surface；
6. 从 XCFramework type-check Swift 6 smoke source；
7. 暂存并解析 local Swift package；
8. 生成 ZIP 与 checksum。

预期：

```text
Apple distribution contract passed.
Device architectures: arm64
Simulator architectures: x86_64 arm64
```

## 第十四步：证明 direct integration 没被破坏

Release artifact 成功不代表 App 成功。

继续运行：

```shell
xcodebuild test \
  -project iosApp/PocketLabIos.xcodeproj \
  -scheme PocketLabIos \
  -destination 'id=<simulator-id>' \
  -derivedDataPath build/xcode-derived-data \
  CODE_SIGNING_ALLOWED=NO
```

UI test 应证明：

- App 仍从 direct integration 得到当前 Kotlin framework；
- 3 条数据库记录正常渲染；
- 第一次刷新更新 3 条；
- 第二次刷新为幂等结果。

这验证了 local development 与 remote artifact 配置可以共享同一 targets/source sets，而不必把日常开发切换到 ZIP。

## 第十五步：画出你的选择矩阵

为自己的团队填写：

| 问题 | 当前答案 |
|---|---|
| Kotlin 与 iOS 是否同仓 | |
| iOS 开发者是否修改 Kotlin | |
| Kotlin 是否消费 Pods | |
| iOS 团队主要包管理器 | |
| 是否需要跨仓 binary SDK | |
| artifact 存储在哪里 | |
| 谁负责 semantic version/tag | |
| 谁运行真机/arm64 Simulator gate | |
| 如何回滚 | |

然后再选 direct、SwiftPM 或 CocoaPods。

禁止只写“SwiftPM 更现代”。每个选择必须对应上表中的至少一个事实。

## 当前验证结果

| 证据 | 结果 |
|---|---|
| iosArm64 Release compile/link | 通过 |
| iosSimulatorArm64 Release compile/link | 通过 |
| iosX64 Release compile/link | 通过 |
| XCFramework assembly | 通过 |
| device arm64 slice | 通过 |
| Simulator arm64 + x86_64 slice | 通过 |
| static archive check | 通过 |
| bundle metadata check | 通过 |
| Swift 6 import smoke | 通过 |
| Swift package parse | 通过 |
| ZIP/checksum | 通过 |
| podspec metadata parse | 通过 |
| iosX64 tests | 47/47 |

完整行为矩阵仍为 54 个不同场景、130/130 次目标执行。新增 targets 是兼容性构建证据，不应伪装成新增业务测试。

## 挑战题

1. 去掉 `iosSimulatorArm64()`，让 distribution gate 准确失败；
2. 把 `isStatic` 改为 false，比较 `file`、嵌入方式和最终 App；
3. 故意让 device 与 Simulator header 不一致，设计 normalized API diff；
4. 建立一个远程测试 package repo，在本地 HTTP server 上验证 URL/checksum；
5. 修改 ZIP 后不改 checksum，观察 SwiftPM 如何拒绝；
6. 为 podspec 建立本地 `pod lib lint` consumer，而不只解析 metadata；
7. 在 Apple Silicon runner 执行 `iosSimulatorArm64Test`；
8. 生成 App archive/link map，测量 static framework 对最终包的真实增量；
9. 为 ZIP、LICENSE、privacy manifest 与 dSYM 设计 release bundle；
10. 把多个 KMP modules 汇入一个显式 export allowlist 的 umbrella framework。

## 验收标准

只有当你能解释下面这段话，才算完成：

> Framework 是具体平台、架构、构建类型与链接方式的二进制变体；XCFramework 用 metadata 聚合并选择这些变体；SwiftPM 与 CocoaPods 再为 artifact 提供版本、下载和依赖集成。日常同仓开发优先保留 direct integration，跨仓交付才引入不可变 binary release。最终选择由依赖方向和团队工作流决定，并由消费者构建证明。

对应决策记录：[ADR-0011](../../docs/adr/0011-apple-binary-distribution.md)

对应文章：[Framework、XCFramework、SPM 与 CocoaPods：你的 Kotlin 代码究竟怎么送到 iOS？](../../articles/13-apple-framework-distribution.md)
