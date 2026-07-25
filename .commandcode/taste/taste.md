# database
- Use SQLite database (SyncDatabase/igeeta_sync.db) as the sole data source instead of MediaStore. Remove/skip the MediaStore path entirely. This applies to ALL data including songs, albums, artists, genres, AND playlists — nothing should come from MediaStore when the DB has data. Confidence: 0.85

# UI preferences
See [ui-preferences/taste.md](ui-preferences/taste.md)
# communication
- Gives extremely terse, direct instructions (often one sentence) and expects the agent to figure out implementation details independently. Does not spell out "how" — only the desired outcome. Confidence: 0.85

# workflow
- Prefers implement-then-test cycle: write code, build, deploy to device, and verify on real hardware — not just compile. Expects agent to proactively use available tooling (e.g., Android MCP) for on-device testing without needing detailed instructions on how. Confidence: 0.7
- Expects Android app (iGpod) to maintain feature parity and schema parity with the iGeeta server. When exploring a server feature, wants it ported to the app. Proactively checks for DB drift between server and app. Confidence: 0.8
- Expects agent to explore sibling project folders in the workspace (e.g., iGeeta server code in a sibling directory to iGpod) to understand data models and features before implementing. Will provide directional hints (e.g., "search in iGeeta folder") when needed. Confidence: 0.8
- Expects agent to proactively audit code quality and look for stale/dead code when reviewing a codebase — will ask for code quality reviews as a standalone task. Confidence: 0.7

# domain
- Music library is Indian classical music (Hindustani). Subgenre (e.g., Hindustani Instrumental, Hindustani Vocal) is the primary meaningful categorization — not top-level genre. Confidence: 0.8
- Raaga (raga) is a first-class taxonomy concept in the library. Raagas have attributes and should be searchable. Tracks should be filterable by one or more raagas. Confidence: 0.85
- Radio is a desired feature: prahara-based (time-of-day), artist-based, instrument-based, and raaga-based radio modes. Server picks tracks statelessly; client manages history/exclusion and prefetch. Confidence: 0.75
- Prefers graceful fallback/degradation when data is incomplete — e.g., if current prahara has no tracks, try adjacent praharas or filter to only available raagas. Wants adaptive logic, not empty results or crashes. Confidence: 0.8
