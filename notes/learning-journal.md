# KMP 学习日志

## 使用方式

每次实验只记录四件事：

1. 我原来以为……
2. 实验实际发生了……
3. 原因是……
4. 这会如何影响工程决策……

这些“预期与事实的落差”是最值得写进公众号的内容。

---

## 2026-11-17：起点

### 已有基础

- Android 开发
- Kotlin

### 当前认知

- 知道 KMM 能用于 Android 和 iOS。
- 尚不清楚编译原理、工程结构与使用方式。

### 第一批待回答问题

- [ ] iOS 为什么能调用 Kotlin？
- [ ] 哪些代码能进入 `commonMain`？
- [ ] `expect/actual` 和接口注入如何选择？
- [ ] 共享 UI 与原生 UI 如何选择？
- [ ] Kotlin 协程如何与 Swift 并发协作？
- [ ] 共享模块如何测试、打包和发布？

### 实验 01 记录

待完成：[在写代码前画出跨平台边界](../labs/01-boundary-before-code/README.md)

---

## 2026-11-18：第一个可运行 KMP 模块

### 我原来以为

- “iOS target” 是一个笼统目标，模板里的 `iosSimulatorArm64()` 应该哪里都能用。
- Kotlin、AGP 和 Gradle 分别落在兼容表范围内，就可以任意组合。
- 测试任务长时间没有输出，大概率是测试逻辑挂死。

### 实验实际发生了

- 当前 host 是 x86_64，`iosSimulatorArm64` 被禁用，必须使用 `iosX64`。
- AGP 9.3.1 + Gradle 8.13 在配置阶段失败；AGP 9.3 自己要求 Gradle 9.5 以上。
- iOS 测试在 Simulator `dyld` 装载阶段等待约 3 分钟，真正的 5 条测试仅执行约 14 ms。
- `:shared:allTests` 最终聚合 JVM、Android Host、iOS x64 三个目标，共 15 次测试执行，全部通过。

### 原因

- target 包含平台和体系结构，不只是操作系统名称。
- 版本兼容是多条边组成的图，不是只看 Kotlin 的一张表。
- compilation、链接、Simulator 启动和测试代码执行是不同阶段，必须用任务输出、进程栈与 XML 报告分别取证。

### 对工程决策的影响

- 固定并记录整套工具链版本，升级时同时核对 Kotlin↔Gradle、Kotlin↔AGP、AGP↔Gradle、Kotlin↔Xcode。
- CI runner 的 CPU 架构是构建配置的一部分。
- 不把 `BUILD SUCCESSFUL` 当成唯一证据；关键测试应检查生成的测试报告和用例数量。

对应实验：[实验 02](../labs/02-first-kmp-module/README.md)  
对应文章：[一张图看懂 KMP 工程](../articles/02-project-model.md)

---

## 2026-11-18：PocketLab 的第一条共享边界

### 我原来以为

- 跨平台能力应该优先用 `expect/actual`，看起来更“KMP”。
- Repository 的实现还没选好数据库，就不能先写保存用例。
- 共享校验最好直接返回错误文案，平台拿来就能展示。

### 实验实际发生了

- Repository、Clock、ID Generator 用普通 Kotlin 接口即可在 commonTest 注入 fake，无需 `expect/actual`。
- 保存用例只需要“保存已验证记录”这个能力，数据库选型可以延后。
- 共享层返回 field + reason code，Android/iOS 可各自映射本地化文案。
- PocketLab 新增 6 条 commonTest，在 JVM、Android Host、iOS 分别执行，实际新增 18 次测试；累计 33/33 通过。

### 原因

- 共享边界由语义和变化原因决定，不由代码能否编译决定。
- 普通接口允许一个平台有多个实现，也容易替换测试 fake。
- 文案属于本地化与 UI 呈现；稳定错误语义才属于共同业务规则。

### 对工程决策的影响

- 先设计领域需要的最小能力，再选择数据库、网络和系统 API。
- 有状态、需替换的能力优先使用接口注入；薄而唯一的平台事实才考虑 `expect/actual`。
- 测试失败路径是否触发副作用，不能只测试“输入字符串是否为空”。

对应产品简报：[PocketLab](../docs/pocketlab-product-brief.md)  
对应决策记录：[ADR-0001](../docs/adr/0001-pocketlab-sharing-boundary.md)  
对应实验：[实验 03](../labs/03-sharing-boundary/README.md)  
对应文章：[commonMain 不是公共垃圾场](../articles/03-sharing-boundary.md)

---

## 2026-11-18：`expect/actual` 只做薄平台接缝

### 我原来以为

- 用了普通接口，就不需要或不应该再用 `expect/actual`。
- iOS SDK 头文件中的 Objective-C 属性，在 Kotlin/Native 中一定能用直觉相同的属性语法访问。
- `expect/actual` 的测试重点只是“每个平台能编译”。

### 实验实际发生了

