# Framework、XCFramework、SPM 与 CocoaPods：你的 Kotlin 代码究竟怎么送到 iOS？

> 面向读者：已经能在 Swift 中调用 KMP，希望把共享模块交付给真实 iOS 团队的 Android/Kotlin 开发者
>
> 技术核对与实验验证日期：2026-11-20
>
> 实验版本：Kotlin 2.4.21、Swift 6.3.2、Xcode 26.5、CocoaPods 1.16.2、Intel macOS host

上一篇，我们把 Kotlin 导出的 API 修成了 Swift 愿意使用的样子。

这一次，API 没变，问题却更接近上线：

> 怎样把这份 API 可靠地送到另一台 Mac、另一个 iOS 工程，甚至另一个团队？

刚接触 Apple 交付体系时，很容易把四个词放在同一排：

```text
Framework / XCFramework / Swift Package Manager / CocoaPods
```

然后问：“到底选哪个？”

这个问题从一开始就把不同层次混在了一起。

Framework 与 XCFramework 描述二进制产物；Swift Package Manager（下文简称 SwiftPM）与 CocoaPods 描述依赖发现、版本解析和接入流程。

真正的关系更像：

```text
Kotlin source
    ↓ Kotlin/Native compile + link
Framework slice
    ↓ gather platform/architecture variants
XCFramework artifact
    ↓ zip + checksum + metadata
SwiftPM package ─────┐
                     ├─→ consumer Xcode project
CocoaPods podspec ───┘
```

它们不是四位选手争一个冠军，而是处在流水线的不同工位。

## 先给 PocketLab 的答案

PocketLab 当前采用“双轨制”：

```text
同仓日常开发
  → embedAndSignAppleFrameworkForXcode
  → Kotlin 改动立即进入 SwiftUI App

跨仓 / 版本化交付
  → Release XCFramework
  → ZIP + checksum
  → SwiftPM binaryTarget
```

CocoaPods 没有被贬低，也没有被装进当前 Xcode 工程。它保留为两种情况下的正式选项：

1. Kotlin 源码必须消费不可替代的 Pod 依赖；
2. iOS 团队已有成熟 Pod 私服、版本治理和发布流水线。

这个选择不是“SwiftPM 更新，所以 CocoaPods 过时”，而是根据仓库边界、依赖方向和团队工作流做出的。

接下来，我们用真实产物证明每一层。

## 第一层：Framework 不是“iOS 万能包”

PocketLab 原来只有：

```kotlin
iosX64 {
    binaries.framework {
        baseName = "PocketLabShared"
        isStatic = true
    }
}
```

它生成：

```text
PocketLabShared.framework/
├── Headers/PocketLabShared.h
├── Modules/module.modulemap
├── Info.plist
└── PocketLabShared
```

从目录外观看，它像一个完整产品。

但 `iosX64` 已经写出了它的限制：

- 平台：iOS Simulator；
- CPU：x86_64；
- build type：Debug 或 Release 中的一种；
- linkage：PocketLab 选择 static；
- module/API：`PocketLabShared` 当前导出面。

换句话说，一个 framework 是一个具体变体，不是“所有 iPhone 与所有 Mac 都能使用”的抽象概念。

把 x86_64 Simulator framework 发给真机工程，文件名不会报错，链接器会。

## Framework 目录不代表动态链接

很多人看到 `.framework`，会自然联想到动态库。

PocketLab 的真实二进制告诉我们不是这样：

```shell
file PocketLabShared.framework/PocketLabShared
```

Release XCFramework 内的 slice 返回：

```text
current ar archive random library
```

这是静态 archive。

`framework` 是 Apple 的 bundle 组织形式；里面的 Mach-O 可以是静态，也可以是动态。

二者的工程后果不同：

| 维度 | 静态 framework | 动态 framework |
|---|---|---|
| 代码进入 App 的时机 | 链接时复制进最终 executable | App 启动/运行时加载 |
| 是否需要嵌入并签名 | 通常不作为独立动态库嵌入 | 通常需要 Embed & Sign |
| 多个组件重复链接风险 | 需要审计重复符号/运行时 | 需要审计加载路径与签名 |
| 启动与产物结构 | 通常更简单 | 多一个运行时装载单元 |
| “文件夹大小”等于 App 增量 | 不等于 | 也不等于 |

