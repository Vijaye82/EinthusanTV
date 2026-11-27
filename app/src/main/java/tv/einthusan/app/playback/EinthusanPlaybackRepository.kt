package tv.einthusan.app.playback

import tv.einthusan.app.account.EinthusanAccountRepository

/**
 * Direct, account-bound playback resolver. Einthusan itself decides whether the requested
 * movie and quality are available to the signed-in account.
 */
class EinthusanPlaybackRepository(
    private val account: EinthusanAccountRepository
) : PlaybackRepository {
    override suspend fun resolve(request: PlaybackRequest): PlaybackResult =
        account.resolveMovie(
            movieId = request.movieId,
            language = request.language,
            quality = request.preferredQuality
        )
}
