# PocketLab 跨平台诊断链实验记录

## 环境与范围

- 日期：2026-11-20
- 主机：Intel Mac，`macos_x64`
- macOS：26.7.3
- JDK：17.0.10
- Xcode：26.5
- Kotlin：2.4.21
- Gradle：8.14.4
- AGP：8.13.2
- Android Release R8 override：9.1.29
- Android runtime smoke：Pixel 2 API 34 emulator
- App version：0.16.0
- Android/iOS build：16

本记录验证共同刷新诊断、Android minified Release、Kotlin/Native executable dSYM 和 Xcode 配置。Xcode 26.5 未安装匹配的 iOS 26.5 runtime，因此没有生成最终 SwiftUI App Release archive。

## 测试结果

新增场景：

| 所有者 | 场景 | 每个共同目标执行 |
|---|---:|---:|
| domain | refresh lifecycle semantics | 5 |
| shared | structured schema | 5 |
| shared | production platform sink | 1 |

更新后的聚合：

| module | JVM | Android Host | iOS x64 |
|---|---:|---:|---:|
| domain | 23/23 | 23/23 | 23/23 |
| shared | 27/27 | 27/27 | 35/35 |
| 合计 | 50/50 | 50/50 | 58/58 |

诊断相关新增共同场景为 11 个，在三个目标重复执行 33 次。仓库不同测试场景由 54 增至 65；目标执行由 130 增至 163，其中当前可运行矩阵为 162，剩余一条仍是受 Simulator runtime 阻断的 iOS UI smoke。

## Android Release 产物

门禁命令：

```shell
cd labs/02-first-kmp-module
./scripts/verify-observability.sh
```

结果：

```text
Observability contract passed.
Structured refresh schema: privacy allowlist + monotonic duration
Android Release: 0.16.0 (16), R8 9.1.29,
pg_map_id=740c594d49ba8c9f2ae2455dd3896c750b19ed0d598b05b449b3b2bc353ed5a9
Android APK SHA-256:
c3ec4aaf8575ec9ff2105884866a1fa5a7eaa63469a43fc2494c7c838df89067
Android mapping SHA-256:
55137bf439885e07e4d25845ef038bcba222e10dbffa91f14da4cc8c4f4e2a91
Kotlin/Native UUID: 27989878-0ED1-33E9-99A6-73B55C1B20B5
Kotlin/Native symbolication: atos restored the diagnostic test function
Apple App Release dSYM setting: configured
```

产物大小：

| 产物 | bytes | 说明 |
|---|---:|---|
| unsigned Release APK | 1,016,474 | 约 993 KiB，本机 build sample |
| mapping.txt | 19,962,387 | 161,310 行 |

mapping header 包含：

```text
# compiler: R8
# compiler_version: 9.1.29
# min_api: 24
# mapping_version: 2.2
```

并能找到 Android App class、`RefreshExperimentsUseCase.kt` 与 `RefreshObservability.kt` 的 source/line metadata。

## R8 兼容性实验

第一次使用 AGP 8.13.2 内置 R8 构建时，R8 输出 Kotlin 2.4 metadata parse warnings。构建虽然成功，但该组合没有作为可接受基线。

根据 Android 官方 Kotlin/R8 兼容表，settings 从官方 R8 repository 覆盖为 9.1.29。重新构建后：

- Kotlin metadata parse warnings 消失；
- mapping header 明确为 R8 9.1.29；
- Release APK 与 mapping 正常生成；
- 出现两条 `OrderedClassFileResourceProvider` 不支持 async parsing 的 warning。

最后一项被记录为当前 AGP provider 与独立 R8 的构建性能提示，不是 Kotlin metadata 解析正确性警告。后续升级 AGP/R8 时重新评估。

## Android minified Release runtime smoke

unsigned APK 被复制到 ignored `build/observability/android`，使用本机标准 debug keystore 对副本签名。该签名只用于本地 smoke，没有进入 Release Gradle 配置，也不是发布签名。

`apksigner verify` 确认 v2/v3 signature 后，APK 安装到 Pixel 2 API 34 emulator。启动 component：

```text
dev.kmpjourney.pocketlab/
dev.kmpjourney.pocketlab.android.MainActivity
```

冷启动结果：

