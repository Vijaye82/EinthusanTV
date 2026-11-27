package tv.einthusan.app.catalog

/**
 * Explicit unavailable state until the approved catalogue endpoint/configuration is wired.
 * This prevents the app from presenting fabricated sample titles as real Einthusan content.
 */
class UnconfiguredCatalogRepository : CatalogRepository {
    override suspend fun loadHome(): CatalogResult =
        CatalogResult.Failure("The authorised catalogue feed has not been configured yet.")

    override suspend fun search(query: CatalogQuery): CatalogResult =
        CatalogResult.Failure("The authorised catalogue feed has not been configured yet.")
}