- 领域继续依赖普通 `Clock`/`IdGenerator` 接口；`expect/actual` 只负责创建默认生产 adapters，两者可以互补。
- `NSDate().timeIntervalSince1970` 在当前 compilation 无法解析；改用 Core Foundation C API 后通过。
- commonTest 不仅验证 actual 存在，还验证时间处于合理 Unix epoch 区间、ID 非空且不同、平台工厂能完成保存。
- 新增 3 条契约在三个 targets 实际执行 9 次，累计 42/42 通过。

### 原因

- 普通接口解决对象替换和运行时组合；`expect/actual` 解决 target 的编译期声明匹配。
- Objective-C/C API 会经过 cinterop 映射，Kotlin 侧可见声明才是编译事实。
- 能链接不代表语义正确；时间单位、纪元和 ID 行为仍需契约测试。

### 对工程决策的影响

- 把 expected declaration 表面积压到最小，优先使用函数/属性工厂。
- 领域用例绝不直接读取系统时钟或 UUID，继续保留 deterministic fake。
- 平台互操作遇到问题时检查 SDK 头文件、Kotlin 声明与最小编译实验，不靠记忆猜方法名。

对应决策记录：[ADR-0002](../docs/adr/0002-platform-adapter-factories.md)  
对应实验：[实验 04](../labs/04-expect-actual-socket/README.md)  
对应文章：[`expect/actual` 像插座](../articles/04-expect-actual.md)

---

## 2026-11-18：第一条共享 StateFlow

### 我原来以为

- `suspend` 函数会自动切到后台线程。
- 共享状态持有者应该自己创建 Main Scope，平台拿来即用。
- 只要最终状态是 SAVED，就不必测试中间状态。

### 实验实际发生了

- Repository 与 UseCase 改成 suspend，但没有硬编码任何 dispatcher。
- `DraftEditorStore` 接受平台宿主注入的 Scope，并只公开只读 StateFlow。
- 保存用原子状态转换进入 SAVING，第二次提交返回 false。
- commonTest 收集并验证 `IDLE → SAVING → SAVED`，还覆盖 REJECTED、FAILED 和字段编辑。
- 5 条新测试在三个 targets 执行 15 次，累计 57/57 通过。

### 原因

- suspend 表达“可暂停并恢复”，不表达运行线程。
- Scope 是生命周期所有权；共享对象不知道 Android Screen 或 SwiftUI View 何时销毁。
- 声明式 UI 依赖状态序列，竞态通常发生在中间状态而非最终值。

### 对工程决策的影响

- Scope 和 dispatcher 都显式注入或由基础设施层负责，不创建不可控的全局协程。
- StateFlow 用于可重建的当前状态；一次性事件另行建模。
- 协程测试使用虚拟时间，不通过真实 sleep 等待异步结果。
- Swift 消费 StateFlow 的体验仍未解决，后续必须单独评估桥接，而不能把“可导出”当成“好用”。

对应决策记录：[ADR-0003](../docs/adr/0003-draft-editor-stateflow.md)  
对应实验：[实验 05](../labs/05-first-shared-data-flow/README.md)  
对应文章：[第一条跨越 Android 与 iOS 的数据流](../articles/05-first-shared-data-flow.md)

---

## 2026-11-18：同一个请求，三套 Ktor 引擎

### 我原来以为

- Ktor 跨平台意味着 Android 与 iOS 使用同一套底层网络实现。
- 只要 catch 所有 Exception 并返回“网络错误”，UI 就足够简单。
- 网络测试越真实越好，最好直接请求公网。
- serialization compiler plugin 与 JSON runtime 应该使用相同版本号。

### 实验实际发生了

- 请求、Content Negotiation、JSON DTO 映射和错误分类位于 commonMain；JVM、Android、iOS 分别装配 CIO、Android、Darwin engine。
- 401、503、418、坏 JSON、传输异常和取消需要不同处理；取消必须继续抛出。
- MockEngine 在 commonTest 精确验证 URL、method、状态码与解码，不需要外网。
- compiler plugin 跟随 Kotlin 2.4.21，kotlinx.serialization JSON runtime 独立使用 1.11.0。
- 8 条新测试在三个 targets 执行 24 次，累计 81/81 通过；每个 target 的真实平台引擎也完成创建与关闭验证。

### 原因

- Ktor 统一客户端请求管线，engine 才负责进入具体平台网络栈。
- HTTP 失败说明服务器已返回响应；坏 JSON 说明协议不兼容；传输失败说明未得到可用响应；取消则是结构化并发控制信号。
- 单元测试应控制输入并精确声明证明范围，真实 TLS/权限/服务端契约属于后续集成测试。
- 编译器插件负责生成 serializer，runtime 提供格式实现，它们是独立发布物。

### 对工程决策的影响

- 公共数据源返回稳定结果类型，不返回 HttpResponse，也不泄漏 Ktor/平台异常。
- DTO 保持网络实现私有，通过映射隔离服务端 schema 与领域模型。
- HttpClient 作为长生命周期资源复用并显式关闭，不在每次请求时创建。
- engine 依赖只放进平台 source set；共同代码只保留薄工厂和共享配置。
- MockEngine、fake data source 与真实测试服务器分别验证不同边界，不能互相冒充。