PocketLab 选择静态，是因为它目前只有一个 App、一个 KMP umbrella 产物，不需要让多个进程或插件在运行时共享动态库。

这不是永恒规则。若宿主结构、扩展目标或二进制复用方式变化，必须重新测量最终 App，而不是凭“静态更快”或“动态更专业”站队。

## 第二层：先补齐 Apple target，再谈分发

为了让产物覆盖真实消费者，本次把 Apple targets 改为：

```kotlin
listOf(
    iosX64(),
    iosArm64(),
    iosSimulatorArm64(),
)
```

它们分别服务：

| Target | 典型消费者 |
|---|---|
| `iosX64` | Intel Mac 上的 iOS Simulator |
| `iosSimulatorArm64` | Apple Silicon Mac 上的 iOS Simulator |
| `iosArm64` | iPhone/iPad 真机与归档 |

这一步不是多复制两份 build 配置。

原来的 Apple 源码在：

```text
src/iosX64Main
src/iosX64Test
```

如果只新增 targets 而不迁移 source set，arm64 framework 会缺少数据库 driver、Darwin HTTP client、Apple facade 和 actual implementations。

所以它们被提升到：

```text
src/iosMain
src/iosTest
```

依赖也从 `iosX64Main.dependencies` 进入 `iosMain.dependencies`：

```kotlin
iosMain.dependencies {
    implementation("io.ktor:ktor-client-darwin:3.6.1")
    implementation("app.cash.sqldelight:native-driver:2.4.1")
}
```

这是 source set 层级第一次直接影响发布正确性：

> “这段代码属于哪组平台”不仅决定复用，也决定哪些二进制变体能完整编译。

## 第三层：XCFramework 是变体选择器

PocketLab 使用 Kotlin Gradle Plugin 的 `XCFramework`：

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

构建命令：

```shell
./gradlew :shared:assemblePocketLabSharedReleaseXCFramework
```

首次冷构建在当前 Intel Mac 上实际耗时 8 分 24 秒。它不是日常每次点 Run 都应该承担的成本，而是发布/CI 工作。

结果位于：

```text
shared/build/XCFrameworks/release/PocketLabShared.xcframework
```

目录核心结构是：

```text
PocketLabShared.xcframework/
├── Info.plist
├── ios-arm64/
│   └── PocketLabShared.framework
└── ios-arm64_x86_64-simulator/
    └── PocketLabShared.framework
```

注意不是三个平铺 framework。

两个 Simulator 架构可以合并为同一平台变体的 fat framework；设备平台被放在另一个 slice。

XCFramework 外层 `Info.plist` 记录：

```text
ios-arm64
  SupportedPlatform = ios
  SupportedArchitectures = [arm64]

ios-arm64_x86_64-simulator
  SupportedPlatform = ios
  SupportedPlatformVariant = simulator
  SupportedArchitectures = [arm64, x86_64]
```

Xcode 根据当前 destination 选择正确 slice。

它不是把所有机器码一次性塞进最终 App；它是把候选变体放进一个可分发容器，让构建系统选择。

## 为什么不能用一个超级 fat framework？

`lipo` 能把不同 CPU 架构合并进一个 universal binary。

但 CPU 架构不是平台身份的全部。

PocketLab 故意执行：

```shell
lipo -create \
  iosArm64/PocketLabShared \
  iosSimulatorArm64/PocketLabShared \
  -output PocketLabShared-invalid-fat
```

真实失败为：

```text
have the same architectures (arm64)
and can't be in the same fat output file
```

设备和 Apple Silicon Simulator 都叫 `arm64`，但它们面向不同平台 ABI。

`lipo` 只会看架构集合，无法表达“这个 arm64 给 iPhone，另一个 arm64 给 Simulator”。XCFramework 的 slice metadata 才能表达。

