import SwiftUI
@preconcurrency import PocketLabShared

struct ExperimentListScreen: View {
    @ObservedObject var viewModel: ExperimentListViewModel

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.experiments.isEmpty && !viewModel.isRefreshing {
                    ContentUnavailableView {
                        Label("还没有实验", systemImage: "flask")
                    } description: {
                        Text("刷新示例数据，开始观察一个小改变。")
                    } actions: {
                        Button("刷新") {
                            viewModel.requestRefresh()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        if let notice = viewModel.notice {
                            Section {
                                Label(notice, systemImage: "checkmark.circle")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        ForEach(viewModel.experiments, id: \.id) { experiment in
                            ExperimentRow(experiment: experiment)
                                .listRowSeparator(.hidden)
                                .listRowInsets(
                                    EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16)
                                )
                        }
                    }
                    .listStyle(.plain)
                    .refreshable {
                        await viewModel.refresh()
                    }
                }
            }
            .overlay {
                if viewModel.isRefreshing && viewModel.experiments.isEmpty {
                    ProgressView("正在同步实验…")
                }
            }
            .navigationTitle("PocketLab")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.requestRefresh()
                    } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .disabled(viewModel.isRefreshing)
                }
            }
        }
        .task {
            await viewModel.observe()
        }
        .task {
            await viewModel.refresh()
        }
        .onDisappear {
            viewModel.stopManualWork()
        }
        .tint(Color(red: 0.19, green: 0.37, blue: 0.33))
    }
}

private struct ExperimentRow: View {
    let experiment: ExperimentRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(experiment.title)
                    .font(.title3.weight(.semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                SyncBadge(state: experiment.syncState)
            }

            LabeledValue(label: "我的猜想", value: experiment.hypothesis)
            if !experiment.notes.isEmpty {
                LabeledValue(label: "验证方法", value: experiment.notes)
            }

            Text(
                Date(
                    timeIntervalSince1970:
                        TimeInterval(experiment.updatedAtEpochMilliseconds) / 1_000
                ),
                format: .dateTime.year().month(.abbreviated).day()
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
        .accessibilityElement(children: .combine)
    }
}

private struct LabeledValue: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption.weight(.bold))
                .foregroundStyle(Color(red: 0.19, green: 0.37, blue: 0.33))
            Text(value)
                .font(.body)
        }
    }
}

private struct SyncBadge: View {
    let state: ExperimentSyncState

    var body: some View {
        let pending = state == ExperimentSyncState.pendingUpload
        Text(pending ? "待上传" : "已同步")
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(
                    pending
                        ? Color.orange.opacity(0.18)
                        : Color.green.opacity(0.16)
                )
            )
    }
}
