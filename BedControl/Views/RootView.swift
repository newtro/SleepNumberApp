import BedKit
import SwiftUI

struct RootView: View {
    @Environment(BedController.self) private var controller
    @State private var showScan = false
    @State private var showDiagnostics = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            Group {
                if controller.savedBed == nil {
                    ScanView(onChosen: {})
                } else {
                    ControlView()
                }
            }
            .navigationTitle(controller.savedBed == nil ? "Find your bed" : "Bed Control")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ConnectionPill()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Diagnostics", systemImage: "waveform.path.ecg") { showDiagnostics = true }
                        Button("Settings", systemImage: "gearshape") { showSettings = true }
                        if controller.savedBed != nil {
                            Divider()
                            Button("Choose a different bed", systemImage: "antenna.radiowaves.left.and.right") { showScan = true }
                            if controller.isReady {
                                Button("Disconnect", systemImage: "bolt.slash") { controller.disconnect() }
                            } else {
                                Button("Connect", systemImage: "bolt") { controller.retry() }
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .sheet(isPresented: $showScan) {
                NavigationStack {
                    ScanView(onChosen: { showScan = false })
                        .navigationTitle("Choose a bed")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showScan = false } } }
                }
            }
            .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
            .sheet(isPresented: $showSettings) { SettingsView() }
        }
    }
}

/// Compact connection status shown in the navigation bar.
struct ConnectionPill: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.caption.weight(.semibold)).lineLimit(1)
        }
        .fixedSize()
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Connection: \(text)")
    }

    private var color: Color {
        switch controller.phase {
        case .ready: return .green
        case .searching, .connecting: return .orange
        case .failed, .bluetoothUnavailable: return .red
        case .noBed: return .gray
        }
    }

    private var text: String {
        switch controller.phase {
        case .ready: return controller.protocolKind?.displayName ?? "Connected"
        case .searching: return "Searching"
        case .connecting: return "Connecting"
        case .failed: return "Offline"
        case .bluetoothUnavailable: return "No Bluetooth"
        case .noBed: return "No bed"
        }
    }
}
