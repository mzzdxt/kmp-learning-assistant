# ADR-0011：本地 direct integration，远程 XCFramework + SwiftPM

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab KMP Apple targets、二进制容器与 iOS 依赖分发

## 背景

PocketLab 已通过 `embedAndSignAppleFrameworkForXcode` 把 `iosX64` static framework 接入同仓 SwiftUI App。这条路径支持快速修改和调试 Kotlin，但只覆盖 Intel Simulator，也没有远程 artifact 的版本、完整性与下载协议。

后续需要同时满足：

- Intel 与 Apple Silicon Simulator；
- arm64 iPhone/iPad device/Archive；
- 同仓 Kotlin/Swift 联合开发；
- 跨仓、不可变、可回滚的二进制交付；
- Swift-friendly API surface 继续受门禁保护；
- 不为了演示包管理器破坏当前可工作的 Xcode workflow。

Framework、XCFramework、SwiftPM 与 CocoaPods 属于不同层次，不能用一个“选哪个”问题替代架构决策。

## 决策

### Apple targets 与 source sets

- 支持 `iosX64`、`iosSimulatorArm64` 与 `iosArm64`；
- 共同 Apple implementation 从 `iosX64Main` 提升为 `iosMain`；
- Apple-specific tests 从 `iosX64Test` 提升为 `iosTest`；
- 当前 Intel host 运行 `iosX64Test`；Apple Silicon CI 必须补 `iosSimulatorArm64Test`；
- 不用 `EXCLUDED_ARCHS` 或静默 ignore 把缺失 target 伪装成支持。

### Framework

- module/base name 固定为 `PocketLabShared`；
- 当前使用 static framework；
- bundle identifier 固定为 `dev.kmpjourney.pocketlab.shared`；
- `CFBundleShortVersionString` 与 `CFBundleVersion` 必须由发布门禁核对；
- Debug framework 用于开发，远程 artifact 只发布 Release。

### XCFramework

- 使用 Kotlin Gradle Plugin `XCFramework("PocketLabShared")` 组装；
- device slice 为 `ios-arm64`；
- Simulator slice 同时包含 `arm64` 与 `x86_64`；
- 不用一个 fat framework 混合 device 与 Simulator platform variants；
- XCFramework 与 ZIP 作为 CI/release artifacts，不提交到源码 Git。

### 本地开发

- 同仓 SwiftUI App 继续使用 `embedAndSignAppleFrameworkForXcode` direct integration；
- Kotlin 修改不经过 binary release 循环；
- Xcode UI runtime test 继续作为本地集成证据；
- 不同时用 CocoaPods plugin 接管同一个 KMP framework 的 Xcode integration。

### 远程分发

- 默认采用 XCFramework + SwiftPM binary target；
- XCFramework ZIP 位于不可变、可直接下载的 release URL；
- checksum 从最终 ZIP 计算；
- Swift package Git tag 使用 semantic version；
- 同一版本 artifact 不覆盖更新；有变化必须发布新版本；
- 发布后在无本地 Gradle/Kotlin cache 的消费者环境验证。

### CocoaPods

- CocoaPods 是保留选项，不是默认远程路径；
- Kotlin 必须消费 Pod dependencies，或 iOS 团队已有强 Pod 治理时重新评估；
- local KMP Pod 与 remote binary Pod 是不同 workflow；
- binary pod 使用 `vendored_frameworks` 指向 XCFramework；
- 当前 podspec 只作为 publishing example，source URL 上传前不视为已发布。

### 多模块演进

- iOS 侧优先只消费一个明确的 umbrella framework；
- 只有 Apple 产品 API 需要的 `api` dependencies 才显式 `export`；
- 默认不启用 `transitiveExport`；
- 模块化不得重新扩大 ADR-0010 已收窄的 Swift API surface。

### 自动门禁

`scripts/verify-apple-distribution.sh` 必须验证：

- 三个 Release framework 能构建；
- device 与 Simulator slices/architectures 正确；
- framework linkage 为 static；
- bundle identifier/version 正确；
- Swift-friendly header names 保留且内部类型不泄漏；
- Swift 6 能从 XCFramework type-check smoke source；
- local Swift package manifest 可解析；
- ZIP 与 SwiftPM checksum 可生成。

Podspec example 另用 `pod ipc spec` 解析；App 行为另由 Xcode UI test 证明。

## 当前证据

### 构建

