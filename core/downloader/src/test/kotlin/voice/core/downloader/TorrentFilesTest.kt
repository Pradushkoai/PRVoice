package voice.core.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentFilesTest {

  @Test
  fun `audio files are recognized across extensions and folders`() {
    assertTrue("Book/Chapter 01.mp3".isAudioFile())
    assertTrue("book.m4b".isAudioFile())
    assertTrue("Chapter.OGG".isAudioFile())
    assertTrue("01.opus".isAudioFile())
    assertTrue("track.flac".isAudioFile())
  }

  @Test
  fun `junk files of audiobook torrents stay out`() {
    assertFalse("cover.jpg".isAudioFile())
    assertFalse("book.nfo".isAudioFile())
    assertFalse("readme.txt".isAudioFile())
    assertFalse("visit.url".isAudioFile())
    assertFalse("cover".isAudioFile())
    assertFalse("sample.mp3.exe".isAudioFile())
  }

  @Test
  fun `the resolved torrent only counts its audio files`() {
    val resolved =
      ResolvedTorrent(
        infoHash = "abc",
        magnetUri = "magnet:?xt=urn:btih:abc",
        metadataFile = java.io.File("metadata.torrent"),
        files =
          listOf(
            TorrentFile(0, "Book/01.mp3", 100),
            TorrentFile(1, "Book/02.mp3", 200),
            TorrentFile(2, "Book/cover.jpg", 10),
          ),
      )

    assertEquals(listOf("Book/01.mp3", "Book/02.mp3"), resolved.audioFiles.map { it.path })
    assertEquals(300L, resolved.audioBytes)
  }

  @Test
  fun `torrent paths flatten into one file name per book folder`() {
    assertEquals("Book - Chapter 01.mp3", "Book/Chapter 01.mp3".flattenedFileName())
    assertEquals("Chapter 01.mp3", "Chapter 01.mp3".flattenedFileName())
    assertEquals("A - B - c.mp3", "A\\B\\c.mp3".flattenedFileName())
  }

  @Test
  fun `the fraction of a torrent follows its bytes`() {
    val torrent =
      BookDownload(
        id = "id",
        sourceId = "rutor",
        sourceName = "Rutor",
        title = "Book",
        torrent = TorrentRef(magnetUri = "magnet:?xt=urn:btih:abc"),
        totalBytes = 200,
        progressBytes = 100,
        addedAtEpochMillis = 0,
      )
    val withProgress = DownloadWithProgress(torrent)
    assertEquals(0.5F, withProgress.fraction)

    val unknownSize = torrent.copy(totalBytes = null)
    assertEquals(0F, DownloadWithProgress(unknownSize).fraction)
  }

  @Test
  fun `the fraction of an http download follows its files`() {
    val http =
      BookDownload(
        id = "id",
        sourceId = "bazaknig",
        sourceName = "Baza-knig",
        title = "Book",
        files =
          listOf(
            DownloadFile("https://example.com/1.mp3", "01"),
            DownloadFile("https://example.com/2.mp3", "02"),
          ),
        completedFiles = 1,
        addedAtEpochMillis = 0,
      )
    assertEquals(0.5F, DownloadWithProgress(http).fraction)
    // half of the second file is already there
    assertEquals(0.75F, DownloadWithProgress(http, currentFileFraction = 0.5F).fraction)
  }
}
