# KMP 从入门到专家：一套会长大的学习实验室

这个仓库服务于一件事：让一名熟悉 Android 与 Kotlin 的开发者，通过真实项目逐步掌握 Kotlin Multiplatform，并把每个阶段沉淀成能发布在公众号上的文章。

> 名称说明：你熟悉的 **KMM（Kotlin Multiplatform Mobile）** 仍常被开发者口头使用；现在官方更常用 **KMP（Kotlin Multiplatform）**。KMP 不只覆盖 Android 与 iOS，还可面向桌面、Web 与服务端。本系列聚焦移动端，但统一使用 KMP 这个名称。

## 现在从哪里开始

1. 阅读 [学习路线图](docs/roadmap.md)，先理解我们为什么按这个顺序学。
2. 阅读第一篇文章：[KMM 到底是什么：同一份 Kotlin，为什么能跑在 Android 和 iOS？](articles/01-what-is-kmp.md)。
3. 完成 [实验 01：在写代码前画出跨平台边界](labs/01-boundary-before-code/README.md)。
4. 阅读第二篇文章：[一张图看懂 KMP 工程：target、compilation 和 source set](articles/02-project-model.md)。
5. 运行 [实验 02：让同一组规则接受 JVM、Android 与 iOS 检验](labs/02-first-kmp-module/README.md)。
6. 阅读第三篇文章：[commonMain 不是公共垃圾场：哪些代码值得共享？](articles/03-sharing-boundary.md)。
7. 完成 [实验 03：为 PocketLab 划出第一条共享边界](labs/03-sharing-boundary/README.md)。
8. 阅读第四篇文章：[`expect/actual` 像插座，但别把整栋楼焊在上面](articles/04-expect-actual.md)。
9. 完成 [实验 04：把 `expect/actual` 限制在墙上的插座](labs/04-expect-actual-socket/README.md)。
10. 阅读第五篇文章：[第一条跨越 Android 与 iOS 的数据流](articles/05-first-shared-data-flow.md)。
11. 完成 [实验 05：第一条共享状态流](labs/05-first-shared-data-flow/README.md)。
12. 阅读第六篇文章：[Ktor：同一个请求，在三套网络引擎上发生了什么？](articles/06-ktor-engines.md)。
13. 完成 [实验 06：同一个请求，三套 Ktor 引擎](labs/06-ktor-network/README.md)。
14. 阅读第七篇文章：[离线优先：不是“加一层缓存”，而是重新决定谁说了算](articles/07-offline-first.md)。
15. 完成 [实验 07：让数据库成为离线世界的事实来源](labs/07-offline-first/README.md)。
16. 阅读第八篇文章：[Coroutines 遇见 Swift Concurrency：一条 Flow 过桥后，为什么忘了怎么取消？](articles/08-coroutines-meet-swift-concurrency.md)。
17. 完成 [实验 08：让 Kotlin Job 和 Swift Task 真正握手](labs/08-coroutines-swift-concurrency/README.md)。
18. 阅读第九篇文章：[Android Compose + SwiftUI：UI 不共享也能合作愉快](articles/09-native-ui.md)。
19. 完成 [实验 09：用 Compose 与 SwiftUI 展示同一份离线事实](labs/09-native-ui/README.md)。
20. 阅读第十篇文章：[KMP 测试金字塔：一次验证，两端真的能放心吗？](articles/10-testing-pyramid.md)。
21. 完成 [实验 10：为 KMP 建立能定位风险的测试金字塔](labs/10-testing-pyramid/README.md)。
22. 阅读第十一篇文章：[Kotlin/Native 深水区：没有 JVM 之后，内存和并发到底谁管？](articles/11-kotlin-native-runtime.md)。
23. 完成 [实验 11：拆开 Kotlin/Native 的编译、内存与并发边界](labs/11-kotlin-native-runtime/README.md)。
24. 阅读第十二篇文章：[为 Swift 设计 Kotlin API：能导出，不等于好用](articles/12-designing-kotlin-api-for-swift.md)。
25. 完成 [实验 12：把“能从 Swift 调用”改造成“像 Swift 一样使用”](labs/12-swift-api-design/README.md)。
26. 阅读第十三篇文章：[Framework、XCFramework、SPM 与 CocoaPods：你的 Kotlin 代码究竟怎么送到 iOS？](articles/13-apple-framework-distribution.md)。
27. 完成 [实验 13：把 KMP framework 变成可验证的 Apple 分发物](labs/13-apple-distribution/README.md)。
28. 阅读第十四篇文章：[KMP 模块化：共享多少，由谁决定？](articles/14-kmp-modularization.md)。
29. 完成 [实验 14：把万能 shared 拆成可执行的依赖边界](labs/14-kmp-modularization/README.md)。
30. 阅读第十五篇文章：[KMP 构建为什么慢：别先加内存，先把 4 分 37 秒拆开](articles/15-build-performance.md)。
31. 完成 [实验 15：把“构建很慢”拆成可以验证的假设](labs/15-build-performance/README.md)。
32. 阅读第十六篇文章：[KMP 出问题时，谁来作证：从一条 628 ms 日志到可符号化崩溃](articles/16-cross-platform-observability.md)。
33. 完成 [实验 16：让跨平台故障留下同一条证据链](labs/16-observability/README.md)。
34. 阅读第十七篇文章：[别把现有 App 一夜搬进 commonMain：一条可回滚的 KMP 迁移路线](articles/17-incremental-kmp-adoption.md)。
35. 完成 [实验 17：给现有 App 留一条可回滚的 KMP 迁移缝](labs/17-incremental-adoption/README.md)。
36. 阅读第十八篇文章：[KMP、Flutter、React Native 与纯原生：别只比较代码复用率](articles/18-mobile-strategy-decision.md)。
37. 完成 [实验 18：别先选框架，先写不能妥协的约束](labs/18-mobile-strategy/README.md)。
38. 阅读第十九篇文章：[Compose Multiplatform 要不要上？一份决策清单](articles/19-compose-multiplatform-decision.md)。
39. 完成 [实验 19：先共享一张卡片，再决定要不要共享整个 UI](labs/19-compose-multiplatform/README.md)。
40. 把疑问和实验结论写进 [学习日志](notes/learning-journal.md)，它们会成为后续文章最真实的素材。

