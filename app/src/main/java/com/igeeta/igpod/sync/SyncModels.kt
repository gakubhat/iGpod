package com.igeeta.igpod.sync

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Server API response models — mirror the JSON returned by the FastAPI server
// ---------------------------------------------------------------------------

data class ServerPlaylist(
    val id: Int,
    val name: String,
    val description: String = "",
    @SerializedName("is_offline") val isOffline: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class ServerPlaylistWithTracks(
    val id: Int,
    val name: String,
    val description: String = "",
    @SerializedName("is_offline") val isOffline: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val tracks: List<ServerTrack> = emptyList(),
)

data class ServerTrack(
    @SerializedName("file_path") val filePath: String,
    @SerializedName("relative_path") val relativePath: String = "",
    @SerializedName("video_id") val videoId: String = "",
    @SerializedName("content_hash") val contentHash: String? = null,
    val genre: String = "",
    @SerializedName("sub_genre") val subGenre: String = "",
    val album: String = "",
    val raag: String = "",
    val title: String = "",
    val artists: List<String> = emptyList(),
    val instruments: List<String> = emptyList(),
    val tags: Map<String, Any> = emptyMap(),
    val duration: Double = 0.0,
    @SerializedName("file_size") val fileSize: Long = 0,
    val bitrate: Int = 0,
    val source: String = "",
    @SerializedName("imported_at") val importedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val rating: Int = 0,
    @SerializedName("play_count") val playCount: Int = 0,
    @SerializedName("last_played") val lastPlayed: String? = null,
    @SerializedName("artwork_url") val artworkUrl: String? = null,
    @SerializedName("track_art_path") val trackArtPath: String = "",
    @SerializedName("chapter_title") val chapterTitle: String = "",
    @SerializedName("chapter_index") val chapterIndex: Int = -1,
    @SerializedName("chapter_start") val chapterStart: Double = -1.0,
    @SerializedName("chapter_end") val chapterEnd: Double = -1.0,
)

data class ServerRatingResponse(
    val rating: Int,
)

data class ServerRaga(
    val name: String = "",
    val mood: String = "",
    val prahara: String = "",
    val season: String = "",
    @SerializedName("varjit_notes") val varjitNotes: String = "",
    @SerializedName("associated_emotions") val associatedEmotions: String = "",
    val description: String = "",
)

// ---------------------------------------------------------------------------
// Sync progress reporting
// ---------------------------------------------------------------------------

enum class SyncPhase {
    CONNECTING,
    FETCHING_PLAYLISTS,
    SYNCING_PLAYLISTS,
    SYNCING_TRACKS,
    SYNCING_RATINGS,
    DONE,
    ERROR,
}

data class SyncProgress(
    val phase: SyncPhase = SyncPhase.CONNECTING,
    val playlistName: String = "",
    val currentTrack: Int = 0,
    val totalTracks: Int = 0,
    val currentAction: String = "",
    val errorMessage: String? = null,
) {
    val percent: Int
        get() = if (totalTracks > 0) (currentTrack * 100) / totalTracks else 0
}

// ---------------------------------------------------------------------------
// Sync configuration
// ---------------------------------------------------------------------------

data class SyncConfig(
    val host: String,
    val port: Int = 8000,
) {
    val baseUrl: String
        get() = "http://$host:$port"

    val isValid: Boolean
        get() = host.isNotBlank() && port in 1..65535
}