- `assemblePocketLabSharedReleaseXCFramework` 首次冷构建成功；
- iosArm64、iosSimulatorArm64、iosX64 均完成 Release compile/link；
- 当前 Intel host 首次 XCFramework 构建约 8 分 24 秒；
- `iosX64Test` 在 source set 迁移后保持 47/47。

### Artifact

- device identifier：`ios-arm64`，architecture `arm64`；
- Simulator identifier：`ios-arm64_x86_64-simulator`；
- Simulator architectures：`x86_64 arm64`；
- 两个 binary 均由 `file` 识别为 static archive；
- 两个 headers 均为 2,267 行，SHA-256 一致；
- framework bundle 版本为 `0.13.0` / build `13`；
- XCFramework 约 40 MB，ZIP 约 12 MB。

### Consumer metadata

- SwiftPM path-based binary package 能 `dump-package` 与 `describe`；
- Swift 6 complete strict-concurrency smoke source 能从 Simulator slice 导入；
- ZIP 能由 `swift package compute-checksum` 生成 checksum；
- binary podspec 能由 CocoaPods 1.16.2 解析为 JSON。

### 负面证据

- `lipo` 明确拒绝合并 device arm64 与 Simulator arm64，因为 architecture 重复；
- Intel host 明确禁用 `iosSimulatorArm64Test`，因此 runtime coverage 尚需 Apple Silicon CI。

## 后果

### 正面

- 真机与两类 Simulator 架构成为显式构建契约；
- 日常开发不承担远程发布循环；
- 远程消费者不必安装 Gradle/JDK/Kotlin toolchain；
- artifact version、architecture、API 与 checksum 都有自动证据；
- SwiftPM/CocoaPods 的选择由团队事实驱动，不与 XCFramework 容器混淆；
- 为下一步 umbrella module 与 CI release pipeline 建立稳定出口。

### 成本

- Apple target 数量从一个增至三个，冷 Release 构建显著变慢；
- XCFramework 同时携带多个候选 slice，artifact storage 增加；
- remote release 需要 ZIP hosting、Git tag、checksum 与消费者验证；
- static framework 的许可证、资源、privacy 与符号资产仍需单独发布策略；
- Intel 本地无法执行 arm64 Simulator tests，需要 Apple Silicon runner；
- 版本最初写在 Gradle DSL；[ADR-0018](0018-machine-checked-production-release-contract.md) 已用 `release.properties` 统一 Android、KMP framework 与 Xcode generator，并由 production contract 检查生成项目 drift。

## 被拒绝的方案

### 只发布 `iosX64` framework

不能支持真机与 Apple Silicon Simulator，因此拒绝。

### 用 `EXCLUDED_ARCHS=arm64` 绕开 Simulator 问题

会删除 Apple Silicon 原生支持并掩盖 artifact 缺口，因此拒绝。

### 用 `lipo` 合并 device 与 Simulator arm64

二者 platform ABI 不同且 architecture 重复，工具已明确拒绝，因此拒绝。

### 所有本地开发都通过远程 binary package

会把每次 Kotlin 修改变成发布/下载循环，损害调试与贡献体验，因此拒绝。

### 把 XCFramework 直接提交到主源码 Git

二进制无法有效 review、永久膨胀仓库且混淆源码/artifact 生命周期，因此拒绝。

### 同时启用 direct integration 与 CocoaPods KMP integration

两条链路会同时接管 framework build/embed，所有权冲突，因此拒绝。

### 因“SwiftPM 更现代”永久排除 CocoaPods

忽略 Kotlin 侧 Pod dependencies 与既有团队基础设施，因此拒绝绝对化判断。

## 重新评估条件

- Kotlin 源码需要消费不可替代的 CocoaPods dependency；
- iOS 团队要求统一进入现有 private Specs repo；
- 需要 dynamic framework、App Extension 或多宿主复用；
- Apple Silicon 成为唯一支持 host，可移除 `iosX64`；
- Kotlin 2.5+ SwiftPM import/export 改变依赖闭包；
- Swift Export 支持稳定 remote distribution；
- 模块化产生多个 KMP modules，需要 umbrella export；
- artifact 体积、链接时间或启动指标成为实际瓶颈；
- 需要公开 SDK、签名、notarization、SBOM、privacy manifest 或 dSYM 服务。

## 验证

- `scripts/verify-apple-distribution.sh` 通过；
- `pod ipc spec distribution/apple/PocketLabShared.podspec` 通过；
- iOS x64 shared tests 47/47；
- 全行为矩阵保持 54 个不同场景、130/130 次执行；
- Debug/Release framework、Release XCFramework 与 Xcode UI runtime 均需在提交前继续通过。
