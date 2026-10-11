import BedKit
import SwiftUI

/// One sleeper's saved favorites: apply, add from the bed's current state, edit, rename,
/// delete and reorder.
struct FavoritesView: View {
    @Environment(BedController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    let side: BedSide

    @State private var editing: BedFavorite?
    @State private var isNew = false
    @State private var capturing = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        Task {
                            capturing = true
                            editing = await controller.captureFavorite(for: side)
                            isNew = true
                            capturing = false
                        }
                    } label: {
                        HStack {
                            Label("Save current position as favorite", systemImage: "plus.circle.fill")
                            if capturing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(capturing || !controller.isReady)
                } footer: {
                    Text("Reads this side's head, the shared foot and this side's Sleep Number from the bed. You can adjust them before saving.")
                }

                Section {
                    if controller.favorites[side].isEmpty {
                        Text("No favorites yet.").foregroundStyle(.secondary)
                    }
                    ForEach(controller.favorites[side]) { favorite in
                        FavoriteRow(favorite: favorite,
                                    canApply: controller.isReady && controller.applyingFavorite == nil && controller.activeHold == nil,
                                    apply: {
                                        controller.applyFavorite(favorite, side: side)
                                        dismiss()
                                    },
                                    edit: {
                                        editing = favorite
                                        isNew = false
                                    })
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            controller.deleteFavorite(controller.favorites[side][index].id, side: side)
                        }
                    }
                    .onMove { offsets, destination in
                        controller.moveFavorites(side: side, from: offsets, to: destination)
                    }
                } header: {
                    Text("\(side.title) side")
                } footer: {
                    Text("Apply moves the \(side.title.lowercased()) head and the foot, then sets the Sleep Number. STOP cancels it at any point. The bed's own Favorite preset is separate.")
                }
            }
            .navigationTitle("\(side.title) favorites")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { EditButton() }
            }
            .sheet(item: $editing) { favorite in
                FavoriteEditor(favorite: favorite, isNew: isNew) { saved in
                    controller.saveFavorite(saved, side: side)
                }
            }
        }
    }
}

private struct FavoriteRow: View {
    let favorite: BedFavorite
    let canApply: Bool
    let apply: () -> Void
    let edit: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: edit) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(favorite.name).font(.headline)
                    Text("Head \(favorite.head)% · Foot \(favorite.foot)% · Sleep Number \(favorite.sleepNumber)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Edit name and values")

            Button("Apply", action: apply)
                .buttonStyle(.borderedProminent)
                .disabled(!canApply)
                .accessibilityLabel("Apply \(favorite.name)")
        }
    }
}

/// Name and values editor used for both new and existing favorites.
struct FavoriteEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: BedFavorite
    @State private var name: String
    let isNew: Bool
    let onSave: (BedFavorite) -> Void

    init(favorite: BedFavorite, isNew: Bool, onSave: @escaping (BedFavorite) -> Void) {
        _draft = State(initialValue: favorite)
        _name = State(initialValue: favorite.name)
        self.isNew = isNew
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.words)
                        .submitLabel(.done)
                }
                Section("Position") {
                    positionRow("Head", value: draft.head) { draft.setHead($0) }
                    positionRow("Foot (shared)", value: draft.foot) { draft.setFoot($0) }
                }
                Section("Firmness") {
                    Stepper(value: Binding(get: { draft.sleepNumber }, set: { draft.setSleepNumber($0) }),
                            in: SleepNumber.range, step: SleepNumber.step) {
                        HStack {
                            Text("Sleep Number")
                            Spacer()
                            Text("\(draft.sleepNumber)").font(.headline.monospacedDigit())
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "New favorite" : "Edit favorite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var saved = draft
                        saved.name = BedFavorite.cleanName(name)
                        onSave(saved)
                        dismiss()
                    }
                }
            }
        }
    }

    private func positionRow(_ title: String, value: Int, set: @escaping (Int) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text("\(value)%").font(.headline.monospacedDigit())
            }
            Slider(value: Binding(get: { Double(value) }, set: { set(Int($0.rounded())) }), in: 0...100, step: 1)
                .accessibilityLabel(title)
                .accessibilityValue("\(value) percent")
        }
    }
}

/// Live progress of a favorite being applied, with a Cancel button (STOP also cancels).
struct FavoriteProgressCard: View {
    @Environment(BedController.self) private var controller
    let run: BedController.FavoriteRun

    var body: some View {
        HStack(spacing: 12) {
            ProgressView(value: Double(max(run.stepIndex - 1, 0)), total: Double(run.stepCount))
                .progressViewStyle(.circular)
            VStack(alignment: .leading, spacing: 2) {
                Text("Applying \(run.favorite.name) (\(run.side.title))").font(.subheadline.weight(.semibold))
                Text("Step \(run.stepIndex) of \(run.stepCount): \(run.status)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Cancel", role: .destructive) { controller.cancelFavorite() }
                .buttonStyle(.bordered)
        }
        .padding(12)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
    }
}