## 仓库结构

```text
.
├── articles/   # 可发布的公众号文章
├── docs/       # 路线、术语、架构决策与参考资料
├── labs/       # 每个阶段的可运行实验
└── notes/      # 学习日志、问题与复盘
```

## 学习方法

每个主题都走同一个闭环：

```text
已有 Android 经验 → 提出跨平台问题 → 最小实验 → Android/iOS 对照
                  → 解释编译与运行原理 → 总结工程边界 → 写成文章
```

“会创建模板项目”不是终点。专家能力体现在：能解释生成物、能选择共享边界、能诊断双端问题、能设计可演进的 API，也能判断什么时候不该共享。

## PocketLab 当前进度

真实项目已经从“保存实验草稿”用例启动：

- [产品简报](docs/pocketlab-product-brief.md)
- [ADR-0001：首个功能的共享边界](docs/adr/0001-pocketlab-sharing-boundary.md)
- [ADR-0002：用 expect/actual 创建薄平台适配器](docs/adr/0002-platform-adapter-factories.md)
- [ADR-0003：用 StateFlow 表达草稿编辑状态](docs/adr/0003-draft-editor-stateflow.md)
- [ADR-0004：共享请求语义，按平台选择 Ktor 引擎](docs/adr/0004-ktor-network-boundary.md)
- [ADR-0005：以本地数据库为事实来源，并用 SQLDelight 保持三端可验证](docs/adr/0005-offline-first-sqldelight.md)
- [ADR-0006：保留 Kotlin 原生异步模型，在 Apple 边界显式桥接取消](docs/adr/0006-swift-concurrency-bridge.md)
- [ADR-0007：原生 UI 配合平台 ViewModel，共享数据与业务内核](docs/adr/0007-native-ui-platform-viewmodels.md)
- [ADR-0008：按风险分层，并在每个支持目标重新执行共同测试](docs/adr/0008-testing-strategy.md)
- [ADR-0009：采用现代 Native 内存模型，并显式治理共享状态与跨语言生命周期](docs/adr/0009-native-memory-concurrency.md)
- [ADR-0010：以 Apple facade 和 Swift value wrapper 管理导出 API](docs/adr/0010-swift-api-surface.md)
- [ADR-0011：本地 direct integration，远程 XCFramework + SwiftPM](docs/adr/0011-apple-binary-distribution.md)
- [ADR-0012：领域模块独立，shared 作为唯一 Apple umbrella](docs/adr/0012-domain-module-and-apple-umbrella.md)
- [ADR-0013：用分层基线和可验证能力治理构建性能](docs/adr/0013-evidence-based-build-performance.md)
- [ADR-0014：用固定事件契约和构建身份定位跨平台故障](docs/adr/0014-cross-platform-observability.md)
- [ADR-0015：用宿主契约和纵向切片渐进接入 KMP](docs/adr/0015-host-owned-seams-for-incremental-adoption.md)
- [ADR-0016：PocketLab 继续使用 KMP 共享逻辑并保留原生 UI](docs/adr/0016-keep-kmp-native-ui-for-pocketlab.md)
- [ADR-0017：Compose Multiplatform 只作为独立叶子组件候选](docs/adr/0017-bound-compose-multiplatform-adoption.md)
- [实验 03 与边界矩阵](labs/03-sharing-boundary/README.md)
- [实验 04 与平台适配器](labs/04-expect-actual-socket/README.md)
- [实验 05 与共享状态流](labs/05-first-shared-data-flow/README.md)
- [实验 06 与 Ktor 平台引擎](labs/06-ktor-network/README.md)
- [实验 07 与离线优先数据库](labs/07-offline-first/README.md)
- [实验 08 与 Swift Concurrency 取消桥](labs/08-coroutines-swift-concurrency/README.md)
- [实验 09 与 Compose / SwiftUI 原生界面](labs/09-native-ui/README.md)
- [实验 10 与多平台测试金字塔](labs/10-testing-pyramid/README.md)
- [实验 11 与 Kotlin/Native runtime 边界](labs/11-kotlin-native-runtime/README.md)
- [实验 12 与 Swift-friendly API surface](labs/12-swift-api-design/README.md)
- [实验 13 与 Apple 二进制分发](labs/13-apple-distribution/README.md)
- [实验 14 与 KMP 模块边界](labs/14-kmp-modularization/README.md)
- [实验 15 与构建性能证据](labs/15-build-performance/README.md)
- [实验 16 与跨平台诊断链](labs/16-observability/README.md)
- [实验 17 与渐进迁移宿主契约](labs/17-incremental-adoption/README.md)
- [实验 18 与移动技术路线决策模型](labs/18-mobile-strategy/README.md)
- [实验 19 与 Compose Multiplatform 共享 UI 决策](labs/19-compose-multiplatform/README.md)
- 领域代码：`labs/02-first-kmp-module/domain/src/commonMain/kotlin/dev/kmpjourney`
- 基础设施与组合代码：`labs/02-first-kmp-module/shared/src/commonMain/kotlin/dev/kmpjourney/pocketlab`
- Apple 互操作代码：`labs/02-first-kmp-module/shared/src/iosMain/kotlin/dev/kmpjourney/pocketlab/interop`
- Android Compose 壳：`labs/02-first-kmp-module/androidApp`
- iOS SwiftUI 壳：`labs/02-first-kmp-module/iosApp`
- 领域测试：`labs/02-first-kmp-module/domain/src/commonTest/kotlin/dev/kmpjourney`
- 基础设施与集成测试：`labs/02-first-kmp-module/shared/src/commonTest/kotlin/dev/kmpjourney/pocketlab`