对应决策记录：[ADR-0004](../docs/adr/0004-ktor-network-boundary.md)  
对应实验：[实验 06](../labs/06-ktor-network/README.md)  
对应文章：[Ktor：同一个请求，在三套网络引擎上发生了什么？](../articles/06-ktor-engines.md)

---

## 2026-11-19：数据库成为唯一事实来源

### 我原来以为

- 离线优先就是网络失败时读取一份缓存。
- Room 已支持 KMP，所以当前 Intel iOS Simulator 也一定能使用。
- 远端刷新成功后，先把 response 塞进 StateFlow，再顺便写数据库即可。
- 时间戳更新者胜足以解决同步冲突。

### 实验实际发生了

- 数据库 Flow 成为 UI 唯一读取路径；本地保存和远端刷新都只修改数据库。
- Room 3.0.3 有 Android、JVM、iosArm64 和 iosSimulatorArm64 variant，却没有当前所需 iosX64；SQLDelight 2.4.1 的 Native driver 仍提供 iosX64。
- 本地保存立即持久化为 `PENDING_UPLOAD`；刷新事务永远不覆盖 pending 记录。
- 已同步记录只接受更新时间更大的远端版本；批量合并只发布完整事务快照。
- 9 条新测试在三个 targets 执行 27 次，累计 108/108 通过；migration verifier 通过。

### 原因

- 缓存只描述读取优化；offline-first 必须定义事实来源、mutation 持久性和同步恢复。
- KMP 依赖解析匹配具体 target variant，“支持 iOS”不代表覆盖所有 Apple 架构。
- 网络 response 是输入，数据库提交后的查询结果才是可恢复事实。
- pending 状态保护尚未上传的用户意图；客户端时间戳仍不能表达多设备并发因果。

### 对工程决策的影响

- UI 不在 cache 与 response 之间切换，刷新失败也不清空本地列表。
- 库选型必须结合 host、target、variant 和可运行测试，而不是只看平台宣传页。
- 同步意图进入持久数据；将来删除使用 tombstone/outbox，不能直接删行。
- 批量 merge 必须在 transaction 中完成，并测试观察者看不到中间状态。
- 从 v1 提交 schema 基线，未来每次变更同时提交 migration 与数据语义测试。
- 迁移到 Apple Silicon 后重新比较 Room 3 与 SQLDelight，不把这次 target 约束变成永久教条。

对应决策记录：[ADR-0005](../docs/adr/0005-offline-first-sqldelight.md)  
对应实验：[实验 07](../labs/07-offline-first/README.md)  
对应文章：[离线优先：不是“加一层缓存”，而是重新决定谁说了算](../articles/07-offline-first.md)

---

## 2026-11-19：让 Kotlin Job 和 Swift Task 真正握手

### 我原来以为

- Kotlin `suspend` 能在 Swift 中写成 `async/await`，取消也会自然传播。
- Swift 只要拿到 `Flow`，就能像 Kotlin 一样收集。
- callback bridge 能返回数据就够了，生命周期稍后再处理。
- Swift 示例写法看起来合理，就不必真的交给 Swift 编译器。

### 实验实际发生了

- 标准 Kotlin/Native framework 的 header 仍把 suspend 导出为 completion handler；Swift 5.5+ importer 额外提供 async 语法。
- 原始 `Flow<List<ExperimentRecord>>` 只导出为协程 Flow protocol，无法直接获得带元素类型的 Swift `AsyncSequence`。
- Apple source set 新增显式 bridge，每次订阅或刷新返回持有 Kotlin Job 的 `NativeCancellable`。
- Swift 使用 `AsyncThrowingStream` 恢复 `for try await`，并在 `onTermination` 中取消 Kotlin Job。
- refresh 分开表达稳定领域结果和意外基础设施异常；取消不会触发完成或失败 callback。
- 5 条 iOS 专用测试通过；framework 成功链接；Swift 6 完整严格并发 type-check 通过。
- 三端累计 113/113 测试通过，migration verifier 继续通过。

### 原因

- Kotlin Job 与 Swift Task 属于不同运行时的任务树，语法映射不等于取消关系映射。
- 标准 Objective-C protocol 无法完整表达 Flow 的元素泛型与 collection 语义。
- 持续异步工作需要明确的单项取消与整体关闭，不能等待 ARC/GC 间接清理。
- `AsyncStream.onTermination` 是 Swift Task 生命周期通知 Kotlin collection 的标准接缝。
- Swift 6 的 Sendable 与 actor 检查只对真正编译的 Swift 生效，Markdown 代码不能作为证据。

### 对工程决策的影响

