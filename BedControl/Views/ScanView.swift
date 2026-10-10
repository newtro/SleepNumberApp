import BedKit
import SwiftUI

struct ScanView: View {
    @Environment(BedController.self) private var controller
    var onChosen: () -> Void

    var body: some View {
        @Bindable var ble = controller.ble
        List {
            Section {
                Label {
                    Text("Close the Sleep Number app on every phone nearby. The bed accepts only a limited number of Bluetooth connections. If the bed asks to pair, press the pair button on the pump or remote.")
                        .font(.callout)
                } icon: {
                    Image(systemName: "info.circle")
                }
            }

            if case .bluetoothUnavailable(let message) = controller.phase {
                Section { Text(message).foregroundStyle(.red) }
            }

            Section {
                if ble.visibleDevices.isEmpty {
                    HStack(spacing: 12) {
                        if ble.isScanning { ProgressView() }
                        Text(ble.isScanning ? "Looking for Sleep Number beds…" : "Not scanning")
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(ble.visibleDevices) { device in
                    Button {
                        ble.stopScan()
                        controller.choose(device)
                        onChosen()
                    } label: {
                        DeviceRow(device: device)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Nearby")
            } footer: {
                Text("Older beds (MCR) advertise their Bluetooth address as the name, e.g. 64:db:a0:xx:xx:xx.")
            }

            Section {
                Toggle("Show all Bluetooth devices", isOn: $ble.showAllDevices)
            }
        }
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                Button(ble.isScanning ? "Stop scanning" : "Scan again") {
                    ble.isScanning ? ble.stopScan() : ble.startScan()
                }
            }
        }
        .onAppear { ble.startScan() }
        .onDisappear { ble.stopScan() }
        .onChange(of: ble.bluetoothState) { _, state in
            if state == .poweredOn && !ble.isScanning { ble.startScan() }
        }
    }
}

private struct DeviceRow: View {
    let device: ScannedDevice

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.classification.isCandidate ? "bed.double.fill" : "dot.radiowaves.left.and.right")
                .font(.title3)
                .foregroundStyle(device.classification.isCandidate ? Color.accentColor : .secondary)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(.body.monospaced()).lineLimit(1)
                if !device.classification.reasons.isEmpty {
                    Text(device.classification.reasons.map(\.rawValue).joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let kind = device.classification.likelyProtocol {
                    Text("Likely \(kind.displayName)").font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
            SignalBars(rssi: device.rssi)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }
}

private struct SignalBars: View {
    let rssi: Int
    var body: some View {
        let level = rssi > -60 ? 3 : rssi > -75 ? 2 : rssi > -90 ? 1 : 0
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<3, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1)
                    .fill(index < level ? Color.primary : Color.secondary.opacity(0.3))
                    .frame(width: 4, height: CGFloat(6 + index * 4))
            }
        }
        .accessibilityLabel("Signal \(rssi) dBm")
    }
}