因此：

```text
fat framework
  = 同一平台内组合多个 CPU architectures

XCFramework
  = 组合多个 platform / variant / architecture artifacts
```

PocketLab 的 Simulator slice 是 fat framework；整个交付物是 XCFramework。两者可以同时存在，并不冲突。

## 产物审计：不要只看任务成功

本次 Release 产物的真实数据是：

| 项目 | 结果 |
|---|---:|
| device framework | arm64，约 14 MB |
| Simulator framework | arm64 + x86_64，二进制约 26 MB |
| 完整 XCFramework | 约 40 MB |
| ZIP archive | 约 12 MB |
| 两个 slice 的 header | 各 2,267 行 |
| header SHA-256 | 完全一致 |

这组数字能证明两件事：

1. API surface 在不同 slice 中一致；
2. XCFramework 目录大小包含多个候选架构，不能当作最终 App 增量。

真正的体积决策要比较 Release App archive、link map 与 App Store thinning 后结果。

“XCFramework 有 40 MB，所以 App 增加 40 MB”是错误推断。

## Bundle metadata 也属于交付契约

PocketLab 为每个 framework slice 固定：

```text
CFBundleIdentifier = dev.kmpjourney.pocketlab.shared
CFBundleShortVersionString = 0.13.0
CFBundleVersion = 13
```

这三个值不是装饰：

- identifier 用于稳定识别 bundle；
- short version 表达产品版本；
- bundle version 表达构建迭代；
- 发布脚本可以拒绝“文件名叫 0.13.0，内部仍是 0.12.0”的产物。

版本必须从自动化可检查的单一来源继续演进。本实验先把它写进 DSL，是为了让 metadata 进入证据；后续 CI 里会把它提升为 release 参数。

## 第四层：本地开发为什么继续用 direct integration

PocketLab 的 Xcode Build Phase 运行：

```shell
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

这条 direct integration 路径适合同仓开发，因为：

- Kotlin 改完，下一次 Xcode build 自动得到新 framework；
- Xcode 的 SDK、configuration 与 architecture 环境会传给 Gradle；
- 可以直接调试 Kotlin 与 Swift 的联合行为；
- 不需要为了每次本地改动创建版本、上传 ZIP、改 checksum。

它的代价也很明确：

- iOS 开发者需要 JDK、Gradle、Kotlin/Native 和共享源码；
- Xcode build 会触发 Kotlin 编译；
- 缓存和工具链问题进入本地反馈环；
- 它不是跨仓二进制发布协议。

因此不能因为已经有 `embedAndSignAppleFrameworkForXcode`，就宣布“我们已经支持 SDK 分发”。

同样，也不该为了模拟第三方 SDK，把同仓每次开发都改成远程二进制。

## 第五层：SwiftPM 负责的不是编译 Kotlin

远程 SwiftPM binary package 的核心 manifest 是：

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PocketLabSharedPackage",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "PocketLabShared",
            targets: ["PocketLabShared"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "PocketLabShared",
            url: "https://example/PocketLabShared-0.13.0.xcframework.zip",
            checksum: "<sha256>"
        ),
    ]
)
```

SwiftPM 不会理解这些二进制是 Kotlin/Native 编出来的。

它只负责：

- 根据 Git tag 解析 package version；
- 下载 URL 对应的 archive；
- 计算并验证 checksum；
- 暴露 product/target；
- 把 binary artifact 交给 Xcode 构建系统选择 slice。

这意味着发布顺序不能乱：

```text
1. 构建 Release XCFramework
2. 执行 API / architecture / metadata gate
3. ZIP，且 XCFramework 位于 archive 根目录
4. 计算 checksum
5. 上传不可变 archive
6. 写入 Package.swift 的 URL + checksum
7. 提交并打 semantic version Git tag
8. 在干净消费者中解析与构建
```

如果先提交 manifest，后替换同一 URL 的 ZIP，checksum 会保护消费者——也会让所有解析失败。

正确动作不是更新同版本 checksum，而是发布新版本与新 URL。

