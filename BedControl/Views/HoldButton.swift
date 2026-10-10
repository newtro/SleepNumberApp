import BedKit
import SwiftUI

/// Press-and-hold movement button. Movement runs only while a finger is down; lifting the
/// finger, the gesture being cancelled (e.g. by the system), or the view disappearing all
/// release it, because `@GestureState` resets on every one of those paths.
struct HoldButton: View {
    @Environment(BedController.self) private var controller
    let axis: FoundationAxis
    let side: BedSide
    let direction: MoveDirection

    @GestureState private var pressing = false

    private var key: BedController.HoldKey { .init(axis: axis, side: side, direction: direction) }
    private var isActive: Bool { controller.activeHold == key }
    private var otherHoldActive: Bool { controller.activeHold != nil && !isActive }

    var body: some View {
        Image(systemName: direction == .up ? "chevron.up" : "chevron.down")
            .font(.title3.weight(.bold))
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(isActive ? Color.white : (controller.isReady ? Color.accentColor : Color.secondary))
            .background(isActive ? Color.accentColor : Color.accentColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
            .scaleEffect(isActive ? 0.96 : 1)
            .opacity(otherHoldActive ? 0.4 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 12))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($pressing) { _, state, _ in state = true }
            )
            .onChange(of: pressing) { _, isPressing in
                if isPressing {
                    guard controller.isReady, controller.activeHold == nil else { return }
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    controller.beginHold(axis, side: side, direction: direction)
                } else if isActive {
                    controller.endHold()
                }
            }
            .onDisappear { if isActive { controller.endHold() } }
            .animation(.easeOut(duration: 0.12), value: isActive)
            .accessibilityElement()
            .accessibilityLabel("\(axis == .head ? "\(side.title) head" : "Foot") \(direction == .up ? "up" : "down")")
            .accessibilityHint("Press and hold to move")
            .accessibilityAddTraits(.isButton)
    }
}
