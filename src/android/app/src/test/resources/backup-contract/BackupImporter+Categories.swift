// SessionRecord from upstream b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c.
struct SessionRecord: Codable {
    let session: ChatSession
    let memoryEnabled: Bool
    let modelBinding: String?
}
