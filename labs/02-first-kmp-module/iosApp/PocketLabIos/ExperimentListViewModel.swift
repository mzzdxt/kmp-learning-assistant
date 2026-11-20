import Combine
import Foundation

@MainActor
final class ExperimentListViewModel: ObservableObject {
    @Published private(set) var experiments: [Experiment] = []
    @Published private(set) var isRefreshing = false
    @Published private(set) var notice: String?

    private let gateway: any ExperimentListGateway
    private var manualRefreshTask: Task<Void, Never>?

    init(gateway: any ExperimentListGateway) {
        self.gateway = gateway
    }

    func observe() async {
        do {
            for try await records in gateway.experiments() {
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
            let result = try await gateway.refresh()
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

    private func refreshMessage(for result: RefreshOutcome) -> String {
        switch result {
        case .updated(let changedCount):
            return "同步完成：更新了 \(changedCount) 条记录"
        case .unchanged:
            return "已是最新状态"
        case .rejected(let rejection):
            return failureMessage(for: rejection)
        }
    }

    private func failureMessage(for rejection: RefreshRejection) -> String {
        switch rejection {
        case .unauthorized:
            return "身份已过期，请重新登录"
        case .server:
            return "服务暂时不可用，本地数据仍可阅读"
        case .unexpectedStatus:
            return "服务器返回了未处理的状态"
        case .malformedResponse:
            return "服务器数据格式不兼容"
        case .connection:
            return "当前离线，继续显示本地数据"
        case .unknown:
            return "刷新失败，但本地数据仍可阅读"
        }
    }

    deinit {
        manualRefreshTask?.cancel()
        Task { @MainActor [gateway] in
            gateway.close()
        }
    }
}