- commonMain 保持 Flow / suspend，callback 只存在于 Apple 适配边界。
- 取消作为一等协议测试：Task cancel → stream termination → token cancel → Job cancel。
- 生产 callback 固定在 Kotlin Main dispatcher，Swift 状态继续使用 `@MainActor`。
- generated header 与 Swift type-check 进入互操作门禁。
- Swift Export 已能原生导出 Flow 为 AsyncSequence，但 2026-11 仍是 Alpha；建立 iOS direct-integration 壳后再做并行评估。
- API 数量增长后再用数据比较手写桥、KMP-NativeCoroutines、SKIE 与 Swift Export。

对应决策记录：[ADR-0006](../docs/adr/0006-swift-concurrency-bridge.md)  
对应实验：[实验 08](../labs/08-coroutines-swift-concurrency/README.md)  
对应文章：[Coroutines 遇见 Swift Concurrency](../articles/08-coroutines-meet-swift-concurrency.md)

---

## 2026-11-19：两套原生 UI，共享一份事实

### 我原来以为

- 既然选择 KMP，就应该尽量连 ViewModel 和 UI 一起共享，否则复用率不够高。
- Android Compose 与 SwiftUI 各写一遍 ViewModel，会自然演变成两套业务逻辑。
- Swift wrapper 通过单文件严格并发 type-check，就足以证明能进入真实 App。
- Kotlin framework 成功链接后，Xcode App 最终链接不会再有 KMP 相关问题。
- AndroidX 库全部选最新稳定版，通常就是最安全的组合。

### 实验实际发生了

- Android 使用 AndroidX ViewModel + Compose，iOS 使用 `@MainActor ObservableObject` + SwiftUI；两端 ViewModel 只翻译生命周期和呈现状态，刷新、数据库与合并规则仍只有一份。
- Android 通过 `collectAsStateWithLifecycle` 收集，iOS 通过 `.task` + AsyncSequence 收集；离开页面时取消沿 Swift Task → stream → Kotlin token → Job 传播。
- 真实 Swift 6 Xcode target 报告 Kotlin `ExperimentRecord` 在 `Continuation.yield` 发送点不是 `Sendable`；最终用一个经 Main dispatcher / MainActor 审计的窄 `UncheckedSendable` 盒子解决，没有降低整个项目并发检查等级。
- 静态 `PocketLabShared.framework` 内的 SQLiter 调用要求最终 App 显式链接 `libsqlite3`；添加 `-lsqlite3` 后才完成链接。
- 最新 Compose BOM 2026.09.00 与 Lifecycle 2.11.0 要求 compileSdk 37 / AGP 9.1，超出当前 AGP 8.13.2 工具链；改用 Compose BOM 2026.06.01 与 Lifecycle 2.10.0 后兼容。
- Android App 在 Pixel 2 API 34 模拟器冷启动成功；iOS App 在 iPhone 15 Pro iOS 17.5 模拟器启动成功；两端均显示共享 demo remote 经 SQLDelight 写入的 3 条记录。
- 共享模块 JVM 37、Android Host 37、iOS 42 条测试通过，Android ViewModel 2 条测试通过，总执行数 118/118。

### 原因

- 平台 ViewModel 可以复用业务能力而不复用生命周期框架；重复的是薄翻译层，不是离线和同步决策。
- KMP 经 Objective-C 导出的类型没有 Swift `Sendable` 元数据；`@preconcurrency` 也不会自动证明线程安全。
- 静态 framework 的系统库依赖由最终可执行文件链接，framework 自己生成成功不代表消费端输入完整。
- Android 依赖兼容是 Kotlin、AGP、Gradle、compileSdk、Compose 与 Lifecycle 组成的图，不是一列最新版本。
- build、Swift compile、final link、install 与 launch 是五个不同门禁。

### 对工程决策的影响

- 当前保留平台 ViewModel 与原生 UI，等真实重复度增长后再评估共享 ViewModel 或 Compose Multiplatform。
- 两端 UI 一律观察数据库事实来源，不直接消费 remote response。
- FFI `@unchecked Sendable` 只能位于有明确线程/actor 契约的窄边界，并在契约变化时重新审计。
- Xcode direct integration、系统 linker flags、Swift 6 build 与 simulator launch 都进入 Apple 验证清单。
- Android 依赖升级先检查官方 release requirements，再由 AAR metadata 与完整构建验证，不通过 suppress 躲避。
- 运行截图只证明 UI 路径存在，不能替代共享规则、平台 ViewModel 和 migration 测试。

对应决策记录：[ADR-0007](../docs/adr/0007-native-ui-platform-viewmodels.md)

对应实验：[实验 09](../labs/09-native-ui/README.md)

对应文章：[Android Compose + SwiftUI：UI 不共享也能合作愉快](../articles/09-native-ui.md)


---

## 2026-11-19：一次编写测试，不等于只执行一次

### 我原来以为

- `commonTest` 跑绿一次，就可以把它算作 Android 与 iOS 都验证过。
- 端到端测试最真实，重要分支都应该尽量从 UI 走一遍。
- 测试数量只有一种口径，报告总数越大越安全。
- UI 异步测试等一两秒最省事。

