package voice.features.bookDownload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.Inject
import voice.core.common.rootGraphAs
import voice.core.downloader.BookDownloadManager
import voice.core.downloader.DownloadState
import voice.core.downloader.DownloadWithProgress
import voice.core.strings.R as StringsR
import voice.core.ui.icons.VoiceIcons

@Inject
class DownloadsViewModel(private val downloadManager: BookDownloadManager) {

  val downloads = downloadManager.downloads

  fun pause(id: String) = downloadManager.pause(id)

  fun resume(id: String) = downloadManager.resume(id)

  fun delete(id: String) = downloadManager.delete(id)
}

@Composable
internal fun DownloadsScreen() {
  val viewModel = retain { rootGraphAs<Graph>().downloadsViewModel }
  val downloads by viewModel.downloads.collectAsState()
  DownloadsScreen(
    downloads = downloads,
    onPause = viewModel::pause,
    onResume = viewModel::resume,
    onDelete = viewModel::delete,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreen(
  downloads: List<DownloadWithProgress>,
  onPause: (String) -> Unit,
  onResume: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(title = { Text(stringResource(StringsR.string.bookdownload_downloads_title)) })
    },
  ) { padding ->
    if (downloads.isEmpty()) {
      Box(
        Modifier.padding(padding).fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          stringResource(StringsR.string.bookdownload_downloads_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier.padding(padding).fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(downloads, key = { it.download.id }) { download ->
        DownloadRow(
          download = download,
          onPause = onPause,
          onResume = onResume,
          onDelete = onDelete,
        )
      }
    }
  }
}

@Composable
private fun DownloadRow(
  download: DownloadWithProgress,
  onPause: (String) -> Unit,
  onResume: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  val state = download.download.state
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        download.download.title,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      listOfNotNull(download.download.author, download.download.sourceName)
        .joinToString(" · ")
        .let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      when (state) {
        DownloadState.RUNNING,
        DownloadState.QUEUED,
        DownloadState.PAUSED -> {
          LinearProgressIndicator(
            progress = { download.fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            gapSize = 0.dp,
            drawStopIndicator = {},
          )
        }
        else -> {}
      }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val label =
          when (state) {
            DownloadState.QUEUED -> stringResource(StringsR.string.bookdownload_details_queued)
            DownloadState.RUNNING ->
              if (download.download.torrent != null) {
                stringResource(
                  StringsR.string.bookdownload_details_progress_bytes,
                  formatBytes(download.download.progressBytes),
                  formatBytes(download.download.totalBytes ?: 0),
                )
              } else {
                stringResource(
                  StringsR.string.bookdownload_details_progress,
                  download.download.completedFiles,
                  download.download.files.size,
                )
              }
            DownloadState.RESOLVING ->
              stringResource(StringsR.string.bookdownload_details_resolving)
            DownloadState.PAUSED -> stringResource(StringsR.string.bookdownload_downloads_pause)
            DownloadState.FAILED ->
              download.download.error?.let { errorText(it) }
                ?: stringResource(StringsR.string.bookdownload_error_unknown)
            DownloadState.DONE -> stringResource(StringsR.string.bookdownload_details_in_library)
          }
        Text(
          label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
          when (state) {
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            DownloadState.RESOLVING ->
              IconButton(onClick = { onPause(download.download.id) }) {
                Icon(
                  VoiceIcons.Pause,
                  contentDescription = stringResource(StringsR.string.bookdownload_downloads_pause),
                )
              }
            DownloadState.PAUSED,
            DownloadState.FAILED ->
              IconButton(onClick = { onResume(download.download.id) }) {
                Icon(
                  VoiceIcons.PlayArrow,
                  contentDescription =
                    stringResource(StringsR.string.bookdownload_downloads_resume),
                )
              }
            else -> {}
          }
          IconButton(onClick = { onDelete(download.download.id) }) {
            Icon(
              VoiceIcons.Delete,
              contentDescription = stringResource(StringsR.string.bookdownload_downloads_delete),
            )
          }
        }
      }
    }
  }
}
