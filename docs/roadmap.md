# 从 Android 开发者到 KMP 专家的路线图

## 目标不是“学完”，而是获得五层能力

| 层级 | 你能做到什么 | 验收作品 |
|---|---|---|
| L1 心智模型 | 说清 KMP 如何编译、哪里共享、哪里仍然原生 | 原理图 + 最小双端函数 |
| L2 常用开发 | 共享模型、网络、存储、协程和测试 | Android/iOS 双端应用 |
| L3 架构设计 | 设计共享边界、状态流、错误模型和平台接口 | 可维护的功能模块 |
| L4 工程与性能 | 处理 Swift 互操作、并发、构建、发布与性能 | framework/XCFramework + CI |
| L5 技术决策 | 评估团队、迁移策略、故障域和投入产出 | 生产级方案与决策记录 |

## 四阶段主线

### 第一阶段：拆掉误解（1–2 周）

核心问题：KMP 是另一种 Flutter 吗？Kotlin 怎么进入 iPhone？共享代码运行在哪里？

- KMP、KMM、Compose Multiplatform、Kotlin/Native 的关系
- Gradle target、source set、编译产物
- Android/JVM 与 iOS/Native 的两条编译链
- `commonMain` 能使用什么，为什么不能直接使用 Android SDK
- `expect/actual`、接口注入和平台 API 边界

验收：一个共享领域函数，在 Android 单元测试和 iOS 模拟器测试中得到相同结果；能手画编译链而不依赖背诵。

### 第二阶段：做出真正有用的 App（3–6 周）

项目暂定名：**PocketLab**——一个“离线优先的个人实验记录器”。它有列表、详情、搜索、同步状态与本地缓存，足以覆盖真实工程问题，又不会被业务复杂度淹没。

- `kotlinx.coroutines` 与 `Flow`
- `kotlinx.serialization`
- Ktor Client 网络层
- SQLDelight 或 Room KMP 本地存储（通过实验后再做选择）
- 时间、UUID、文件、网络状态等平台能力
- 共享业务逻辑 + Android Compose / iOS SwiftUI 原生 UI
- commonTest、平台测试与 fake

验收：Android 与 iOS UI 不共享，但数据、领域规则和状态管理共享；断网可用；核心逻辑有测试。

### 第三阶段：从“能跑”到“像工程”（4–8 周）

- source set 层级与模块化
- API 设计：让 Kotlin API 对 Swift 友好
- 异常、空值、泛型、sealed class、协程与 Swift 的互操作
- 依赖注入与平台实现装配
- Framework、XCFramework、Swift Package Manager、CocoaPods 集成取舍
- Gradle 构建缓存、版本目录、编译时间与 CI
- 日志、崩溃定位、性能与二进制体积

验收：共享模块能独立发布、接入一个现有 Android/iOS 壳工程，并有自动化测试和构建。

### 第四阶段：专家决策（持续）

- 共享 UI 与原生 UI 的成本模型
- Compose Multiplatform 的适用边界
- 增量迁移遗留 Android/iOS 项目
- 团队所有权、版本治理、发布节奏和故障域
- 与 Flutter、React Native、纯原生方案做同条件对比
- 阅读 Kotlin/Native、编译器与生态库源码

验收：针对不同团队给出不同方案，而不是把 KMP 当成固定答案；决策能写清假设、证据、风险和回滚方式。

## 公众号系列规划

### 第一辑：原来如此

1. KMM 到底是什么：同一份 Kotlin，为什么能跑在 Android 和 iOS？
2. 一张图看懂 KMP 工程：target、source set 和编译产物
3. `commonMain` 不是公共垃圾场：哪些代码值得共享？
4. `expect/actual` 像插座，但别把整栋楼都接在上面

### 第二辑：做一个真 App

5. 第一条跨越 Android 与 iOS 的数据流
6. Ktor：同一个请求，在三套网络引擎上发生了什么？
7. 离线优先：不是“加一层缓存”，而是重新决定谁说了算
8. Coroutines 遇见 Swift Concurrency：一条 Flow 过桥后，为什么忘了怎么取消？
9. Android Compose + SwiftUI：UI 不共享也能合作愉快
10. 测试金字塔：一次验证，两端放心

### 第三辑：穿过深水区

11. Kotlin/Native 的编译、内存与并发模型
12. 为 Swift 设计 Kotlin API：能导出不等于好用
13. Framework、XCFramework、SPM 与 CocoaPods 怎么选
14. KMP 模块化：共享多少，由谁决定？
15. 构建为什么慢，以及如何用数据优化
16. KMP 出问题时，谁来作证：从一条 628 ms 日志到可符号化崩溃

### 第四辑：技术负责人视角

17. 在现有 App 中渐进引入 KMP
18. KMP、Flutter、React Native 与纯原生：别只比较代码复用率
19. Compose Multiplatform 要不要上？一份决策清单
20. KMP 上生产：团队协作、版本治理与回滚策略

## 每篇文章的质量门槛

- 有一个真实问题，而不只是罗列 API。
- 有一个可以复现的最小实验。
- 同时解释“怎么做”和“为什么”。
- 至少展示一次 Android/iOS 对照。
- 明确能力边界、替代方案和失败场景。
- 技术结论链接到官方资料；版本敏感内容注明验证日期。
- 结尾提供读者可以亲手完成的挑战。

## 进度规则

只有当本阶段验收作品可运行、原理能口述、文章能被不熟悉 KMP 的 Android 开发者读懂，才进入下一阶段。路线允许回环：遇到 Swift 互操作或 Kotlin/Native 问题时，回到最小实验，而不是在业务工程里猜。