最近一次完整移动工程验证中，`domain + shared` 在 JVM 与 Android Host 各聚合运行 50 条测试，iOS x64 聚合运行 58 条，共计 158/158；其中 domain 在三个目标各运行 23 条，shared 分别运行 27/27/35。Android unit 6/6、Compose UI 2/2，当前 production 可运行矩阵为 166/166。仓库定义 69 个 production 场景、167 次目标执行；剩余 1 条是 iOS UI smoke，上一完整基线为 1/1，但当前 Xcode 26.5 未安装匹配的 iOS 26.5 runtime，旧 17.5–18.4 devices 被判为不可用，因此没有把 destination 发现失败伪装成 UI 通过。独立 `sharedUi` spike 另有 Android host contract tests 2/2；它生成 Android AAR、iOS device/Simulator arm64 Release frameworks，并通过 Swift 6 strict consumer，但 Intel host 不能运行 arm64 Simulator test。本轮没有让 `sharedUi` 进入 mobile binary，App 仍为 0.17.0/build 17。

49 个共同场景在两个 production modules 中按所有权分组，并各自在三个目标重复执行；场景数与执行数分别表达行为覆盖和目标兼容证据。模块门禁会禁止 domain 导入平台、Ktor、SQLDelight 或反向依赖 shared，并证明 Kotlin consumer 通过 `api` 看见 domain、Apple framework 却不 export domain；Apple header 从 2,267 行降至 888 行。共同层集成测试继续穿过 demo remote、Refresh UseCase、真实 SQLDelight Repository、transaction 与 Flow；Native 专用实验覆盖 GC 引用环、Swift callback/cancellation、Apple snapshot 与稳定 failure report。Apple API gate 会检查 Swift names、隐藏实现类型与 `swift_private` callbacks，并用 Swift 6 complete strict concurrency 编译完整 SwiftUI source。渐进迁移门禁确保 Android/Swift ViewModel 与 UI 只依赖宿主契约，KMP 类型收口于 adapter/composition root，legacy fixture 不进入生产且 runtime dual write 为 0。移动技术路线门禁用 11 个 criteria、hard gates 和四个场景证明 KMP、Flutter、React Native 与纯原生都能在不同约束下胜出，PocketLab 当前选择 KMP + 原生 UI，但不把场景分数伪装成通用排名。Compose shared UI 门禁把一张叶子卡片隔离在 `sharedUi`，验证 Compose Multiplatform 1.11.1 与现有 AGP 8.13.2/API 36 共存，Apple header 157 行且 presentation model 泄漏为 0；最新版 1.12.2 所需 Android build-chain 升级和 Apple x86_64 移除都被记录为采用成本。Apple distribution gate 构建 `iosArm64`、`iosSimulatorArm64`、`iosX64` Release frameworks，核对 static linkage、0.17.0/build 17 metadata、XCFramework slices、Swift binary package 与 ZIP checksum。构建性能门禁验证 Gradle build/configuration cache、Kotlin text/JSON build reports 与 benchmark harness；可观测性门禁验证固定隐私 allowlist schema、monotonic duration、R8 9.1.29 mapping、APK/mapping 构建身份、Native binary/dSYM UUID 和 Kotlin 函数恢复。分层基线仍把约 1 秒的 warm JVM feedback、19.8 秒的单架构 Native Debug forced build 和多架构 Release distribution 分开解释。SQLDelight migration verifier、Android minified Release、Debug/Release Apple framework 和 Release XCFramework 继续完成构建。