## 本地 Swift binary package 不需要 checksum

Apple 同时支持 path-based binary target：

```swift
.binaryTarget(
    name: "PocketLabShared",
    path: "Artifacts/PocketLabShared.xcframework"
)
```

本次门禁把刚生成的 XCFramework 复制到临时 package：

```text
build/apple-distribution/SwiftPackage/
├── Package.swift
└── Artifacts/PocketLabShared.xcframework
```

然后执行：

```shell
swift package dump-package
swift package describe --type json
```

解析结果确认：

```text
package = PocketLabSharedPackage
product = PocketLabShared
target = PocketLabShared: binary
```

这条路径验证 manifest 与本地产物能组成合法 package，但它不是远程发布完成的证据。

远程路径还必须证明：

- URL 可下载；
- ZIP 根结构正确；
- checksum 匹配；
- Git tag 可解析；
- 干净机器没有依赖本地缓存。

## checksum 保护的是 archive，不是“代码大概一样”

PocketLab 运行：

```shell
swift package compute-checksum \
  PocketLabShared-0.13.0.xcframework.zip
```

本次 archive 得到了一个 64 位十六进制 SHA-256 值。

这个值属于这一个 ZIP 的字节内容。重新打包，即使内部 framework 逻辑没变，也可能得到不同 archive bytes。

因此 checksum 必须由发布流水线在最终上传文件上计算，不能手写、猜测或从另一个压缩包复制。

## SwiftPM 的版本不是 framework 内版本

这里至少有四个版本概念：

| 层次 | 示例 | 谁消费 |
|---|---|---|
| Kotlin/项目版本 | `0.13.0` | Gradle/发布流水线 |
| framework bundle version | `0.13.0` / `13` | Apple bundle tooling |
| Swift package Git tag | `0.13.0` | SwiftPM resolver |
| binary checksum | `<archive SHA-256>` | SwiftPM integrity check |

它们需要在一次 release 中对齐，但含义不相同。

checksum 不是 semantic version；bundle version 也不会自动创建 SwiftPM tag。

## CocoaPods 不是“另一种 XCFramework”

CocoaPods 同样可以分发 XCFramework。

一个 binary podspec 的关键字段是：

```ruby
Pod::Spec.new do |spec|
  spec.name = "PocketLabShared"
  spec.version = "0.13.0"
  spec.ios.deployment_target = "15.0"
  spec.ios.vendored_frameworks = "PocketLabShared.xcframework"
  spec.static_framework = true
end
```

PocketLab 提供了发布示例，并用：

```shell
pod ipc spec distribution/apple/PocketLabShared.podspec
```

验证 DSL 能解析为标准 JSON metadata。

但没有发布这个 spec，也没有假装示例 URL 已存在。metadata 可解析只是第一道门；真正发布仍需要 archive、source URL、spec repo 与干净消费者验证。

## Kotlin CocoaPods plugin 还解决另一类问题

Kotlin CocoaPods Gradle plugin 不只会写一个 binary podspec。

它可以：

- 让 KMP 项目消费 Objective-C-compatible Pods；
- 生成本地 podspec 与 Xcode script phase；
- 把 Kotlin 项目作为本地 Pod 接入 Xcode；
- 生成用于 CocoaPods 分发的 XCFramework 与 podspec 任务。

这就是为什么“团队更喜欢 SwiftPM”不能自动替代 CocoaPods：

如果 Kotlin 源码依赖一个只能通过 CocoaPods 稳定接入的库，依赖方向已经影响构建系统。

同时，官方明确提醒：用 CocoaPods plugin 连接 KMP framework 的方式，不能与 `embedAndSignAppleFrameworkForXcode` direct integration 同时使用。

两条链路都会试图负责“怎样把 Kotlin framework 放进 Xcode build”，并存会产生双重所有权。

PocketLab 没有 Pod 依赖，所以当前继续 direct integration；这比为了文章演示把现有 Xcode 工程强行改成 Pods 更符合真实工程判断。

## SwiftPM 与 CocoaPods 应该怎么选？