### 实验实际发生了

- 37 个共同场景分别进入 JVM、Android Host 与 iOS x64，形成 111 次执行；共同源码只有一份，运行证据有三份。
- 新增共同集成测试，真实穿过 demo remote、Refresh UseCase、SQLDelight Repository、transaction 与 Flow；第一次刷新写入 3 条完整快照，第二次刷新变化数为 0。
- Android Compose 新增 2 条 instrumentation 测试，在 API 34 模拟器验证 state 渲染与刷新 intent。
- iOS 新增 XCTest UI target，使用唯一数据库冷启动真实 SwiftUI App，验证 3 条记录、首次同步提示和幂等刷新。
- 当前共有 50 个不同场景、124/124 次自动化执行；这两个数字回答不同问题。
- Xcode 26.5 一度只列出 generic destination，但显式 boot iOS 18.4 device 并传入 id 后，UI test 1/1 通过。

### 原因

- `commonTest` 是共享测试源码，最终仍由各 target 的 compilation、runner、driver 与运行时执行。
- 单元、集成、平台和 UI 测试覆盖不同故障面，真实性不是越高越能替代下层精确断言。
- 场景数描述行为定义，执行数描述目标矩阵；重复运行是兼容证据，不是新增业务场景。
- 固定 sleep 等待时间而不是条件，会同时制造浪费与偶发失败。

### 对工程决策的影响

- 每个支持 target 必须实际运行共同测试，JVM 绿灯不能代替 Native 证据。
- 单元测试 fake 端口，共同集成保留真实数据库接缝，UI 只覆盖少量高价值可见行为。
- Android 平台状态与 Compose semantics 由 Android 测试拥有；Swift bridge 与 SwiftUI 装配由 Apple 测试拥有。
- 测试报告同时给出不同场景数与目标执行数，并写清口径。
- Kotlin 使用虚拟调度器，Compose 使用 test synchronization，XCTest 使用 `waitForExistence`；timeout 只是失败上限。
- CI 将按快速 PR、平台 PR、主分支 UI smoke 和发布/定时车道拆分，而不是只有一个巨大的 `allTests`。

对应决策记录：[ADR-0008](../docs/adr/0008-testing-strategy.md)

对应实验：[实验 10](../labs/10-testing-pyramid/README.md)

对应文章：[KMP 测试金字塔：一次验证，两端真的能放心吗？](../articles/10-testing-pyramid.md)


---

## 2026-11-19：没有 JVM，不等于没有 runtime

### 我原来以为

- Kotlin/Native 只是把 Kotlin 代码翻译成 Swift 或 Objective-C。
- 现代 Native 允许对象跨线程后，并发安全也随之解决。
- ARC 和 GC 都会处理引用环，所以 Swift/Kotlin 混合对象迟早能自动释放。
- framework 文件大小可以直接代表最终 App 增量。

### 实验实际发生了

- iOS 编译先产生包含序列化 IR、metadata 与 target 信息的 KLIB，再由 LLVM、Kotlin runtime 和依赖链接成 static framework；它不是 Swift 源码。
- 本机 KLIB manifest 明确记录 `builtins_platform=NATIVE`、`native_targets=ios_x64` 与 Kotlin 2.4.21 compiler version。
- Debug / Release iosX64 framework 都成功链接；前者约 67 MB、后者约 12 MB，但二者仍是静态归档链接输入，不能冒充最终 App 体积。
- 一个 `commonTest` 让两个独立 dispatcher 并发调用同一个 ledger；用 `Mutex` 串行化后，200 次写入完整且计数连续。
- 一个 iosX64 专用测试构造纯 Kotlin 双向强引用环，丢弃 root 后调用 `GC.collect()`，弱引用变为 `null`。
- 新增 2 个不同场景后，JVM 39/39、Android Host 39/39、iOS x64 45/45；连同平台测试总计 128/128 次执行。

### 原因

- Kotlin/Native 不依赖 JVM，却仍携带对象模型、调度与 tracing GC 等 Kotlin runtime 能力。
- 现代 memory manager 使用共享 heap；它移除了旧 freeze 限制，却没有移除 data race。
- tracing GC 根据 Kotlin 对象图的可达性回收；ARC 根据引用计数释放 Swift/Objective-C 对象。混合强环横跨两套所有权系统，双方都可能看到对方仍“活着”。
- framework 还会在 Xcode 最终链接时裁剪、合并依赖并受架构和符号设置影响，所以只能对最终 Release App 做大小归因。

### 对工程决策的影响

- 新代码禁止恢复 `freeze()`、`@SharedImmutable` 和 legacy `native-mt` 依赖；遇到旧资料先核对现代 memory manager 时间线。
- 共享可变状态必须明确同步机制或唯一 owner；“能访问”不作为并发安全论据。
- Swift callback、delegate、closure 与 Kotlin owner 的引用方向进入 code review 清单，混合环用 `weak`、`unowned` 或显式取消协议断开。
- GC 负责回收不可达内存，不负责停止 Job、注销监听或关闭数据库与网络资源；生命周期协议继续显式化。
- 性能问题先采集 GC 日志、safepoint signpost 与 Instruments 证据，再调整 binary options 或内存结构。

