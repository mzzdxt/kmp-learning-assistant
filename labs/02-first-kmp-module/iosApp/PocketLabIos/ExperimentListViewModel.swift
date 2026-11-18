import Combine
import Foundation
@preconcurrency import PocketLabShared

@MainActor
final class ExperimentListViewModel: ObservableObject {
    @Published private(set) var experiments: [ExperimentRecord] = []
    @Published private(set) var isRefreshing = false
    @Published private(set) var notice: String?

    private let feature: IosPocketLabFeature
    private var manualRefreshTask: Task<Void, Never>?

    init(feature: IosPocketLabFeature) {
        self.feature = feature
    }

    func observe() async {
        do {
            for try await records in feature.experimentList.experiments() {
                experiments = records
            }
        } catch is CancellationError {
            // SwiftUI cancelled the view task; the AsyncStream cancels the Kotlin Job.
        } catch {
            notice = error.localizedDescription
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let result = try await feature.experimentList.refreshOnce()
            notice = refreshMessage(for: result)
        } catch is CancellationError {
            // Navigation and view disappearance are control flow, not user-facing errors.
        } catch {
            notice = error.localizedDescription
        }
    }

    func requestRefresh() {
        manualRefreshTask?.cancel()
        manualRefreshTask = Task { [weak self] in
            await self?.refresh()
        }
    }

    func stopManualWork() {
        manualRefreshTask?.cancel()
        manualRefreshTask = nil
    }

    private func refreshMessage(for result: any RefreshExperimentsResult) -> String {
        if let applied = result as? RefreshExperimentsResultApplied {
            return applied.changedCount == 0
                ? "已是最新状态"
                : "同步完成：更新了 \(applied.changedCount) 条记录"
        }
        if let failed = result as? RefreshExperimentsResultFailed {
            return failureMessage(for: failed.reason)
        }
        return "刷新完成"
    }

    private func failureMessage(for reason: RefreshFailureReason) -> String {
        if reason == RefreshFailureReason.unauthorized {
            return "身份已过期，请重新登录"
        }
        if reason == RefreshFailureReason.server {
            return "服务暂时不可用，本地数据仍可阅读"
        }
        if reason == RefreshFailureReason.malformedResponse {
            return "服务器数据格式不兼容"
        }
        if reason == RefreshFailureReason.connection {
            return "当前离线，继续显示本地数据"
        }
        return "服务器返回了未处理的状态"
    }

    deinit {
        manualRefreshTask?.cancel()
        feature.close()
    }
}