先问这些问题，而不是问谁更现代：

| 问题 | 更偏向 direct | 更偏向 SwiftPM binary | 更偏向 CocoaPods |
|---|---|---|---|
| Kotlin 与 iOS 是否同仓、一起改 | 是 | 否 | 两者都可 |
| iOS 开发是否需要调试 Kotlin 源码 | 经常 | 很少 | 本地 Pod 可 |
| 是否要像第三方 SDK 一样版本化 | 否 | 是 | 是 |
| 团队是否已有 SwiftPM 依赖治理 | 不关键 | 是 | 否 |
| Kotlin 是否消费不可替代 Pod | 否 | 通常否 | 是 |
| 是否已有私有 Specs repo/Pod pipeline | 不关键 | 否 | 是 |
| 是否愿意维护 ZIP、checksum、Git tag | 否 | 是 | Pod source/spec 对应流程 |
| 是否希望 iOS 机器不安装 Gradle/JDK | 否 | 是 | 远程 binary Pod 也可以 |

PocketLab 的决策树是：

```text
消费者会修改/调试共享 Kotlin 吗？
├─ 会 → direct integration
└─ 不会
   ├─ Kotlin 必须消费 CocoaPods dependencies？
   │  ├─ 是 → CocoaPods plugin / Pod workflow
   │  └─ 否
   │     ├─ iOS 团队已有强 Pod 治理？
   │     │  ├─ 是 → binary Pod 也合理
   │     │  └─ 否 → XCFramework + SwiftPM binary target
```

这棵树允许不同团队得到不同答案。

专家能力不是背出唯一推荐，而是能指出改变答案的变量。

## 为什么不把 40 MB XCFramework 提交进 Git？

二进制 artifact 与源码仓库有不同生命周期：

- artifact 由特定工具链、配置和输入生成；
- Git diff 无法有效审查二进制内部变化；
- 每次版本会永久放大仓库；
- 源码 tag 与 artifact release 可以建立一一对应；
- CI 应能从 tag 重建、签名、检查并上传。

PocketLab 只提交：

- Gradle XCFramework 配置；
- Swift package manifest template；
- CocoaPods podspec publishing example；
- smoke source；
- 可执行 distribution gate。

生成的 `.xcframework`、ZIP、checksum 和临时 package 全部留在 ignored `build/`。

真正发布时，它们应进入 release asset / artifact storage，而不是普通源码 commit。

## 多个 KMP 模块怎么办？

假设未来拆成：

```text
:domain
:network
:database
:apple-api
```

不要本能地给 iOS App 四个 Kotlin/Native frameworks。

更常见的做法是建立 umbrella framework：

```kotlin
appleTarget.binaries.framework {
    export(project(":domain"))
    export(project(":apple-api"))
}
```

只有 source set 中的 `api` dependency 才能被 `export`。

而且 export 默认不传递；`transitiveExport = true` 会扩大 API、禁用更多 dead-code elimination，并增加编译时间和体积，官方不建议无脑打开。

这与上一篇的 API allowlist 是同一个原则：

> umbrella 是显式产品边界，不是把所有内部模块打成一锅。

下一篇模块化会真正拆开这组边界。

## 静态二进制不等于没有依赖责任

Kotlin/Native final binary 可能包含第三方代码、数据或派生内容。

即使消费者只看见一个静态 framework，也必须处理：

- Ktor、SQLDelight、coroutines 等依赖的许可证；
- required system frameworks 与 linker flags；
- 资源文件是否需要随 App bundle 复制；
- privacy manifest、符号文件和崩溃符号化资产；
- 多个 Kotlin frameworks 带来的 runtime/符号冲突风险。

“依赖已经静态链接，所以不用管了”在法律和工程上都不成立。

本实验尚未包含资源 bundle 与 dSYM 发布；这不是被忽略，而是明确列入后续 CI/可观测性里程碑的未完成项。

## 一条真正有用的分发门禁

PocketLab 新增：

```shell
scripts/verify-apple-distribution.sh
```

它不会只运行 assemble task，而会继续验证：

