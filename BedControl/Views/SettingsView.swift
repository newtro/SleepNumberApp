import BedKit
import SwiftUI

struct SettingsView: View {
    @Environment(BedController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var confirmForget = false

    var body: some View {
        @Bindable var controller = controller
        NavigationStack {
            Form {
                Section {
                    Toggle("Swap left and right", isOn: $controller.swapSides)
                } header: {
                    Text("Sides")
                } footer: {
                    Text("Left and Right mean the sides as you lie in bed on your back. If pressing Left moves your partner's side, turn this on. Reports disagree on how the bed numbers its sides, so check once with a small head movement.")
                }

                Section("Bed") {
                    LabeledContent("Name", value: controller.savedBed?.name ?? "None")
                    LabeledContent("Protocol", value: controller.protocolKind?.displayName ?? controller.savedBed?.lastProtocol?.displayName ?? "Unknown")
                    if controller.savedBed != nil {
                        Button("Forget this bed", role: .destructive) { confirmForget = true }
                    }
                }

                Section {
                    Text("Bed Control talks to the bed directly over Bluetooth. It cannot work while the Sleep Number app (or another phone) holds all of the bed's connections.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Forget this bed?", isPresented: $confirmForget, titleVisibility: .visible) {
                Button("Forget", role: .destructive) {
                    controller.forgetBed()
                    dismiss()
                }
            }
        }
    }
}