对应决策记录：[ADR-0009](../docs/adr/0009-native-memory-concurrency.md)

对应实验：[实验 11](../labs/11-kotlin-native-runtime/README.md)

对应文章：[Kotlin/Native 深水区：没有 JVM 之后，内存和并发到底谁管？](../articles/11-kotlin-native-runtime.md)


---

## 2026-11-20：framework 能导出，不代表 Swift 愿意使用

### 我原来以为

- Kotlin 声明是 `public`，自然就应该进入 Apple framework。
- Swift name 好看以后，API 就已经 Swift-friendly。
- Kotlin sealed result 到 Swift 后仍然能穷尽 `switch`。
- 给 Kotlin payload 套一个通用 `@unchecked Sendable`，Swift 6 问题就解决了。
- Swift Export 功能越来越强，可以立刻替换 Objective-C framework 路径。

### 实验实际发生了

- 原始 2,834 行 header 暴露 `ExperimentRecord`、共同 sealed result、bridge 组装依赖和 raw callback。
- Apple DTO 与 KDoc 先让 header 增至 2,888 行；用 `internal` / `@HiddenFromObjC` 收窄类型图后降到 2,267 行。
- 只隐藏 `ExperimentLocalStore` 而保留公开实现类时，Native 编译器因可见性继承图不一致而失败；隐藏实现类后恢复。
- `@ObjCName` 让 Swift 看见 `ExperimentListClient`、`PocketLabFeature`、`CancellationToken`，Objective-C symbol 仍保持唯一前缀。
- `@ShouldRefineInSwift` 让 raw callback 变成 `swift_private`；Swift wrapper 仍可通过 `__subscribe` / `__refresh` 使用。
- Kotlin snapshot 被立即复制成 `Experiment: Identifiable, Sendable`，业务 payload 不再需要 unchecked wrapper。
- Kotlin report 被映射成可穷尽 `RefreshOutcome`，failure 使用稳定 code 和 `.unknown` 退化。
- 新增 2 个 iOS API mapping 场景后，iOS x64 达到 47/47；全矩阵共有 54 个不同场景、130/130 次执行。

### 原因

- Kotlin visibility、Objective-C export visibility 与 Swift 产品 API 是三套不同决策。
- Objective-C protocol/class 无法完整表达 Kotlin sealed hierarchy、nullable primitive 和所有 generic 语义。
- `@unchecked Sendable` 只关闭检查，不创造隔离；复制为 Swift value 才恢复静态保证。
- API 兼容不仅是 symbol 存在，还包括 Swift source name、参数标签、未知值、错误、取消和生命周期。
- Swift Export 当前已支持更多原生映射，但仍是 Alpha；工具能力与发布风险需要用同一组门禁比较。

### 对工程决策的影响

- Apple API 采用显式 allowlist 心智模型；内部类型默认不导出。
- Swift 只消费 Apple snapshot/report 与平台 value/enum，不直接依赖数据库 record 或共同 sealed hierarchy。
- raw callbacks 保留为隐藏实现原语，公开体验由 AsyncSequence/async wrapper 提供。
- stable failure code 必须有 unknown 路径；平台文案不从 Kotlin diagnostic message 推导。
- `@unchecked Sendable` 只允许出现在受锁保护的 cancellation box 等窄 FFI 边界。
- `scripts/verify-apple-api.sh`、Swift 6 strict type-check、adapter tests 和 UI runtime 共同构成发布证据。
- 未来拆出独立 `apple-api` module，并平行评估 Swift Export，不在当前版本直接切换。

对应决策记录：[ADR-0010](../docs/adr/0010-swift-api-surface.md)

对应实验：[实验 12](../labs/12-swift-api-design/README.md)

对应文章：[为 Swift 设计 Kotlin API：能导出，不等于好用](../articles/12-designing-kotlin-api-for-swift.md)


---

## 2026-11-20：一个 framework 文件夹，装不下所有 Apple 消费者

### 我原来以为

- 生成 `.framework` 后，就能直接交给所有 iOS 工程。
- XCFramework、SwiftPM 与 CocoaPods 是三个互斥的集成方案。
- `lipo` 能把所有 iOS 架构合进一个万能 framework。
- XCFramework 有 40 MB，最终 App 就会增加 40 MB。
- 远程 binary package 能减少环境配置，所以本地开发也应该只用远程包。

### 实验实际发生了