1. `ios-arm64` device slice 存在；
2. Simulator slice 同时包含 `x86_64` 与 `arm64`；
3. 两个 framework 都是 static archive；
4. bundle identifier 与版本正确；
5. Swift-friendly 名称仍在 header；
6. `ExperimentRecord`、sealed result 和内部构造器没有重新泄漏；
7. Swift 6 strict-concurrency 能从 XCFramework 的 Simulator slice 导入 API；
8. 本地 Swift binary package manifest 能被 SwiftPM 描述；
9. ZIP 能生成，并能计算远程分发 checksum。

门禁输出：

```text
Apple distribution contract passed.
Device architectures: arm64
Simulator architectures: x86_64 arm64
Swift package: .../build/apple-distribution/SwiftPackage
Remote artifact checksum: <sha256>
```

这条门禁把“我们应该支持 Apple Silicon”从 README 愿望改成了机器可检查事实。

## 构建成功仍然不等于 App 成功

本次完整证据链还包括：

```text
common / Android / iosX64 tests
        ↓
Debug + Release framework
        ↓
Release XCFramework slices
        ↓
Swift API smoke type-check
        ↓
Swift package metadata parse
        ↓
Xcode build + iOS UI runtime test
```

XCFramework gate 证明 artifact 结构正确；XCTest 才证明当前 App 仍能冷启动、读数据库、刷新并保持幂等。

不能拿其中一层代替全部。

## 七个常见错误

### 1. “有 `.framework` 就能发给所有 iOS 工程”

先检查 platform、variant、architecture、build type 与 linkage。

### 2. “把所有架构 lipo 在一起就行”

`arm64 device` 与 `arm64 Simulator` 架构名相同、平台 ABI 不同。使用 XCFramework metadata 区分。

### 3. “SPM 和 XCFramework 二选一”

SwiftPM binary target 通常分发的正是 XCFramework。

### 4. “CocoaPods 只能发源码”

Podspec 的 `vendored_frameworks` 支持 `.framework` 与 `.xcframework`。

### 5. “同一个版本的 ZIP 可以覆盖更新”

SwiftPM checksum 会拒绝；版本化 artifact 应不可变。

### 6. “XCFramework 文件夹 40 MB，App 就增加 40 MB”

目录包含多个候选 slice。测最终 Release App、thinning 与 link map。

### 7. “远程二进制能让 iOS 团队更轻松，所以本地也只用远程”

远程分发降低工具要求，也提高 Kotlin 修改、调试和发布反馈成本。保留 local development workflow。

## 发布前检查清单

### 产物

- [ ] device 与 Simulator variants 都存在；
- [ ] Intel/Apple Silicon Simulator 支持范围与团队设备一致；
- [ ] Debug artifact 没有误发成 Release；
- [ ] static/dynamic 选择与宿主嵌入方式一致；
- [ ] module name、bundle identifier、版本稳定；
- [ ] header/API diff 已审查；
- [ ] 许可证、资源、privacy、dSYM 策略已明确。

### SwiftPM

- [ ] XCFramework 在 ZIP 根目录；
- [ ] binary target 名称匹配 module；
- [ ] URL 是直接、稳定、不可变下载；
- [ ] checksum 对最终 ZIP 计算；
- [ ] Package.swift 已在干净环境解析；
- [ ] Git semantic version tag 已推送。

### CocoaPods

- [ ] 明确是 local KMP Pod 还是 remote binary Pod；
- [ ] 不与 direct integration 双重接管 Xcode build；
- [ ] deployment target 与 vendored framework 路径正确；
- [ ] podspec 在干净 consumer/lint 流程验证；
- [ ] Specs repo/source archive 的不可变策略明确。

### 运行

- [ ] 真机 slice 至少完成链接验证；
- [ ] 当前主机 Simulator 完成 App/UI 测试；
- [ ] Apple Silicon CI 执行 `iosSimulatorArm64Test`；
- [ ] Xcode 最新/最低支持版本都有消费者验证；
- [ ] 回滚到上一 artifact version 的流程可执行。

