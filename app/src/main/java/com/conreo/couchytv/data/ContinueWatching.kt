package com.conreo.couchytv.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.tvprovider.media.tv.TvContractCompat
import com.conreo.couchytv.ui.tileColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** android.Manifest.permission.READ_TV_LISTINGS is hidden in the SDK — the
 *  contract string is stable and the manifest declares it by the same name. */
private const val PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

/**
 * One continue-watching card: a Watch Next row published by an installed app.
 *
 * @Immutable lets Compose skip cards whose data didn't change — the whole row
 * is rebuilt on every resume refresh, and identity-stable entries (see
 * [ContinueWatching.load]) keep that refresh free of recomposition.
 */
@Immutable
data class ContinueEntry(
    /** "$pkg|$rowId|$stamp" — stable LazyRow key, unique per row generation */
    val key: String,
    val pkg: String,
    val title: String,
    /** season number as published (may be non-numeric, e.g. "12B"); null = unknown */
    val season: String?,
    /** episode number as published; null = unknown */
    val episode: String?,
    /** 0..1 playback progress, or null when the row carries no duration */
    val progress: Float?,
    /** resolved artwork: poster art, falling back to the 16:9 thumbnail */
    val posterUri: String?,
    /** tile color precomputed like AppEntry.tile — never hashed in composition */
    val tile: Color,
    /** publisher's `intent_uri` ("intent:" scheme) — resume straight into playback */
    val intentUri: String?,
)

/** A package that currently publishes Watch Next rows, and how many. */
data class CwSource(val pkg: String, val cards: Int)

/**
 * Reads the system's Watch Next ("Play Next" / "Continue watching") table.
 *
 * Constraints imposed by AOSP's TvProvider (verified against its source):
 *  • rows of OTHER packages are only returned to holders of the dangerous
 *    READ_TV_LISTINGS permission (ACCESS_ALL_EPG_DATA is signature|privileged,
 *    so a sideloaded build can never hold it);
 *  • a query with a `selection`/`sortOrder` throws SecurityException without
 *    that privileged permission — everything is filtered and sorted in code;
 *  • some devices lack columns added in API 26, so the projection falls back
 *    tier by tier instead of failing the whole query.
 * Every failure path degrades to "no rows", never to a crash — the row simply
 * stays hidden.
 */
object ContinueWatching {

    /** key (poster URI) -> decoded, ready-to-draw artwork */
    private val posterCache = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val posters: StateFlow<Map<String, ImageBitmap>> = posterCache.asStateFlow()

    /** Posters fetched on a small background pool — never the main thread. */
    private val posterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    /** URI -> last failed attempt; keeps a dead CDN from being hammered on every resume. */
    private val failedAt = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private const val RETRY_BACKOFF_MS = 5 * 60_000L

    @Volatile private var unsupported = false
    /** Projection that worked on this device — remembered so later refreshes skip the tiers. */
    @Volatile private var workingProjection: Array<String>? = null
    /** Last list handed out — returned as-is when unchanged, so Compose skips the row. */
    @Volatile private var lastList: List<ContinueEntry>? = null

