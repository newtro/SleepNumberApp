import BedKit
import SwiftUI

struct ControlView: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                StatusCard()
                if let banner = controller.banner {
                    BannerView(text: banner) { controller.banner = nil }
                }
                if let run = controller.applyingFavorite {
                    FavoriteProgressCard(run: run)
                }
                HStack(alignment: .top, spacing: 12) {
                    SidePanel(side: .left)
                    SidePanel(side: .right)
                }
                FootPanel()
                if controller.hasUnderBedLight { LightRow() }
            }
            .padding(.horizontal, 14)
            .padding(.top, 6)
            .padding(.bottom, 12)
            .disabled(!controller.isReady)
            .opacity(controller.isReady ? 1 : 0.55)
            .animation(.default, value: controller.isReady)
        }
        // A scroll must not steal the finger from a held movement button.
        .scrollDisabled(controller.activeHold != nil)
        .refreshable { await controller.refreshNow() }
        .safeAreaInset(edge: .bottom) {
            StopButton()
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.bar)
        }
        .background(Color(.systemGroupedBackground))
    }
}

// MARK: - Status

private struct StatusCard: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(controller.savedBed?.name ?? "Bed")
                    .font(.headline.monospaced())
                    .lineLimit(1)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(isError ? .red : .secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            switch controller.phase {
            case .searching, .connecting:
                ProgressView()
            case .failed:
                Button("Retry") { controller.retry() }.buttonStyle(.borderedProminent)
            default:
                if controller.moving {
                    Label("Moving", systemImage: "arrow.up.arrow.down").font(.caption.weight(.semibold)).foregroundStyle(.orange)
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var isError: Bool {
        if case .failed = controller.phase { return true }
        if case .bluetoothUnavailable = controller.phase { return true }
        return false
    }

    private var detail: String {
        switch controller.phase {
        case .ready:
            if let updated = controller.lastUpdated {
                return "\(controller.protocolKind?.displayName ?? "") · updated \(updated.formatted(date: .omitted, time: .standard))"
            }
            return controller.protocolKind?.displayName ?? "Connected"
        case .searching(let name): return "Looking for \(name)… (stays pending until the bed is in range)"
        case .connecting(let step): return step
        case .failed(let message): return message
        case .bluetoothUnavailable(let message): return message
        case .noBed: return "No bed selected"
        }
    }
}

private struct BannerView: View {
    let text: String
    let dismiss: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
            Text(text).font(.callout).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: dismiss) { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                .accessibilityLabel("Dismiss")
        }
        .padding(12)
        .background(Color.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Side panel

private struct SidePanel: View {
    @Environment(BedController.self) private var controller
    let side: BedSide
    @State private var showFavorites = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(side.title).font(.title3.weight(.bold))
                Spacer()
                if let preset = controller.activePreset[side] {
                    Text(preset.title).font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.18), in: Capsule())
                }
            }

            FirmnessControl(side: side)

            Divider()

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Head").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(controller.head[side].map { "\($0)%" } ?? "–")
                        .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                }
                PositionBar(value: controller.head[side])
                HStack(spacing: 8) {
                    HoldButton(axis: .head, side: side, direction: .up)
                    HoldButton(axis: .head, side: side, direction: .down)
                }
            }

            Divider()

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                ForEach(controller.presetOptions) { option in
                    Button {
                        controller.activate(option, side: side)
                    } label: {
                        VStack(spacing: 3) {
                            Image(systemName: option.symbol).font(.body)
                            Text(option.title).font(.caption2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity, minHeight: 46)
                    }
                    .buttonStyle(.bordered)
                    .tint(isActive(option) ? .accentColor : .secondary)
                    .accessibilityLabel("\(option.accessibilityName), \(side.title) side")
                }
            }

            Button {
                showFavorites = true
            } label: {
                Label("My favorites (\(controller.favorites[side].count))", systemImage: "heart.text.square")
                    .font(.footnote.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("\(side.title) side saved favorites")
        }
        .sheet(isPresented: $showFavorites) { FavoritesView(side: side) }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func isActive(_ option: PresetOption) -> Bool {
        if case .preset(let preset) = option { return controller.activePreset[side] == preset }
        return false
    }
}

private struct FirmnessControl: View {
    @Environment(BedController.self) private var controller
    let side: BedSide
    @State private var draft: Int?

