package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.delay

/**
 * The Boomio board's one parent catalog.
 *
 * A profile's Home ribbon is normally N catalogs, one per row, and their order is
 * the order the manifest lists them in. The parent collapses that into a single
 * entry whose *content* is one tile per row: a tile's meta id IS that row's catalog
 * id. So the row lineup, row order and row names all become server content -- a
 * daily board change needs no new catalog ids and no cold launch.
 *
 * This mirrors the TV client (HomeHub.kt there). The addon only publishes the parent
 * while its own hub flag is on, so against a board that does not offer it this is
 * inert and Home behaves exactly as before.
 */
const val REC_HOME_HUB_CATALOG_ID = "rec-_home"

/** One row of the parent: [id] is that row's own catalog id. */
data class HomeHubTile(val id: String, val name: String, val type: String)

/**
 * Fetched parent catalogs, keyed by the addon's manifest URL.
 *
 * Held outside the repository because the answer is per *addon*, not per Home
 * refresh: it changes when the board changes, not when the user changes their row
 * order. [ensureLoaded] re-fetches whenever the set of parent-bearing addons
 * changes, and is a no-op otherwise.
 */
object HomeHub {
    private const val FETCH_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_500L
    private const val TILE_LIMIT = 80

    private var tilesByManifestUrl: Map<String, List<HomeHubTile>> = emptyMap()
    private var loadedSignature: String? = null

    fun snapshot(): Map<String, List<HomeHubTile>> = tilesByManifestUrl

    /** The row lineup as a string, for callers that cache work keyed on it. */
    fun lineupSignature(): String = tilesByManifestUrl.entries.joinToString("|") { (manifestUrl, tiles) ->
        manifestUrl + "=" + tiles.joinToString(",") { tile -> tile.id }
    }

    fun clear() {
        tilesByManifestUrl = emptyMap()
        loadedSignature = null
    }

    /**
     * Fetch every installed parent and remember its tiles. Returns true when the
     * answer changed, i.e. when the caller should rebuild its row definitions.
     *
     * A parent that cannot be fetched is simply absent from the map: it then shows
     * as one ordinary row of tile cards, which is what a build without this support
     * does. An empty Home would be the worse failure.
     */
    suspend fun ensureLoaded(addons: List<ManagedAddon>, force: Boolean = false): Boolean {
        val parents = addons.enabledAddons().mapNotNull { addon ->
            val catalog = addon.manifest?.catalogs?.firstOrNull { it.id == REC_HOME_HUB_CATALOG_ID }
                ?: return@mapNotNull null
            addon to catalog
        }
        val signature = parents.joinToString("|") { (addon, catalog) ->
            "${addon.manifestUrl}#${catalog.type}"
        }
        if (!force && loadedSignature == signature) return false

        val loaded = buildMap {
            parents.forEach { (addon, catalog) ->
                val tiles = fetchTiles(addon, catalog)
                if (!tiles.isNullOrEmpty()) put(addon.manifestUrl, tiles)
            }
        }
        val changed = loaded != tilesByManifestUrl
        tilesByManifestUrl = loaded
        loadedSignature = signature
        return changed
    }

    private suspend fun fetchTiles(addon: ManagedAddon, catalog: AddonCatalog): List<HomeHubTile>? {
        repeat(FETCH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val page = runCatching {
                fetchCatalogPage(
                    manifestUrl = addon.manifestUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    maxItems = TILE_LIMIT,
                )
            }.getOrNull() ?: return@repeat
            // An empty parent is an answer (a board with no rows today), not a failure.
            return page.items.map { meta -> HomeHubTile(id = meta.id, name = meta.name, type = meta.type) }
        }
        return null
    }
}

/**
 * The catalogs [catalog] stands for when it is a parent, or null when it is not
 * one -- or is one whose tiles are unknown, in which case it stays an ordinary row.
 *
 * A tile id normally names a catalog the addon already publishes, and the
 * manifest's own descriptor for it is used verbatim: that carries the row's real
 * type and extras. A tile the manifest does not list (the board moved between the
 * manifest fetch and the tile fetch) is synthesised from the parent.
 */
