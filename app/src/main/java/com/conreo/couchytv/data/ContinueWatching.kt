@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.conreo.couchytv.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.tvprovider.media.tv.TvContractCompat
import com.conreo.couchytv.ui.tileColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** android.Manifest.permission.READ_TV_LISTINGS is hidden in the SDK — the
 *  contract string is stable and the manifest declares it by the same name. */
private const val PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

/*
 * Column names from TvContract.ProgramColumns. In androidx.tvprovider that
 * interface is @RestrictTo(LIBRARY_GROUP_PREFIX), so referring to its
 * constants fails the release lint (`RestrictedApi`), even though the values
 * are part of the public framework `android.media.tv.TvContract` contract and
 * have been stable since the table was introduced. Using the literal names
 * keeps the release build lint-clean without a blanket suppression.
 */
private const val COL_TITLE = "title"
private const val COL_POSTER_ART_URI = "poster_art_uri"
private const val COL_THUMBNAIL_URI = "thumbnail_uri"
private const val COL_SEASON_DISPLAY_NUMBER = "season_display_number"
private const val COL_EPISODE_DISPLAY_NUMBER = "episode_display_number"

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
    /** preview-channel rows only: the channel this card came from (0 for Watch Next) */
    val channelId: Long = 0L,
    /** preview-channel rows only: the channel's display name (null for Watch Next) */
    val channelName: String? = null,
)

/** One preview channel published by an app (the "Channels on the home screen" API). */
@Immutable
data class CwChannel(
    val channelId: Long,
    val name: String,
    val cards: Int,
)

/**
 * An installed app that publishes continue-watching content, grouped for the
 * settings screen: Watch Next rows (one toggle per app) plus the preview
 * channels it publishes (one toggle per channel — a channel can be "History"
 * or "Popular", and only the user knows which ones belong in the row).
 */
@Immutable
data class CwSource(
    val pkg: String,
    val watchNext: Int,
    val channels: List<CwChannel>,
) {
    val isEmpty: Boolean get() = watchNext == 0 && channels.isEmpty()
}

