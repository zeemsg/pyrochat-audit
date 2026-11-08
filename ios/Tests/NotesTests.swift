import XCTest
@testable import PyroChat

@MainActor
final class NotesTests: XCTestCase {

    private func tempStoreURL() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pyro-notes-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("store")
    }

    func testCreateEditPersistReload() throws {
        let url = tempStoreURL()
        let store = SecureStore(creatingWithPassword: "notes-pass-123", at: url)
        let svc = ChatService(store: store)

        let n = svc.createNote()
        svc.updateNoteText(id: n.id, text: "ops log\nrotate keys friday")
        svc.togglePin(id: n.id)
        store.flush()

        let reopened = try SecureStore(unlockingWithPassword: "notes-pass-123", at: url)
        let saved = reopened.blob.notes?.first
        XCTAssertEqual(reopened.blob.notes?.count, 1)
        XCTAssertEqual(saved?.text, "ops log\nrotate keys friday")
        XCTAssertEqual(saved?.title, "ops log")
        XCTAssertEqual(saved?.preview, "rotate keys friday")
        XCTAssertEqual(saved?.isPinned, true)
    }

    func testNotesEncryptedAtRest() throws {
        let url = tempStoreURL()
        let store = SecureStore(creatingWithPassword: "notes-pass-123", at: url)
        let svc = ChatService(store: store)
        svc.updateNoteText(id: svc.createNote().id, text: "the launch code is 0000")
        store.flush()
        let raw = try Data(contentsOf: url)
        XCTAssertFalse(String(decoding: raw, as: UTF8.self).contains("launch code"))
    }

    func testDeleteNote() {
        let store = SecureStore(creatingWithPassword: "p8charss", at: tempStoreURL())
        let svc = ChatService(store: store)
        let n = svc.createNote()
        svc.updateNoteText(id: n.id, text: "doomed")
        svc.deleteNote(id: n.id)
        XCTAssertTrue(svc.notes.isEmpty)
        XCTAssertEqual(store.blob.notes, [])
    }

    func testEmptyNewNoteDiscardedOnExit() {
        let store = SecureStore(creatingWithPassword: "p8charss", at: tempStoreURL())
        let svc = ChatService(store: store)

        let empty = svc.createNote()
        svc.finalizeNote(id: empty.id, isNew: true)
        XCTAssertTrue(svc.notes.isEmpty)

        let ws = svc.createNote()
        svc.updateNoteText(id: ws.id, text: "  \n\n ")
        svc.finalizeNote(id: ws.id, isNew: true)
        XCTAssertTrue(svc.notes.isEmpty)

        let kept = svc.createNote()
        svc.updateNoteText(id: kept.id, text: "keep me")
        svc.finalizeNote(id: kept.id, isNew: true)
        XCTAssertEqual(svc.notes.count, 1)

        // existing (not new) note emptied out is NOT auto-deleted
        svc.updateNoteText(id: kept.id, text: "")
        svc.finalizeNote(id: kept.id, isNew: false)
        XCTAssertEqual(svc.notes.count, 1)
    }

    func testSortPinnedFirstThenModifiedDesc() {
        let store = SecureStore(creatingWithPassword: "p8charss", at: tempStoreURL())
        let svc = ChatService(store: store)
        let a = svc.createNote(); svc.updateNoteText(id: a.id, text: "a")
        let b = svc.createNote(); svc.updateNoteText(id: b.id, text: "b")
        let c = svc.createNote(); svc.updateNoteText(id: c.id, text: "c")
        svc.togglePin(id: a.id)
        let order = svc.sortedNotes.map(\.title)
        XCTAssertEqual(order.first, "a")
        XCTAssertEqual(Array(order.dropFirst()), ["c", "b"])
    }
}
