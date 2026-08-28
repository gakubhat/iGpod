package com.igeeta.igpod.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the iGeeta server API.
 * Mirrors the endpoints used by the web UI.
 */
class IgeetaApi(config: SyncConfig) {

    private val baseUrl = config.baseUrl
    private val gson: Gson = GsonBuilder().create()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // -----------------------------------------------------------------------
    // Connection test
    // -----------------------------------------------------------------------

    /**
     * Test connection by fetching the playlist list.
     * Returns true if the server responds successfully.
     */
    fun testConnection(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/playlists")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // Playlists
    // -----------------------------------------------------------------------

    /**
     * GET /api/playlists — list all playlists.
     */
    fun getPlaylists(): List<ServerPlaylist> {
        val request = Request.Builder()
            .url("$baseUrl/api/playlists")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("GET /api/playlists failed: ${it.code}")
            val body = it.body?.string() ?: throw IOException("Empty response")
            val type = object : TypeToken<List<ServerPlaylist>>() {}.type
            gson.fromJson(body, type)
        }
    }

    /**
     * GET /api/playlists/{id} — get playlist with all tracks.
     */
    fun getPlaylist(id: Int): ServerPlaylistWithTracks {
        val request = Request.Builder()
            .url("$baseUrl/api/playlists/$id")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("GET /api/playlists/$id failed: ${it.code}")
            val body = it.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, ServerPlaylistWithTracks::class.java)
        }
    }

    // -----------------------------------------------------------------------
    // Tracks
    // -----------------------------------------------------------------------

    /**
     * GET /api/track?path=... — get single track detail.
     */
    fun getTrack(path: String): ServerTrack {
        val request = Request.Builder()
            .url("$baseUrl/api/track?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("GET /api/track failed: ${it.code}")
            val body = it.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, ServerTrack::class.java)
        }
    }

    // -----------------------------------------------------------------------
    // Streaming / Download
    // -----------------------------------------------------------------------

    /**
     * GET /api/stream?path=... — stream audio file into [outputStream].
     * Caller is responsible for closing the output stream.
     */
    fun streamTrack(path: String, outputStream: OutputStream) {
        val request = Request.Builder()
            .url("$baseUrl/api/stream?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("GET /api/stream failed: ${response.code}")
        response.body?.byteStream()?.use { input ->
            outputStream.use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
    }

    /**
     * Returns the InputStream for a track download. Caller must close it.
     */
    fun streamTrack(path: String): InputStream {
        val request = Request.Builder()
            .url("$baseUrl/api/stream?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("GET /api/stream failed: ${response.code}")
        return response.body?.byteStream() ?: throw IOException("Empty response body")
    }

    // -----------------------------------------------------------------------
    // Artwork
    // -----------------------------------------------------------------------

    /**
     * GET /api/artwork?path=... — download artwork image into [outputStream].
     */
    fun downloadArtwork(path: String, outputStream: OutputStream) {
        val request = Request.Builder()
            .url("$baseUrl/api/artwork?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("GET /api/artwork failed: ${response.code}")
        response.body?.byteStream()?.use { input ->
            outputStream.use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
    }

    /**
     * Returns the InputStream for an artwork download. Caller must close it.
     */
    fun downloadArtwork(path: String): InputStream {
        val request = Request.Builder()
            .url("$baseUrl/api/artwork?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("GET /api/artwork failed: ${response.code}")
        return response.body?.byteStream() ?: throw IOException("Empty response body")
    }

    // -----------------------------------------------------------------------
    // Rating
    // -----------------------------------------------------------------------

    /**
     * POST /api/track/rating?path=... — set track rating.
     */
    fun setRating(path: String, rating: Int): ServerRatingResponse {
        val jsonBody = gson.toJson(mapOf("rating" to rating))
        val requestBody = jsonBody.toRequestBody(
            "application/json; charset=utf-8".toMediaTypeOrNull()
        )
        val request = Request.Builder()
            .url("$baseUrl/api/track/rating?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("POST /api/track/rating failed: ${it.code}")
            val body = it.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, ServerRatingResponse::class.java)
        }
    }

    // -----------------------------------------------------------------------
    // Raga metadata
    // -----------------------------------------------------------------------

    /**
     * GET /api/raga — list all ragas with metadata.
     */
    fun getRagas(): List<ServerRaga> {
        val request = Request.Builder()
            .url("$baseUrl/api/raga")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("GET /api/raga failed: ${it.code}")
            val body = it.body?.string() ?: throw IOException("Empty response")
            val type = object : TypeToken<List<ServerRaga>>() {}.type
            gson.fromJson(body, type)
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // -----------------------------------------------------------------------
    // UI helper data class
    // -----------------------------------------------------------------------

    data class ServerPlaylistInfo(
        val id: Int,
        val name: String,
        val description: String,
    )
}