/** Top of the per-app card cap selector: "All", then 1..[CW_APP_LIMIT_MAX]. */
const val CW_APP_LIMIT_MAX = 50

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

    /**
     * URI -> its own Compose state. A decoded poster updates exactly one of
     * these, so only the card showing that URI recomposes. The previous
     * screen-wide `StateFlow<Map>` made *every* finished download recompose the
     * whole launcher — measured as ~48% janky frames while scrolling.
     *
     * Access-ordered LRU: the map is capped, and evicting an entry only drops
     * the reference; a card currently composed keeps its State (and bitmap)
     * until it leaves the screen, then the GC can reclaim it.
     */
    private val posterStates =
        object : LinkedHashMap<String, MutableState<ImageBitmap?>>(64, 0.75f, true) {}
    /** Guards the access-ordered map (main thread composes, IO thread decodes). */
    private val posterLock = Any()
    private const val MAX_CACHED_POSTERS = 80

    /** Posters fetched on a small background pool — never the main thread. */
    private val posterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    /** URI -> last failed attempt; keeps a dead CDN from being hammered on every resume. */
    private val failedAt = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private const val RETRY_BACKOFF_MS = 5 * 60_000L

    @Volatile private var unsupported = false
    /** Projection that worked on this device — remembered so later refreshes skip the tiers. */
    @Volatile private var workingProjection: Array<String>? = null
    /** Same, for the preview-channel table (independent: one may fail alone). */
    @Volatile private var previewProjection: Array<String>? = null
    @Volatile private var previewUnsupported = false
    /** Last list handed out — returned as-is when unchanged, so Compose skips the row. */
    @Volatile private var lastList: List<ContinueEntry>? = null

    private val PROJECTION_TIERS = listOf(
        // Everything we read. The API-26-only columns come first: if the device
        // is new enough this tier wins and we get progress + episode info.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
            TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI,
            COL_POSTER_ART_URI,
            COL_THUMBNAIL_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContractCompat.PreviewProgramColumns.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContractCompat.PreviewProgramColumns.COLUMN_DURATION_MILLIS,
            COL_SEASON_DISPLAY_NUMBER,
            COL_EPISODE_DISPLAY_NUMBER,
        ),
        // Pre-O providers: title/art/intent only, no progress, no episode info.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
            TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI,
            COL_POSTER_ART_URI,
            COL_THUMBNAIL_URI,
        ),
        // Last resort — a text-only row beats no row at all.
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
        ),
    )

    /**
     * Preview-channel projections, tiered the same way. These rows come from
     * the app-owned "channels on the home screen" API — many apps publish
     * their history/bookmarks/popular lists there instead of Watch Next.
     */
    private val PREVIEW_TIERS = listOf(
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
            TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI,
            COL_POSTER_ART_URI,
            COL_THUMBNAIL_URI,
            COL_SEASON_DISPLAY_NUMBER,
            COL_EPISODE_DISPLAY_NUMBER,
            TvContractCompat.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContractCompat.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContractCompat.PreviewPrograms.COLUMN_BROWSABLE,
            TvContractCompat.PreviewPrograms.COLUMN_WEIGHT,
        ),
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
            TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI,
            COL_POSTER_ART_URI,
            COL_THUMBNAIL_URI,
        ),
        arrayOf(
            BaseColumns._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME,
            COL_TITLE,
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
     * The cards to show: installed sources the user has not disabled, capped
     * at [count].
     *
     * Two surfaces are merged:
     *  • Watch Next — one system-managed row, with real engagement timestamps;
     *  • preview channels — app-owned rows, with no timestamps at all.
     *
     * Because the preview rows carry no recency signal and one app may publish
     * hundreds of them, each app's candidates are capped by its per-app limit
     * (config.continueAppLimits). The row is then shared fairly between apps
     * (one card per app per pass) but each app's cards are rendered as one
     * **contiguous block** — all of app A's cards, then all of app B's, etc.
     *
     * Call from a background dispatcher (it does its own IO hop anyway).
     */
    suspend fun load(
        context: Context,
        config: LauncherConfig,
        installed: Set<String>,
        count: Int,
    ): List<ContinueEntry> {
        if (!config.continueWatching || count <= 0) return emptyList()
        if (!hasPermission(context) || unsupported) return emptyList()

        val disabledApps = config.continueSources
        val disabledChannels = config.continueChannels

        fun eligible(pkg: String, title: String): Boolean =
            pkg != context.packageName && pkg in installed && title.isNotBlank()

        val watchNext = (queryRows(context) ?: emptyList()).asSequence()
            .filter { eligible(it.pkg, it.title) && it.pkg !in disabledApps }
            .sortedByDescending { it.stamp }
            .toList()

        // Preview channels are governed ONLY by the per-channel toggles. The
        // per-app switch is labelled "Watch Next" and must not hide preview
        // rows: an app can publish history/bookmarks channels while its Watch
        // Next surface is switched off (the row would otherwise vanish no
        // matter which channels the user enabled).
        val previews = (queryPreviewRows(context) ?: emptyList()).asSequence()
            .filter {
                eligible(it.pkg, it.title) &&
                    (it.channelId == 0L || "${it.pkg}|${it.channelId}" !in disabledChannels)
            }
            // Rows are ordered per channel below; keep a stable base order.
            .sortedBy { it.id }
            .toList()

        // Per-app candidate streams. Watch Next first (it has real recency),
        // then this app's preview channels, newest-created channel first.
        val wnByApp = watchNext.groupBy { it.pkg }
        val previewByApp = previews.groupBy { it.pkg }
        val apps = (wnByApp.keys + previewByApp.keys).distinct()
        // Drop a title duplicated by two surfaces (Watch Next wins: it's first
        // in the stream), so the same movie doesn't take two cards.
        val seen = HashSet<String>()
        val streams = apps.map { pkg ->
            val wn = wnByApp[pkg].orEmpty()
            val channels = previewByApp[pkg].orEmpty()
                .groupBy { it.channelId }
                .toSortedMap(compareByDescending { it })
                .values
                .flatMap { rows ->
                    // Take the channel from its BEGINNING. Publishers order
                    // their list with `weight` (higher = earlier, e.g. NUM:
                    // 100..1); when that hint is absent, the provider's own
                    // insertion order is the list — not its tail.
                    if (rows.any { it.weight != null }) {
                        rows.sortedWith(
                            compareByDescending<RawRow> { it.weight ?: 0L }.thenBy { it.id }
                        )
                    } else {
                        rows.sortedBy { it.id }
                    }
                }
            val candidates = (wn + channels).filter {
                seen.add(it.pkg + "|" + it.title.lowercase(java.util.Locale.ROOT))
            }
            // Per-app cap: the user can say "at most N cards from this app".
            val cap = config.continueAppLimits[pkg]
            pkg to if (cap != null && cap > 0 && cap < candidates.size) {
                candidates.take(cap)
            } else candidates
        }.filter { it.second.isNotEmpty() }
            .sortedWith(
                compareByDescending<Pair<String, List<RawRow>>> {
                    it.second.firstOrNull()?.stamp ?: 0L
                }.thenBy { it.first }
            )

        // Layout: cards stay in **contiguous per-app blocks**, but the row is
        // shared fairly between apps. A plain sequential fill lets an early,
        // uncapped publisher (a channel with 100 rows) consume the whole row,
        // so apps later in the order — e.g. one whose Watch Next is disabled
        // and whose channels therefore carry no timestamp — would never show.
        // Fair-share quotas (one card per app per pass, bounded by each app's
        // block) decide HOW MANY cards each app gets; the block order then
        // decides WHERE they go, keeping every app's cards adjacent.
        val quota = LinkedHashMap<String, Int>()
        var remaining = count
        while (remaining > 0) {
            var added = false
            for ((pkg, stream) in streams) {
                if ((quota[pkg] ?: 0) >= stream.size) continue
                quota[pkg] = (quota[pkg] ?: 0) + 1
                remaining--
                added = true
                if (remaining == 0) break
            }
            if (!added) break
        }

        val picked = ArrayList<RawRow>(count)
        for ((pkg, stream) in streams) {
            val n = quota[pkg] ?: 0
            if (n > 0) picked.addAll(stream.subList(0, n))
        }

        val fresh = picked.map(::toEntry)
        // Same content → SAME list instance → nothing recomposes.
        val prev = lastList
        return (if (prev != null && fresh == prev) prev else fresh).also { lastList = it }
    }

    /**
     * Which apps publish continue-watching content right now, grouped for the
     * settings screen: Watch Next row counts and per-channel preview counts.
     */
    suspend fun sources(context: Context): List<CwSource> {
        if (!hasPermission(context) || unsupported) return emptyList()
        val watchNext = (queryRows(context) ?: emptyList())
            .filter { it.pkg != context.packageName && it.title.isNotBlank() }
            .groupingBy { it.pkg }
            .eachCount()
        val previews = (queryPreviewRows(context) ?: emptyList())
            .filter { it.pkg != context.packageName && it.title.isNotBlank() }

        val packages = (watchNext.keys + previews.map { it.pkg }.distinct()).distinct()
        return packages.map { pkg ->
            CwSource(
                pkg = pkg,
                watchNext = watchNext[pkg] ?: 0,
                channels = previews.filter { it.pkg == pkg }
                    .groupBy { it.channelId }
                    .map { (channelId, rows) ->
                        CwChannel(
                            channelId = channelId,
                            name = rows.firstOrNull()?.channelName
                                ?.ifBlank { null } ?: channelId.toString(),
                            cards = rows.size,
                        )
                    }
                    .sortedBy { it.channelId },
            )
        }.filter { !it.isEmpty }
            .sortedWith(compareByDescending<CwSource> { it.watchNext }.thenBy { it.pkg })
    }

    // ---------------------------------------------------------------- posters

    /**
     * The state a composed card observes. Touching the LRU keeps freshly shown
     * artwork cached; the cap bounds memory (previously it grew forever).
     */
    private fun posterState(uri: String): MutableState<ImageBitmap?> =
        synchronized(posterLock) {
            posterStates[uri]?.let { return it }
            val state = mutableStateOf<ImageBitmap?>(null)
            posterStates[uri] = state
            if (posterStates.size > MAX_CACHED_POSTERS) {
                val eldest = posterStates.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove() // live compositions keep their own reference
                }
            }
            state
        }

    /** Already-decoded artwork for [uri], if any (LRU-touched). */
    private fun cachedPoster(uri: String): ImageBitmap? =
        synchronized(posterLock) { posterStates[uri]?.value }

    /**
     * Compose helper: artwork for [uri], or null while it is still loading.
     * Reading this inside [ContinueCard] scopes invalidation to that one card.
     */
    @Composable
    fun rememberPoster(uri: String?): ImageBitmap? {
        if (uri == null) return null
        return remember(uri) { posterState(uri) }.value
    }

    /**
     * Queue one poster fetch. Called by a card as it enters composition, so
     * only visible artwork is downloaded — no 50-image burst on resume.
     * [widthPx] is the card's actual pixel width, so we decode no more pixels
     * than get painted.
     */
    fun requestPoster(context: Context, uri: String, widthPx: Int) {
        if (cachedPoster(uri) != null) return
        val now = System.currentTimeMillis()
        if (now - (failedAt[uri] ?: 0L) < RETRY_BACKOFF_MS) return
        if (!inFlight.add(uri)) return
        val app = context.applicationContext
        posterScope.launch {
            try {
                fetchPoster(app, uri, widthPx)
            } finally {
                inFlight.remove(uri)
            }
        }
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
        val channelId: Long = 0L,
        val channelName: String? = null,
        /** publisher's ordering hint inside its channel (higher = earlier), null = unset */
        val weight: Long? = null,
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

    /**
     * Preview-channel rows, with channel display names resolved in a second
     * (small) query. Returns empty on failure: this surface is optional, and
     * a device without it must still get the Watch Next row.
     */
    private fun queryPreviewRows(context: Context): List<RawRow>? {
        if (previewUnsupported) return null
        val resolver = context.contentResolver
        val tiers = previewProjection?.let { listOf(it) } ?: PREVIEW_TIERS
        for (projection in tiers) {
            val rows = runCatching {
                resolver.query(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    projection, null, null, null,
                )?.use { cursor -> readPreviewRows(cursor) }
            }.getOrNull() ?: continue
            previewProjection = projection
            val names = channelNames(context)
            return rows.map { it.copy(channelName = names[it.channelId]) }
        }
        previewUnsupported = true
        return null
    }

    /** channelId -> display name. Queried once per refresh; tables are small. */
    private fun channelNames(context: Context): Map<Long, String> = runCatching {
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val out = HashMap<Long, String>()
            if (c.moveToFirst()) do {
                out[c.getLong(0)] = c.getString(1) ?: ""
            } while (c.moveToNext())
            out
        }
    }.getOrNull() ?: emptyMap()

    private fun readRows(c: Cursor): List<RawRow> {
        if (!c.moveToFirst()) return emptyList()
        val rows = ArrayList<RawRow>(c.count)
        do {
            rows += RawRow(
                id = c.num(BaseColumns._ID),
                pkg = c.str(TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME).orEmpty(),
                title = c.str(COL_TITLE).orEmpty(),
                season = c.str(COL_SEASON_DISPLAY_NUMBER),
                episode = c.str(COL_EPISODE_DISPLAY_NUMBER),
                progress = progressOf(c),
                posterUri = c.str(COL_POSTER_ART_URI)
                    ?.ifBlank { null }
                    ?: c.str(COL_THUMBNAIL_URI)
                        ?.ifBlank { null },
                stamp = c.num(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
                intentUri = c.str(TvContractCompat.PreviewProgramColumns.COLUMN_INTENT_URI)
                    ?.ifBlank { null },
            )
        } while (c.moveToNext())
        return rows
    }

    private fun readPreviewRows(c: Cursor): List<RawRow> {
        if (!c.moveToFirst()) return emptyList()
        val rows = ArrayList<RawRow>(c.count)
        do {
            // browsable=0 rows exist but are hidden from the home screen; skip
            // them rather than surfacing something the publisher marked private.
            if (c.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_BROWSABLE) >= 0 &&
                c.num(TvContractCompat.PreviewPrograms.COLUMN_BROWSABLE) == 0L
            ) continue
            rows += RawRow(
                id = c.num(BaseColumns._ID),
                pkg = c.str(TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME).orEmpty(),
                title = c.str(COL_TITLE).orEmpty(),
                season = c.str(COL_SEASON_DISPLAY_NUMBER),
                episode = c.str(COL_EPISODE_DISPLAY_NUMBER),
                progress = progressOf(c),
                posterUri = c.str(COL_POSTER_ART_URI)
                    ?.ifBlank { null }
                    ?: c.str(COL_THUMBNAIL_URI)
                        ?.ifBlank { null },
                // No engagement timestamp on this table — row order is the only hint.
                stamp = 0L,
                intentUri = c.str(TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI)
                    ?.ifBlank { null },
                channelId = c.num(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID),
                weight = c.numOrNull(TvContractCompat.PreviewPrograms.COLUMN_WEIGHT),
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

    private fun Cursor.numOrNull(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun toEntry(r: RawRow) = ContinueEntry(
        key = "${r.pkg}|${r.channelId}|${r.id}",
        pkg = r.pkg,
        title = r.title,
        season = r.season,
        episode = r.episode,
        progress = r.progress,
        posterUri = r.posterUri,
        tile = tileColor(r.pkg),
        intentUri = r.intentUri,
        channelId = r.channelId,
        channelName = r.channelName,
    )

    // ---------------------------------------------------------------- posters

    private fun fetchPoster(context: Context, uri: String, widthPx: Int) {
        // Decode at the card's real pixel width: no more pixels than get
        // painted (the old fixed 270dp target over-decoded small cards).
        val w = widthPx.coerceIn(240, 720)
        val h = w * 9 / 16
        val bmp = loadPoster(context, uri, w, h)
        if (bmp == null) {
            failedAt[uri] = System.currentTimeMillis()
            return
        }
        bmp.prepareToDraw()
        failedAt.remove(uri)
        posterState(uri).value = bmp.asImageBitmap()
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