- 只有 `iosX64` 时，framework 只能服务 Intel Simulator；新增了 `iosArm64` 与 `iosSimulatorArm64`。
- Apple implementation/test 从 `iosX64Main` / `iosX64Test` 提升到 `iosMain` / `iosTest`，三种 targets 才能共享完整平台实现。
- `assemblePocketLabSharedReleaseXCFramework` 首次冷构建约 8 分 24 秒，真实生成 device arm64 与 universal Simulator 两个 slices。
- Simulator binary 包含 `x86_64 arm64`，device binary 为 `arm64`；两者都是 static archive。
- 直接用 `lipo` 合并两个 arm64 变体失败，因为 device 与 Simulator 架构名相同、platform ABI 不同。
- 两个 slice 的 header 都是 2,267 行且 SHA-256 一致；完整 XCFramework 约 40 MB，ZIP 约 12 MB。
- SwiftPM local binary package 能解析，ZIP 能生成 checksum；binary podspec example 能被 CocoaPods 解析。
- distribution gate 能从 XCFramework type-check Swift 6 smoke source，并拒绝架构、版本和 API surface 回归。

### 原因

- Framework 是具体 platform/variant/architecture/build type/linkage 的二进制变体。
- Fat framework 只在同一平台内组合不同 CPU；XCFramework 用 metadata 区分 device 与 Simulator variants。
- SwiftPM/CocoaPods 负责版本、下载、完整性与消费者集成，通常承载的仍是 XCFramework。
- XCFramework 同时保存多个候选 slices，目录体积不等于最终 App 选中的机器码。
- 本地 direct integration 优化修改与调试反馈；远程 binary distribution 优化仓库隔离与消费者工具要求。

### 对工程决策的影响

- 同仓 SwiftUI App 继续使用 `embedAndSignAppleFrameworkForXcode`，远程发布采用 Release XCFramework + SwiftPM。
- 生成 artifact 不提交源码 Git；由 CI 从 tag 构建、检查、压缩、计算 checksum 并上传。
- 同一版本的 remote ZIP 不可覆盖；artifact 改变必须发布新 semantic version。
- CocoaPods 不因 SwiftPM 存在而被永久排除；Kotlin Pod dependencies 或现有 Specs pipeline 会触发重新评估。
- Intel host 的 arm64 Simulator test 缺口被明确记录，后续 Apple Silicon CI 必须补齐。
- 多模块阶段优先建立一个显式 export allowlist 的 umbrella framework，避免多个 Kotlin runtimes/API surfaces 无序进入 iOS。

对应决策记录：[ADR-0011](../docs/adr/0011-apple-binary-distribution.md)

对应实验：[实验 13](../labs/13-apple-distribution/README.md)

对应文章：[Framework、XCFramework、SPM 与 CocoaPods：你的 Kotlin 代码究竟怎么送到 iOS？](../articles/13-apple-framework-distribution.md)

---

## 2026-11-20：shared 能共享，不代表所有共享代码属于同一个模块

### 我原来以为

- source set 和 package 已经把领域、基础设施与平台代码分开，再建 Gradle module 只是整理目录。
- `shared` 对 `domain` 使用 `api(project(...))` 后，domain API 会自然进入 Apple framework。
- 有两个 KMP modules，就应该给 iOS 生成两个 frameworks。
- 把 use case test 搬进 domain 即可，继续使用真实 SQLDelight 会更可靠。
- 模块越多、header 越短，架构就越成熟。

### 实验实际发生了

- 新建 `:domain` 后，规则、模型、端口、用例与状态机能在不依赖 Ktor、SQLDelight 或平台 API 的前提下独立编译测试；package 名无需改变。
- `shared` 使用 `api(project(":domain"))` 后，Android App 仍只依赖 shared 并能编译 domain 类型；Apple framework 没有 `export(project(":domain"))`，所以 Swift 看不到 domain declarations。
- `domain` 声明三种 iOS targets 但不生成 framework；`shared` 继续作为唯一 umbrella，内部链接 domain KLIB。
- `RefreshExperimentsUseCaseTest` 改用 `ExperimentLocalStore` 内存 fake；真实 SQLDelight repository 与端到端刷新仍在 shared tests 覆盖。
- domain 在 JVM/Android Host/iOS x64 各通过 18 条；shared 分别通过 21/21/29，聚合仍为 39/39/47。
- 仓库仍定义 54 个不同场景、130 次目标执行；本轮重跑 129/129，iOS UI 上一完整基线为 1/1，但 Xcode 26.5 缺少匹配 runtime，不能把 destination 发现失败写成通过。
- Apple header 从 2,267 行降到 888 行，domain rule/use-case/port/state symbols 为 0，Apple facade 保持存在。
- header 仍含 SQLDelight 生成声明，说明 infrastructure 仍与 framework producer 同 module；它成为有证据的后续候选，而不是被忽略。

### 原因

- source set 决定 target 复用，package 决定命名空间，Gradle module 才能执行依赖、编译和发布边界。
- Gradle `api` 管理 Kotlin consumer classpath；Kotlin/Native `export` 单独管理 framework API。
- 多个 Kotlin/Native frameworks 可能复制依赖、runtime、状态与类型；内部多模块通常由一个 umbrella 交付给 iOS。
- 端口 fake 验证 use case orchestration，真实 repository/integration tests 验证技术接缝，两层证据不能互相冒充。
- 模块有配置、任务、ABI、CI 和所有权成本；是否继续拆分必须由依赖方向、变更扩散与测量结果决定。