    private var current: Int? { controller.firmness[side] }
    private var shown: Int { draft ?? controller.requestedFirmness[side] ?? current ?? 50 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Sleep Number").font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(current.map(String.init) ?? "–")
                    .font(.system(size: 40, weight: .bold, design: .rounded).monospacedDigit())
                    .contentTransition(.numericText())
                if let status {
                    Text(status).font(.caption2.weight(.semibold)).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(spacing: 6) {
                stepButton(-SleepNumber.step, symbol: "minus")
                Text("\(shown)")
                    .font(.headline.monospacedDigit())
                    .frame(minWidth: 34)
                    .foregroundStyle(draft == nil ? .secondary : .primary)
                stepButton(SleepNumber.step, symbol: "plus")
            }
            if let draft, draft != current {
                Button("Set \(draft)") {
                    controller.setFirmness(draft, side: side)
                    self.draft = nil
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .frame(maxWidth: .infinity, alignment: .leading)
                .disabled(controller.applyingFavorite != nil || controller.activeHold != nil)
            } else if controller.queuedFirmness[side] != nil {
                Button("Cancel queued", role: .destructive) { controller.cancelQueuedFirmness(side) }
                    .controlSize(.small)
            }
        }
    }

    private var status: String? {
        if let queued = controller.queuedFirmness[side] { return "Queued \(queued): waits for the pump" }
        if controller.adjustingSide == side {
            if let target = controller.requestedFirmness[side] { return "Adjusting to \(target)…" }
            return "Adjusting…"
        }
        if controller.pumpBusy && controller.adjustingSide == nil { return "Pump busy" }
        return nil
    }

    private func stepButton(_ delta: Int, symbol: String) -> some View {
        Button {
            draft = SleepNumber.normalize(shown + delta)
        } label: {
            Image(systemName: symbol).font(.body.weight(.bold)).frame(width: 30, height: 26)
        }
        .buttonStyle(.bordered)
        .disabled(delta < 0 ? shown <= SleepNumber.range.lowerBound : shown >= SleepNumber.range.upperBound)
        .accessibilityLabel(delta < 0 ? "Softer" : "Firmer")
    }
}

// MARK: - Foot, light, stop

private struct FootPanel: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        @Bindable var controller = controller
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Foot").font(.title3.weight(.bold))
                Text("shared").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(controller.foot[controller.footSide].map { "\($0)%" } ?? "–")
                    .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            PositionBar(value: controller.foot[controller.footSide])
            Picker("Send as", selection: $controller.footSide) {
                ForEach(BedSide.allCases) { Text("As \($0.title)").tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(controller.activeHold != nil)
            .accessibilityHint("Which sleeper's side the foot commands are sent for")
            HStack(spacing: 10) {
                HoldButton(axis: .foot, side: controller.footSide, direction: .up)
                HoldButton(axis: .foot, side: controller.footSide, direction: .down)
            }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LightRow: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        Button {
            controller.toggleLight()
        } label: {
            HStack {
                Image(systemName: controller.lightOn == true ? "lightbulb.max.fill" : "lightbulb")
                    .font(.title3)
                    .foregroundStyle(controller.lightOn == true ? .yellow : .secondary)
                Text("Under-bed light").font(.headline)
                Spacer()
                Text(controller.lightOn.map { $0 ? "On" : "Off" } ?? "Unknown").foregroundStyle(.secondary)
            }
            .padding(14)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Under-bed light, \(controller.lightOn == true ? "on" : "off")")
    }
}

private struct StopButton: View {
    @Environment(BedController.self) private var controller

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            controller.stopEverything()
        } label: {
            Label("STOP", systemImage: "stop.fill")
                .font(.title2.weight(.heavy))
                .frame(maxWidth: .infinity, minHeight: 56)
        }
        .buttonStyle(.borderedProminent)
        .tint(.red)
        .disabled(!controller.isReady)
        .accessibilityHint("Stops all bed movement and any firmness adjustment")
    }
}

struct PositionBar: View {
    let value: Int?
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.2))
                Capsule().fill(Color.accentColor)
                    .frame(width: proxy.size.width * CGFloat(min(max(value ?? 0, 0), 100)) / 100)
            }
        }
        .frame(height: 6)
        .animation(.easeOut(duration: 0.3), value: value)
        .accessibilityHidden(true)
    }
}