```text
Status: ok
LaunchState: COLD
TotalTime: 1414
```

Logcat：

```text
I PocketLab: schema=1 event=pocketlab.refresh phase=started level=info
  operation_id=<same-uuid>
I PocketLab: schema=1 event=pocketlab.refresh phase=finished level=info
  operation_id=<same-uuid> duration_ms=628 outcome=applied
  received_count=3 changed_count=3
```

真实输出为单行；这里换行用于阅读。未出现 `AndroidRuntime` crash。

`1414 ms` 与 `628 ms` 都是单台模拟器的一次样本。它们证明 minified Release 启动、刷新和诊断 writer 可达，不代表性能预算或真实用户分布。

## 隐私与行为检查

固定 schema 允许：

```text
operation id
duration
stable outcome/reason
HTTP status
received/changed counts
exception type
```

固定 schema 不允许：

```text
experiment title/hypothesis/notes
base URL or database path
Authorization/token
exception message
raw response
arbitrary attributes
```

测试还证明：

- 同一次操作的 started/finished correlation ID 相同；
- token 中的换行和分隔字符被清理；
- diagnostic backend 抛错不会改变成功结果；
- expected failure 仍是稳定 failure result；
- cancellation 和 unexpected exception 均原样重抛。

## Kotlin Native dSYM

目标：

```text
shared/build/bin/iosX64/debugTest/test.kexe
shared/build/bin/iosX64/debugTest/test.kexe.dSYM
```

UUID：

```text
binary  27989878-0ED1-33E9-99A6-73B55C1B20B5
dSYM    27989878-0ED1-33E9-99A6-73B55C1B20B5
```

`nm` 定位共同诊断测试函数地址后，`atos` 使用 dSYM 恢复：

```text
kfun:dev.kmpjourney.pocketlab.diagnostics.
StructuredRefreshDiagnosticsTest#
appliedRefreshUsesOneCorrelationIdAndMonotonicDuration()
```

当前输出源码行是 `StructuredRefreshDiagnosticsTest.kt:0`。因此证据范围是“UUID 匹配且 Kotlin 函数名可恢复”，不是“完整源码行符号化”。

## Apple App dSYM 缺口

工程与 generator 均设置 Release：

```text
DEBUG_INFORMATION_FORMAT = dwarf-with-dsym
```

实际执行 Xcode Release Simulator App build 时，Xcode 26.5 没有安装匹配的 iOS 26.5 runtime/platform，generic Simulator destination 也不可用，命令以 exit 70 结束。

因此本轮没有：

- 最终 SwiftUI App Release archive；
- 最终 App binary/dSYM UUID 对照；
- 受控 App crash report 的端到端 symbolication。

这些项目应在安装匹配 runtime 的 Apple Silicon CI 或开发机补齐。Native test executable 的成功不能替代最终 App 归档验证。

## Apple 分发版本回归

版本更新后重新运行 `verify-apple-distribution.sh`：

```text
BUILD SUCCESSFUL in 3m 8s
Device architectures: arm64
Simulator architectures: x86_64 arm64
Remote artifact checksum:
1ba0e60b2d87b29bf65184f5ee276ad239cf0b6df38f075db7d99e6c864952b2
```

两个 XCFramework slices 的 metadata 均为 `0.16.0 / build 16`，Swift 6 consumer type-check、Swift package 解析和 ZIP checksum 通过。这个 gate 验证 framework 分发版本，不替代最终 App archive/dSYM 验证。

## 门禁耗时与 task 状态

版本更新后的专用门禁：

```text
BUILD SUCCESSFUL in 59s
112 actionable tasks: 25 executed, 87 up-to-date
Configuration cache entry stored.
```

这是一轮混合执行/up-to-date 的功能门禁，不是 clean build benchmark。它只说明门禁在当前状态成功。

## 结论

本轮建立了可执行的最小诊断链：

```text
typed lifecycle
→ fixed privacy allowlist schema
→ platform writer
→ minified Release mapping / Native dSYM
→ artifact identity
```

当前可以可靠地区分刷新成功、业务失败、取消和未预期异常，并证明 Android Release 与 Native executable 有匹配的反混淆/符号恢复材料。远端采集、告警、最终 iOS App archive 和真实性能分位数仍是后续工作，未被本实验结果代替。
