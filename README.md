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
24. 把疑问和实验结论写进 [学习日志](notes/learning-journal.md)，它们会成为后续文章最真实的素材。

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
- [实验 03 与边界矩阵](labs/03-sharing-boundary/README.md)
- [实验 04 与平台适配器](labs/04-expect-actual-socket/README.md)
- [实验 05 与共享状态流](labs/05-first-shared-data-flow/README.md)
- [实验 06 与 Ktor 平台引擎](labs/06-ktor-network/README.md)
- [实验 07 与离线优先数据库](labs/07-offline-first/README.md)
- [实验 08 与 Swift Concurrency 取消桥](labs/08-coroutines-swift-concurrency/README.md)
- [实验 09 与 Compose / SwiftUI 原生界面](labs/09-native-ui/README.md)
- [实验 10 与多平台测试金字塔](labs/10-testing-pyramid/README.md)
- [实验 11 与 Kotlin/Native runtime 边界](labs/11-kotlin-native-runtime/README.md)
- 生产代码：`labs/02-first-kmp-module/shared/src/commonMain/kotlin/dev/kmpjourney/pocketlab`
- Apple 互操作代码：`labs/02-first-kmp-module/shared/src/iosX64Main/kotlin/dev/kmpjourney/pocketlab/interop`
- Android Compose 壳：`labs/02-first-kmp-module/androidApp`
- iOS SwiftUI 壳：`labs/02-first-kmp-module/iosApp`
- 共享测试：`labs/02-first-kmp-module/shared/src/commonTest/kotlin/dev/kmpjourney/pocketlab`

目前 JVM 与 Android Host 各运行 39 条测试，iOS x64 运行 45 条测试，共享模块合计 123/123；Android ViewModel 2 条、Compose UI 2 条、iOS UI 1 条，总计 128/128 次自动化执行。按源码统计共有 52 个不同测试场景，其中 38 个 `commonTest` 在三个目标重复执行；两组数字分别表达行为覆盖与目标兼容证据。共同层集成测试已穿过 demo remote、Refresh UseCase、真实 SQLDelight Repository、transaction 与 Flow；新增并发实验让两个 dispatcher 通过 `Mutex` 安全修改同一对象，Native 专用实验用弱引用验证纯 Kotlin 强引用环可被 tracing GC 回收。Android Compose semantics 测试在 API 34 模拟器通过；iOS XCTest 则真实冷启动 SwiftUI App，验证 3 条数据库记录与幂等刷新。SQLDelight migration verifier、Android APK、Debug/Release Apple framework 和 Swift 6 Xcode App 继续完成构建。

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
- [Apple AsyncStream](https://developer.apple.com/documentation/swift/asyncstream)
- [Kotlin Multiplatform 运行测试](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html)
- [Android Compose 测试](https://developer.android.com/develop/ui/compose/testing)
- [Apple Testing with Xcode](https://developer.apple.com/documentation/xcode/testing)

