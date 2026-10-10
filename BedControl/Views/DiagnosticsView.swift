import BedKit
import SwiftUI
import UIKit

struct DiagnosticsView: View {
    @Environment(BedController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var filter: Filter = .all
    @State private var copied = false
    @State private var autoScroll = true

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All", frames = "Frames", events = "Events"
        var id: String { rawValue }
    }

    private var entries: [DiagnosticsLog.Entry] {
        controller.log.entries.filter { entry in
            switch filter {
            case .all: return true
            case .frames: return entry.event.direction == .tx || entry.event.direction == .rx
            case .events: return entry.event.direction == .info || entry.event.direction == .error
            }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                infoSection
                Picker("Filter", selection: $filter) {
                    ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)
                logList
            }
            .navigationTitle("Diagnostics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("Clear", systemImage: "trash", role: .destructive) { controller.log.clear() }
                    Spacer()
                    Button("Read status", systemImage: "arrow.clockwise") { Task { await controller.refreshNow() } }
                        .disabled(!controller.isReady)
                    Spacer()
                    ShareLink(item: exportText) { Image(systemName: "square.and.arrow.up") }
                    Button(copied ? "Copied" : "Copy all", systemImage: copied ? "checkmark" : "doc.on.doc") {
                        UIPasteboard.general.string = exportText
                        copied = true
                        Task { try? await Task.sleep(for: .seconds(1.5)); copied = false }
                    }
                }
            }
        }
    }

    private var exportText: String { controller.log.export(header: controller.diagnosticsHeader()) }

    private var infoSection: some View {
        VStack(alignment: .leading, spacing: 3) {
            ForEach(Array(controller.diagnostics.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top) {
                    Text(row.0).foregroundStyle(.secondary)
                    Spacer()
                    Text(row.1).multilineTextAlignment(.trailing).textSelection(.enabled)
                }
                .font(.caption.monospaced())
            }
            if controller.diagnostics.isEmpty {
                Text("Not connected").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var logList: some View {
        ScrollViewReader { proxy in
            List(entries) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(DiagnosticsLog.timeFormatter.string(from: entry.date)).foregroundStyle(.secondary)
                        Text(entry.event.direction.rawValue).fontWeight(.bold).foregroundStyle(color(entry.event.direction))
                        Text(entry.event.channel).foregroundStyle(.secondary)
                    }
                    Text(entry.event.text)
                    if let bytes = entry.event.bytes {
                        Text(bytes.hexString).foregroundStyle(.secondary)
                    }
                }
                .font(.caption2.monospaced())
                .textSelection(.enabled)
                .id(entry.id)
                .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
            }
            .listStyle(.plain)
            .onChange(of: entries.last?.id) { _, last in
                if autoScroll, let last { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
    }

    private func color(_ direction: FrameEvent.Direction) -> Color {
        switch direction {
        case .tx: return .blue
        case .rx: return .green
        case .info: return .secondary
        case .error: return .red
        }
    }
}