## 本次验证矩阵

代码行为测试数没有因为多打两个架构就虚增：

| 验证 | 结果 |
|---|---:|
| JVM shared | 39/39 |
| Android Host shared | 39/39 |
| iOS x64 shared / interop | 47/47 |
| Android ViewModel | 2/2 |
| Android Compose UI | 2/2 |
| iOS XCTest UI | 1/1 |
| 不同测试场景 | 54 |
| 自动化执行总数 | 130/130 |

新增的是交付证据：

| 交付验证 | 结果 |
|---|---|
| iosArm64 Release framework | 通过 |
| iosSimulatorArm64 Release framework | 通过 |
| iosX64 Release framework | 通过 |
| Release XCFramework assembly | 通过 |
| device/simulator architecture gate | 通过 |
| bundle metadata gate | 通过 |
| Swift 6 XCFramework import smoke | 通过 |
| local Swift package parse | 通过 |
| ZIP checksum generation | 通过 |
| CocoaPods podspec metadata parse | 通过 |

当前 Intel host 不能执行 `iosSimulatorArm64Test`。这不是被静默忽略，而是明确的 CI 缺口：arm64 framework 已交叉编译，arm64 Simulator runtime test 必须由 Apple Silicon runner 补齐。

## 写在最后

把 Kotlin 代码交给 iOS，从来不只是“生成一个 framework”。

你需要连续回答：

```text
什么 API 被导出？
        ↓
为哪些平台和架构链接？
        ↓
怎样装进可选择的 artifact？
        ↓
谁负责版本、下载和完整性？
        ↓
消费者怎样在本地开发、远程发布与回滚？
```

PocketLab 这次得到的最重要结论不是“SwiftPM 赢了”。

而是：

> 本地集成和远程分发是两种不同优化目标；Framework、XCFramework 与包管理器属于不同层。先分层，再选择，最后用消费者构建证明。

下一篇，我们会把目前偏大的 `shared` 拆成真正的模块边界。届时会出现一个更难的问题：Android 可以直接依赖多个 KMP modules，为什么 iOS 往往更适合只看见一个 umbrella framework？

## 留给你的动手挑战

不要只停在“我知道 XCFramework 是什么”。请给自己的 KMP 模块做一次可回滚的 Apple 二进制交付演练：

1. 分别生成 device 与 Simulator 的 Release framework，并用 `lipo -info` 或 `file` 记录每个 slice 的架构；
2. 把它们组装成 XCFramework，再用一个最小 Swift 6 consumer 导入公开 API，证明产物不是“打包成功但无法消费”；
3. 压缩产物、计算 SwiftPM checksum，然后故意改动 ZIP 中一个字节，观察完整性校验为什么必须失败。

最后写下你的选择：本地开发使用 direct integration、远程发布使用 SwiftPM binary，还是由 CocoaPods 接管？答案里必须同时包含团队约束、失败场景和回滚路径。能解释这些取舍，比记住三条打包命令更接近真正掌握 Apple 分发。

## 官方资料

- [Kotlin Multiplatform：iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)
- [Kotlin Multiplatform：Direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [Kotlin Multiplatform：Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Kotlin Multiplatform：Swift package export setup](https://kotlinlang.org/docs/native-spm.html)
- [Kotlin Multiplatform：CocoaPods overview and setup](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-overview.html)
- [Kotlin Multiplatform：Use a Kotlin project as a CocoaPods dependency](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-xcode.html)
- [Apple：Creating a multi-platform binary framework bundle](https://developer.apple.com/documentation/xcode/creating-a-multi-platform-binary-framework-bundle)
- [Apple：Distributing binary frameworks as Swift packages](https://developer.apple.com/documentation/xcode/distributing-binary-frameworks-as-swift-packages)
- [Apple：`binaryTarget(name:url:checksum:)`](https://developer.apple.com/documentation/packagedescription/target/binarytarget%28name%3Aurl%3Achecksum%3A%29)
- [CocoaPods Podspec Syntax Reference](https://guides.cocoapods.org/syntax/podspec.html)
