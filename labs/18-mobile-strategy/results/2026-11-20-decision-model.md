# PocketLab 移动技术路线决策实验记录

## 环境

- 日期：2026-11-20
- 主机：Intel Mac，`Darwin x86_64`
- macOS：26.7.3
- Ruby：2.6.10
- Node.js：22.15.0
- Flutter SDK：本机未安装
- PocketLab mobile binary：继续为 0.17.0/build 17

本轮只运行标准库决策模型，不构建 Flutter 或 React Native App。没有移动运行时代码进入 PocketLab，因此没有为了文档里程碑制造 0.18.0 binary。

## 官方资料快照

核对日期为 2026-11-20：

- KMP 官方文档明确支持只共享业务逻辑并保留 native UI，也支持渐进扩大共享范围；
- Flutter 3.50 官方架构仍由 Dart app、framework、engine 与 platform embedder 组成，并支持 Android/iOS add-to-app；
- React Native latest 0.88，New Architecture 使用 JSI、Fabric 和 TurboModules，官方文档支持向存量 App 添加单个 view 或 user flow；
- Android Compose 与 SwiftUI/UIKit 官方文档均提供渐进互操作路径。

这些是候选架构事实，不是 PocketLab 性能或交付结果。

## 第一次执行失败

初版评估器使用 Ruby `filter_map`，本机 Ruby 2.6 报错：

```text
undefined method `filter_map' for Array
```

没有升级本机 Ruby。脚本改为 `each_with_object` 后兼容 Ruby 2.6，并继续只依赖标准库。

第二次门禁失败是 verifier 查找 `no universal framework score`，模型实际写的是 `not a universal framework score`。修正为精确匹配后通过。

两次失败都属于实验门禁自身的问题，没有改变评分输入来追求通过。

## 最终门禁

命令：

```shell
cd labs/18-mobile-strategy
./scripts/verify-mobile-strategy.sh
```

输出：

```text
Syntax OK
Mobile strategy model passed.
4 strategies, 11 criteria, 4 scenarios
Hard constraints run before weighted fit.
Each strategy wins one scenario; no universal winner is encoded.
Mobile strategy report rendered.
Hard gates -> scenario weights -> candidate -> implementation spike
```

## 场景结果

| 场景 | 通过 gate 的候选 | 决策候选 | 关键解释 |
|---|---|---|---|
| PocketLab brownfield | KMP、纯原生 | KMP 92.2 | 保留原生 UI/资产，同时共享业务规则 |
| Greenfield branded product | Flutter、React Native | Flutter 85.6 | 与 RN 仅差 4.8，必须做同题 spike |
| React web team extension | React Native | React Native 82.0 | 人员与交付语言被声明为 gate |
| Deep platform product | 纯原生 | 纯原生 87.1 | KMP 总分 88.6，但未通过平台 API gate |

四个场景分别由四种方案胜出。这不是为了证明大家都一样好，而是防止模型暗中编码“KMP 必须赢”。

Deep-platform 场景把 KMP 的 `platform_api_velocity` 记为 4，因为输入假定核心平台能力需要进入 shared orchestration 并维护 adapter。若它可以完整留在 native side，该 rating 应改为 5 后重算；当前结果不是框架固有定律。

## PocketLab 决策

```text
Continue:
KMP shared business/data + Android Compose + iOS SwiftUI

Do not start:
Flutter rewrite
React Native rewrite
pure-native duplication of shared rules
```

依据：

- 当前两端 UI 与平台生命周期已经是有效资产；
- 目标问题是业务与数据规则重复；
- KMP 可按 feature seam 渐进接入；
- 第 17 轮已经验证 composition-root rollback 结构；
- 纯原生通过硬约束，但会继续复制共同业务场景。

## 证据边界

本轮证明：

- 输入 schema 可由机器验证；
- hard gate 在 weighted score 之前执行；
- 相同输入得到确定结果；
- 没有 universal winner；
- PocketLab 当前约束下 KMP 是进入下一阶段的候选。

本轮没有证明：

- KMP 性能优于 Flutter、React Native 或纯原生；
- 当前 rating 可以直接用于另一个团队；
- Flutter/RN plugin 生态满足 PocketLab 全部生产需求；
- Compose Multiplatform 是否适合共享 UI；
- 未来组织和目标平台变化后 KMP 仍是 winner。

需要比较相近候选时，下一份强证据必须来自同题 Release spike，而不是继续修改表格。