    private val PROJECTION_TIERS = listOf(
        // Everything we read. The API-26-only columns come first: if the device
        // is new enough this tier wins and we get progress + episode info.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContractCompat.PreviewProgramColumns.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContractCompat.PreviewProgramColumns.COLUMN_DURATION_MILLIS,
            TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        ),
        // Pre-O providers: title/art/intent only, no progress, no episode info.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
        ),
        // Last resort — a text-only row beats no row at all.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
        ),
    )

    /** Whether the dangerous READ_TV_LISTINGS permission is currently held. */
    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(PERMISSION_READ_TV_LISTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-M: dangerous permissions are granted at install when declared.
            context.checkCallingOrSelfPermission(PERMISSION_READ_TV_LISTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * The cards to show: installed sources the user has not disabled, most
     * recently engaged first, capped at [count]. Call from a background
     * dispatcher (it does its own IO hop anyway).
     */
    suspend fun load(
        context: Context,
        config: LauncherConfig,
        installed: Set<String>,
        count: Int,
    ): List<ContinueEntry> {
        if (!config.continueWatching || count <= 0) return emptyList()
        if (!hasPermission(context) || unsupported) return emptyList()
        val rows = queryRows(context) ?: return emptyList()
        val disabled = config.continueSources
        val fresh = rows.asSequence()
            .filter { it.pkg != context.packageName && it.pkg in installed && it.pkg !in disabled }
            .filter { it.title.isNotBlank() }
            // Newest engagement first; a stable sort keeps provider order among
            // date-less rows (stamp 0) — and pushes them to the end.
            .sortedByDescending { it.stamp }
            .take(count)
            .map(::toEntry)
            .toList()
        // Same content → SAME list instance → nothing recomposes.
        val prev = lastList
        return (if (prev != null && fresh == prev) prev else fresh).also { lastList = it }
    }

    /** Which packages publish Watch Next rows right now (for the settings screen). */
    suspend fun sources(context: Context): List<CwSource> {
        if (!hasPermission(context) || unsupported) return emptyList()
        val rows = queryRows(context) ?: return emptyList()
        return rows.groupBy { it.pkg }
            .map { (pkg, list) -> CwSource(pkg, list.size) }
            .sortedWith(compareByDescending<CwSource> { it.cards }.thenBy { it.pkg })
    }

    /** Queue poster downloads for [entries]; already-cached and backing-off URIs are skipped. */
    fun requestPosters(context: Context, entries: List<ContinueEntry>) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        entries.mapNotNull { it.posterUri }
            .distinct()
            .filter { uri ->
                uri !in posterCache.value &&
                    uri !in inFlight &&
                    (failedAt[uri] ?: 0L).let { now - it >= RETRY_BACKOFF_MS }
            }
            .forEach { uri -> posterScope.launch { fetchPoster(app, uri) } }
    }

    // ------------------------------------------------------------------ query

    private data class RawRow(
        val id: Long,
        val pkg: String,
        val title: String,
        val season: String?,
        val episode: String?,
        val progress: Float?,
        val posterUri: String?,
        val stamp: Long,
        val intentUri: String?,
    )

    /** Runs the query with tiered projections; null = no TV provider / no access. */
    private fun queryRows(context: Context): List<RawRow>? {
        val resolver = context.contentResolver
        val tiers = workingProjection?.let { listOf(it) } ?: PROJECTION_TIERS
        for (projection in tiers) {
            val rows = runCatching {
                resolver.query(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    projection, null, null, null,
                )?.use { cursor -> readRows(cursor) }
            }.getOrNull() ?: continue
            workingProjection = projection
            return rows
        }
        // Every tier failed: no provider, no permission, or a provider we do
        // not understand. Stop trying for the rest of this process — the row
        // stays hidden and costs nothing.
        unsupported = true
        return null
    }

    private fun readRows(c: Cursor): List<RawRow> {
        if (!c.moveToFirst()) return emptyList()
        val rows = ArrayList<RawRow>(c.count)
        do {
            rows += RawRow(
                id = c.num(BaseColumns._ID),
                pkg = c.str(TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME).orEmpty(),
                title = c.str(TvContractCompat.WatchNextPrograms.COLUMN_TITLE).orEmpty(),
                season = c.str(TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                episode = c.str(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                progress = progressOf(c),
                posterUri = c.str(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                    ?.ifBlank { null }
                    ?: c.str(TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                        ?.ifBlank { null },
                stamp = c.num(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
                intentUri = c.str(TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI)
                    ?.ifBlank { null },
            )
        } while (c.moveToNext())
        return rows
    }

    private fun progressOf(c: Cursor): Float? {
        val duration = c.num(TvContractCompat.PreviewProgramColumns.COLUMN_DURATION_MILLIS)
        val position = c.num(TvContractCompat.PreviewProgramColumns.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        return if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else null
    }

    /** Null-safe column reads: a missing column (-1) is simply an absent value. */
    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.num(column: String): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun toEntry(r: RawRow) = ContinueEntry(
        key = "${r.pkg}|${r.id}|${r.stamp}",
        pkg = r.pkg,
        title = r.title,
        season = r.season,
        episode = r.episode,
        progress = r.progress,
        posterUri = r.posterUri,
        tile = tileColor(r.pkg),
        intentUri = r.intentUri,
    )

    // ---------------------------------------------------------------- posters

    /** Same paint budget as banners: 270dp at device density, clamped for RAM. */
    private fun posterTarget(context: Context): Pair<Int, Int> {
        val w = (270 * context.resources.displayMetrics.density).toInt().coerceIn(320, 720)
        return w to w * 9 / 16
    }

    private fun fetchPoster(context: Context, uri: String) {
        if (!inFlight.add(uri)) return
        try {
            val (w, h) = posterTarget(context)
            val bmp = loadPoster(context, uri, w, h)
            if (bmp == null) {
                failedAt[uri] = System.currentTimeMillis()
                return
            }
            bmp.prepareToDraw()
            failedAt.remove(uri)
            posterCache.update { it + (uri to bmp.asImageBitmap()) }
        } finally {
            inFlight.remove(uri)
        }
    }

    /**
     * Artwork -> exactly the 16:9 pixels the card paints, cached as lossless
     * WebP (the AppRepository iconcache recipe). Center-cropping to 16:9 here
     * means the card's ContentScale.Crop is a no-op and the decoded bitmap
     * never holds pixels nobody sees — a 2:3 poster is 1.5× taller than its
     * card, which matters on a 1GB box.
     */
    private fun loadPoster(context: Context, uri: String, w: Int, h: Int): Bitmap? {
        val dir = File(context.filesDir, "postercache").apply { mkdirs() }
        val name = "${Integer.toHexString(uri.hashCode())}-${uri.length}-w$w.webp"
        val cached = File(dir, name)
        if (cached.exists()) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }

        val decoded = openSource(context, uri)?.use { stream -> decodeCenterCrop(stream, w, h) }
            ?: return null
        runCatching {
            cached.outputStream().use { out ->
                if (Build.VERSION.SDK_INT >= 30) {
                    decoded.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    @Suppress("DEPRECATION")
                    decoded.compress(Bitmap.CompressFormat.WEBP, 100, out)
                }
            }
        }
        return decoded
    }

    private fun openSource(context: Context, uri: String): InputStream? = runCatching {
        when (Uri.parse(uri).scheme) {
            "http", "https" -> {
                val conn = URL(uri).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = true
                // Some CDNs 403 an empty/unknown UA; a generic mobile one is
                // what these poster URLs are normally served to.
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Mobile Safari/537.36",
                )
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    null
                } else conn.inputStream // closing it releases the connection
            }
            // content:// from the publisher's own image provider, or
            // android.resource:// — open as a plain stream.
            else -> context.contentResolver.openInputStream(Uri.parse(uri))
        }
    }.getOrNull()

    /**
     * Decodes [stream] near [w] wide and center-crops it to exactly w×h.
     * Bounds-decode first so a 2000px poster becomes one small bitmap instead
     * of a full-size one plus a scaled copy.
     */
    private fun decodeCenterCrop(stream: InputStream, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The stream can't be reset after a bounds decode — buffer it first,
        // bounded so a broken/oversized response can't exhaust the heap.
        val bytes = readBounded(stream, MAX_POSTER_BYTES) ?: return null
        if (bytes.isEmpty()) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, w) }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val scale = w.toFloat() / src.width
        val shownH = (src.height * scale)
        val cropped = if (shownH > h) {
            // Keep the vertical middle band — that's what ContentScale.Crop shows.
            val cropH = (src.height - (h / scale).toInt()).coerceAtLeast(0)
            val y = cropH / 2
            val band = Bitmap.createBitmap(src, 0, y, src.width, src.height - cropH)
            if (band !== src) src.recycle()
            band
        } else src
        val result = if (cropped.width != w || cropped.height != h) {
            Bitmap.createScaledBitmap(cropped, w, h, true).also {
                if (it !== cropped) cropped.recycle()
            }
        } else cropped
        return result
    }

    private const val MAX_POSTER_BYTES = 8 * 1024 * 1024

    private fun readBounded(stream: InputStream, max: Int): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(tmp)
            if (n <= 0) break
            total += n
            if (total > max) return null // oversized: treat as a failed fetch
            out.write(tmp, 0, n)
        }
        out.toByteArray()
    }.getOrNull()

    private fun sampleSize(width: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target) sample *= 2
        return sample
    }
}