internal fun expandHomeHubCatalog(
    manifest: AddonManifest,
    catalog: AddonCatalog,
    tiles: List<HomeHubTile>,
): List<AddonCatalog>? {
    if (catalog.id != REC_HOME_HUB_CATALOG_ID || tiles.isEmpty()) return null
    val published = manifest.catalogs.associateBy { it.id }
    return tiles.map { tile ->
        published[tile.id] ?: catalog.copy(id = tile.id, name = tile.name, type = tile.type)
    }
}

/** `hub-<base>movie` and `hub-<base>series` are the same door, split by kind. */
internal fun hubGroupBaseOf(catalogId: String): String? {
    if (!catalogId.startsWith("hub-")) return null
    val id = catalogId.removePrefix("hub-")
    if (id.isEmpty()) return null
    val base = when {
        id.endsWith("series") -> id.removeSuffix("series")
        id.endsWith("movie") -> id.removeSuffix("movie")
        id.endsWith("tv") -> id.removeSuffix("tv")
        else -> return null
    }
    return base.takeIf { it.isNotEmpty() && !it.endsWith('-') }
}

// Keeps a merged door to one screenful: the pair can carry 64 items each.
private const val HUB_MERGED_ITEM_LIMIT = 60

/**
 * Shows each `hub-` movie/series pair as ONE row, at the first sibling's position,
 * with the kinds interleaved and the drawer of the leader's catalog behind "open".
 *
 * Without this Home reads as doubled: "Action" (movies) and "Action" (series) as
 * two rows with the same label and near-identical artwork.
 *
 * [mergedTitleByKey] supplies the label the merged row should carry -- the bare
 * catalog name, so the "(Movies)"/"(Series)" suffix the pair used to tell itself
 * apart is dropped. The leader's own key survives, so the row still opens the
 * leader's catalog and the settings screen still has one row to order.
 */
internal fun collapseHubSiblingSections(
    sections: List<HomeCatalogSection>,
    mergedTitleByKey: Map<String, String>,
): List<HomeCatalogSection> {
    if (sections.size < 2) return sections

    val groups = LinkedHashMap<String, MutableList<HomeCatalogSection>>()
    sections.forEach { section ->
        val catalogId = (section.target as? CatalogTarget.Addon)?.catalogId ?: return@forEach
        val base = hubGroupBaseOf(catalogId) ?: return@forEach
        groups.getOrPut(base) { mutableListOf() } += section
    }
    val merges = groups.filterValues { it.size >= 2 }
    if (merges.isEmpty()) return sections

    val mergedByLeaderKey = HashMap<String, HomeCatalogSection>()
    val foldedKeys = HashSet<String>()
    merges.values.forEach { members ->
        mergedByLeaderKey[members.first().key] = mergeHubSections(members, mergedTitleByKey)
        members.drop(1).forEach { foldedKeys += it.key }
    }

    return sections.mapNotNull { section ->
        when {
            section.key in foldedKeys -> null
            else -> mergedByLeaderKey[section.key] ?: section
        }
    }
}

private fun mergeHubSections(
    members: List<HomeCatalogSection>,
    mergedTitleByKey: Map<String, String>,
): HomeCatalogSection {
    val leader = members.first()
    val merged = LinkedHashMap<String, MetaPreview>()
    var index = 0
    val deepest = members.maxOf { it.items.size }
    while (index < deepest && merged.size < HUB_MERGED_ITEM_LIMIT) {
        members.forEach { section ->
            section.items.getOrNull(index)?.let { item ->
                merged.putIfAbsent("${item.type}:${item.id}", item)
            }
            if (merged.size >= HUB_MERGED_ITEM_LIMIT) return@forEach
        }
        index++
    }
    return leader.copy(
        title = mergedTitleByKey[leader.key]?.takeIf { it.isNotBlank() } ?: leader.title,
        items = merged.values.toList(),
        availableItemCount = members.sumOf { it.availableItemCount },
        hasMore = members.any { it.hasMore },
    )
}