### 对工程决策的影响

- domain 禁止平台、Ktor、SQLDelight 和反向 shared 依赖，并由 `verify-module-boundaries.sh` 自动执行。
- Kotlin consumer 与 Apple consumer 使用不同 API 契约：前者保留完整 domain，后者只消费 Apple facade。
- Apple framework 只保留一个 umbrella；默认不 export domain，不启用 `transitiveExport`。
- `StateFlow` 出现在 domain public API，因此 coroutines 是 domain 的 `api` dependency；依赖可见性由公开签名决定。
- 模块迁移同时记录测试聚合、header allow/deny 和 consumer compile，避免只凭目录变化宣布成功。
- 只有 SQLDelight header 泄漏、build profile 或团队所有权证明收益时，再拆 infrastructure/apple-api。

对应决策记录：[ADR-0012](../docs/adr/0012-domain-module-and-apple-umbrella.md)

对应实验：[实验 14](../labs/14-kmp-modularization/README.md)

对应文章：[KMP 模块化：共享多少，由谁决定？](../articles/14-kmp-modularization.md)

---

## 2026-11-20：构建慢不是结论，而是一组尚未拆开的成本

### 我原来以为

- Release XCFramework 要 4 分多钟，说明 KMP 日常开发一定很慢。
- build cache 开启后 wall time 明显下降，就能把全部收益归因给 cache。
- configuration cache 是另一种编译产物缓存，打开后所有构建都会更快。
- 私有实现改动只会增量编译那一个文件。
- 模块化之后，所有改动都不会传播到下游。
- 给 Gradle 更多 heap、打开 parallel/Native incremental 总不会有坏处。

### 实验实际发生了

- `help` 的 configuration-cache reuse 平均 916 ms，对照 disabled 1,245 ms，约低 26.4%。
- 日常 JVM no-op reuse 平均 1,024 ms，对照 disabled 1,663 ms，约低 38.4%；但第一次 store 为 4,532 ms，反而更慢。
- 第一轮 clean/cache 实验看似从 23.077 s 降到 11.911 s，日志却只有 1 个 `FROM-CACHE`，结论被判无效并重测。
- 受控 warm restore 为 1.510 s，明确出现 28 个 `FROM-CACHE`；outputs 保留时 no-op 为 1.039 s，47 个 tasks `UP-TO-DATE`。
- 单一 `iosX64` Debug forced rerun 为 19.80 s；Kotlin tasks 占全部 task time 的 91.5%，framework link task 约 9.955 s。
- 私有等价实现改动让 domain compilation 执行，shared/App compilation 保持 up-to-date；但 Kotlin report 因 KT-62686 明确执行 domain 非增量 fallback。
- 新增 public method 后，domain、shared 与 Android App 相关 compilation 全部执行，49 tasks 中 17 executed；公共 ABI 正确沿依赖图传播。
- build-performance gate 成功验证 build/configuration cache、Kotlin text/JSON reports 与重复任务 cache reuse。

### 原因

- configuration cache 保存配置后的 task graph/state；build cache 保存 task outputs；up-to-date 直接使用仍在工作区的 outputs；incremental compiler 则在 compile task 内少做工作。
- wall time 同时受 daemon、JIT、OS file cache、task selection、cache state 与后台负载影响，不能单独证明因果。
- KMP 会为多个 targets 分别编译 common source；Release XCFramework 还包含多 architecture、优化、链接、组装和 consumer gates。
- 模块/classpath snapshot 能隔离不改变 ABI 的实现改动，但真实公共契约变化必须传播。
- experimental flags 可能带来正确性、兼容性、Release runtime 或二进制体积取舍，必须单独 A/B。

### 对工程决策的影响

- 日常反馈、Android、Apple Debug、合并矩阵和 Release distribution 分成不同 task lanes。
- 默认保留 up-to-date outputs，不把 `clean` 当构建仪式。
- 性能记录必须同时保存场景、cache state、wall time 与 task outcomes；证据不足的结果作废重测。
- 开启 configuration cache 和 Kotlin file/JSON reports，并用 fail-mode 门禁防止升级后静默退化。
- 不因缺少证据而增大 heap、打开 Beta Native incremental 或 Release binary cache。
- CI 优先保存 Gradle/Konan caches；remote cache 等待跨 runner 收益、权限和污染恢复方案。
- 当前不把本机毫秒值设为硬门禁；积累稳定 CI p50/p95 后再定义相对退化预算。

对应决策记录：[ADR-0013](../docs/adr/0013-evidence-based-build-performance.md)

对应实验：[实验 15](../labs/15-build-performance/README.md)

对应文章：[KMP 构建为什么慢：别先加内存，先把 4 分 37 秒拆开](../articles/15-build-performance.md)





