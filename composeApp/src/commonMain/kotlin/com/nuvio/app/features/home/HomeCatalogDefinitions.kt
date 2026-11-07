package com.nuvio.app.features.home

import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.supportsPagination
import com.nuvio.app.features.servers.ServerCatalog
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_catalog_default_title
import org.jetbrains.compose.resources.getString

data class HomeCatalogDefinition(
    val key: String,
    val defaultTitle: String,
    val catalogName: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val supportsPagination: Boolean,
    val descriptorSignature: String,
    val serverConnectionId: String? = null,
) {
    val cacheKey: String
        get() = "$key|$descriptorSignature"

    fun titleFor(showCatalogType: Boolean): String =
        if (showCatalogType) defaultTitle else catalogName
}

fun buildAddonCatalogRefreshSignature(addons: List<ManagedAddon>): List<String> =
    addons.enabledAddons().map { addon ->
        val signature = CatalogDescriptorSignature()
        signature.addAddon(addon)
        addon.manifest?.let { manifest ->
            signature.addManifest(manifest)
            manifest.catalogs.forEach(signature::addCatalog)
        }
        signature.value()
    }.sorted()

/**
 * Every catalog the addons offer, in manifest order -- which IS Home's default row
 * order -- with the Boomio board's own conventions applied on top:
 *
 *  - a catalog that needs a query to return anything is not a row (the TV client's
 *    rule; without it every "search" catalog renders an empty shelf);
 *  - `rec-<row>-drilldown` catalogs are the "More Like This" drawers behind a row's
 *    trailing tile, never rows themselves -- shown, each row reads as doubled;
 *  - `/row/` addons (the per-profile board) additionally declare more catalogs than
 *    they mean to show, so only the ones Home is for survive;
 *  - `rec-_home`, the board's parent, expands into the rows its tiles name.
 */
fun buildHomeCatalogDefinitions(
    addons: List<ManagedAddon>,
    hubTiles: Map<String, List<HomeHubTile>> = emptyMap(),
): List<HomeCatalogDefinition> =
    addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        addon to manifest
    }.flatMap { (addon, manifest) ->
        val isBoardAddon = isBoardAddonManifest(addon.manifestUrl)
        val tiles = hubTiles[addon.manifestUrl].orEmpty()
        manifest.catalogs
            .filter { catalog -> catalog.extra.none { it.isRequired } }
            .filter { catalog -> !isHomeDrilldownCatalogId(catalog.id) }
            .filter { catalog -> !isBoardAddon || isFeaturedHomeCatalog(catalog) }
            .flatMap { catalog -> expandHomeHubCatalog(manifest, catalog, tiles) ?: listOf(catalog) }
            .map { catalog -> addon.toHomeCatalogDefinition(manifest, catalog) }
    }.distinctBy(HomeCatalogDefinition::key) + ServerCatalog.homeDefinitions()

/** The per-profile Taste board: `{base}/{userId}/row/_board/manifest.json`. */
internal fun isBoardAddonManifest(manifestUrl: String): Boolean =
    manifestUrl.contains("/row/")

internal fun isHomeDrilldownCatalogId(catalogId: String): Boolean =
    catalogId.endsWith(REC_DRILLDOWN_SUFFIX)

internal const val REC_DRILLDOWN_SUFFIX = "-drilldown"

// Per-user rec rows and hub browse rows. Only /row/ addons publish these, so
// nothing from a per-service catalog leaks in behind this test.
private val FEATURED_HOME_CATALOG_ID_PREFIXES = listOf("rec-", "hub-")
private val FEATURED_HOME_CATALOG_IDS = setOf("golden-age")
private val FEATURED_HOME_CATALOG_NAME_KEYWORDS = listOf(
    "popular", "trending", "top", "new", "upcoming", "recent",
    "watchlist", "recommended", "rec", "discover", "hot",
)

/** "anime"/"collection" are real row types the enum cannot name, so match the raw type. */
internal fun isFeaturedHomeCatalog(catalog: AddonCatalog): Boolean {
    val rawType = catalog.type.lowercase()
    if (rawType == "collection" || rawType == "anime") return true
    val lowerName = catalog.name.lowercase()
    return FEATURED_HOME_CATALOG_ID_PREFIXES.any { catalog.id.startsWith(it) } ||
        catalog.id in FEATURED_HOME_CATALOG_IDS ||
        FEATURED_HOME_CATALOG_NAME_KEYWORDS.any { lowerName.contains(it) }
}

private fun ManagedAddon.toHomeCatalogDefinition(
    manifest: AddonManifest,
    catalog: AddonCatalog,
): HomeCatalogDefinition =
    HomeCatalogDefinition(
        key = "${manifest.id}:${catalog.type}:${catalog.id}",
        defaultTitle = runBlocking {
            getString(
                Res.string.home_catalog_default_title,
                catalog.name,
                localizedMediaTypeLabel(catalog.type),
            )
        },
        catalogName = catalog.name,
        addonName = displayTitle,
        manifestUrl = manifestUrl,
        type = catalog.type,
        catalogId = catalog.id,
        supportsPagination = catalog.supportsPagination(),
        descriptorSignature = buildHomeCatalogDescriptorSignature(this, manifest, catalog),
    )

private fun buildHomeCatalogDescriptorSignature(
    addon: ManagedAddon,
    manifest: AddonManifest,
    catalog: AddonCatalog,
): String {
    val signature = CatalogDescriptorSignature()
    signature.addAddon(addon)
    signature.addManifest(manifest)
    signature.addCatalog(catalog)
    return signature.value()
}

private class CatalogDescriptorSignature {
    private var hash = -3750763034362895579L

    fun add(value: String?) {
        val text = value.orEmpty()
        mix(text.length)
        text.forEach { character -> mix(character.code) }
    }

    fun add(value: Boolean) {
        mix(if (value) 1 else 0)
    }

    fun add(value: Int?) {
        mix(value ?: Int.MIN_VALUE)
    }

    fun addAddon(addon: ManagedAddon) {
        add(addon.userSetName)
        add(addon.enabled)
        add(addon.isRefreshing)
        add(addon.errorMessage)
        add(addon.manifestUrl)
    }

    fun addManifest(manifest: AddonManifest) {
        add(manifest.id)
        add(manifest.name)
        add(manifest.version)
        add(manifest.description)
        add(manifest.logoUrl)
        add(manifest.transportUrl)
        manifest.types.forEach(::add)
        manifest.idPrefixes.forEach(::add)
        manifest.resources.forEach { resource ->
            add(resource.name)
            resource.types.forEach(::add)
            resource.idPrefixes.forEach(::add)
        }
        add(manifest.behaviorHints.configurable)
        add(manifest.behaviorHints.configurationRequired)
        add(manifest.behaviorHints.adult)
        add(manifest.behaviorHints.p2p)
    }

    fun addCatalog(catalog: AddonCatalog) {
        add(catalog.type)
        add(catalog.id)
        add(catalog.name)
        add(catalog.supportsPagination())
        catalog.extra.forEach { extra ->
            add(extra.name)
            add(extra.isRequired)
            extra.options.forEach(::add)
            add(extra.optionsLimit)
        }
    }

    fun value(): String = hash.toULong().toString(16)

    private fun mix(value: Int) {
        hash = (hash xor value.toLong()) * 1099511628211L
    }
}

internal fun String.displayLabel(): String = localizedMediaTypeLabel(this)