## 当前环境快照

- macOS 26.7.3
- JDK 17.0.10
- Xcode 26.5
- Android SDK（已安装并用于编译的 API 36）
- 项目使用 Gradle Wrapper 8.14.4，无需预装 Gradle
- 尚未检测到 Android Studio

实验 02 已能通过命令行完成 JVM、Android Host 与 iOS x64 测试。安装 Android Studio 与 Kotlin Multiplatform IDE 插件后，可获得工程同步、跨 source set 导航和运行配置等完整 IDE 体验。

## 权威资料

- [Kotlin Multiplatform 概览](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
- [Kotlin Multiplatform Quickstart](https://kotlinlang.org/docs/multiplatform/quickstart.html)
- [KMP 工程结构](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html)
- [调用平台 API](https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)
- [expect / actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Ktor Client engines](https://ktor.io/docs/client-engines.html)
- [Ktor Client serialization](https://ktor.io/docs/client-serialization.html)
- [SQLDelight Multiplatform SQLite](https://sqldelight.github.io/sqldelight/2.4.0/multiplatform_sqlite/)
- [Room for Kotlin Multiplatform](https://developer.android.com/kotlin/multiplatform/room)
- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Kotlin/Native memory management](https://kotlinlang.org/docs/native-memory-manager.html)
- [Kotlin/Native 与 Swift/Objective-C ARC](https://kotlinlang.org/docs/native-arc-integration.html)
- [迁移到现代 Kotlin/Native memory manager](https://kotlinlang.org/docs/native-migration-guide.html)
- [Kotlin Swift Export](https://kotlinlang.org/docs/native-swift-export.html)
- [Kotlin/Native Objective-C API refinement](https://kotlinlang.org/docs/native-objc-interop.html#hide-kotlin-declarations-from-objective-c-and-swift)
- [Kotlin Multiplatform iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)
- [Kotlin/Native final binaries 与 XCFramework](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Kotlin Swift package export](https://kotlinlang.org/docs/native-spm.html)
- [Kotlin CocoaPods integration](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-overview.html)
- [KMP 多模块工程配置](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)
- [KMP 推荐工程结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP 默认 source set hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [Gradle Build Performance](https://docs.gradle.org/current/userguide/performance.html)
- [Gradle Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Kotlin compilation and caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)
- [Kotlin/Native compilation time](https://kotlinlang.org/docs/native-improving-compilation-time.html)
- [Apple AsyncStream](https://developer.apple.com/documentation/swift/asyncstream)
- [Kotlin Multiplatform 运行测试](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html)
- [Android Compose 测试](https://developer.android.com/develop/ui/compose/testing)
- [Apple Testing with Xcode](https://developer.apple.com/documentation/xcode/testing)

