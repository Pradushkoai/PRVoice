package voice.core.booksources

import kotlinx.serialization.Serializable

/**
 * How the files of a book can be obtained. Sources provide one of these; the downloader knows how
 * to handle each.
 */
@Serializable
sealed interface DownloadSpec {

  /** Individual files, downloadable one by one, e.g. chapter mp3s. */
  @Serializable
  data class HttpFiles(
    val tracks: List<Track>,
  ) : DownloadSpec

  /**
   * A torrent, by magnet uri or by the url of a .torrent file, with the files it contains once the
   * metadata is known. [files] may be empty when the source doesn't list them up front.
   */
  @Serializable
  data class Torrent(
    val magnetUri: String? = null,
    val torrentUrl: String? = null,
    val name: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
  ) : DownloadSpec

  companion object {
    fun torrent(
      magnetUri: String? = null,
      torrentUrl: String? = null,
    ): Torrent = Torrent(magnetUri = magnetUri, torrentUrl = torrentUrl)

    fun from(summary: TorrentSummary): Torrent =
      Torrent(
        magnetUri = summary.magnetUri,
        torrentUrl = summary.torrentUrl,
        seeders = summary.seeders,
        leechers = summary.leechers,
      )
  }
}

@Serializable
data class Track(
  /** Absolute url of the file. */
  val url: String,
  /** Name for the downloaded file, without extension, e.g. `01 - Глава_01`. */
  val name: String,
)
