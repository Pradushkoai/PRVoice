package voice.features.bookDownload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import voice.core.booksources.BookInfo
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SearchResult
import voice.core.common.rootGraphAs
import voice.core.downloader.DownloadState
import voice.core.downloader.DownloadWithProgress
import voice.core.strings.R as StringsR
import voice.core.ui.R as UiR
import voice.core.ui.icons.VoiceIcons

@Composable
internal fun BookDetailsScreen(result: SearchResult) {
  val viewModel =
    retain(result.url) {
      rootGraphAs<Graph>().bookDetailsViewModelFactory.create(result)
    }
  BookDetailsScreen(
    loading = viewModel.loading,
    failed = viewModel.failed,
    content = viewModel.content,
    download = viewModel.download(),
    onBackClick = viewModel::onBackClick,
    onRetry = viewModel::load,
    onDownloadClick = viewModel::onDownloadClick,
    onPauseClick = viewModel::onPauseClick,
    onResumeClick = viewModel::onResumeClick,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookDetailsScreen(
  loading: Boolean,
  failed: Boolean,
  content: BookInfo?,
  download: DownloadWithProgress?,
  onBackClick: () -> Unit,
  onRetry: () -> Unit,
  onDownloadClick: () -> Unit,
  onPauseClick: () -> Unit,
  onResumeClick: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(content?.result?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
          IconButton(onClick = onBackClick) {
            Icon(VoiceIcons.ArrowBack, contentDescription = null)
          }
        },
      )
    },
  ) { padding ->
    Box(Modifier.padding(padding).fillMaxSize()) {
      when {
        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        failed ->
          Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(
              stringResource(StringsR.string.bookdownload_error_network),
              style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onRetry) {
              Text(stringResource(StringsR.string.bookdownload_downloads_resume))
            }
          }
        content != null ->
          BookDetailsContent(
            content = content,
            download = download,
            onDownloadClick = onDownloadClick,
            onPauseClick = onPauseClick,
            onResumeClick = onResumeClick,
          )
      }
    }
  }
}

@Composable
private fun BookDetailsContent(
  content: BookInfo,
  download: DownloadWithProgress?,
  onDownloadClick: () -> Unit,
  onPauseClick: () -> Unit,
  onResumeClick: () -> Unit,
) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      AsyncImage(
        model = content.result.coverUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(12.dp)),
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
        error = painterResource(UiR.drawable.album_art),
        contentScale = ContentScale.Crop,
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(content.result.title, style = MaterialTheme.typography.titleLarge)
        content.result.author?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        content.result.narrator?.let { narrator ->
          Text(
            stringResource(StringsR.string.library_search_match_narrator, narrator),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        listOfNotNull(content.year, content.duration, content.series)
          .joinToString(" · ")
          .takeIf { it.isNotEmpty() }
          ?.let {
            Text(
              it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
      }
    }

    content.description?.let { description ->
      Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
      )
    }

    DownloadSection(
      content = content,
      download = download,
      onDownloadClick = onDownloadClick,
      onPauseClick = onPauseClick,
      onResumeClick = onResumeClick,
    )
  }
}

@Composable
private fun DownloadSection(
  content: BookInfo,
  download: DownloadWithProgress?,
  onDownloadClick: () -> Unit,
  onPauseClick: () -> Unit,
  onResumeClick: () -> Unit,
) {
  val torrent = content.files is DownloadSpec.Torrent
  when {
    download == null -> {
      if (torrent) {
        Button(onClick = onDownloadClick, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(StringsR.string.bookdownload_details_torrent_download))
        }
      } else {
        val trackCount = (content.files as DownloadSpec.HttpFiles).tracks.size
        Button(onClick = onDownloadClick, modifier = Modifier.fillMaxWidth()) {
          Text(
            stringResource(StringsR.string.bookdownload_details_download) +
              " · " +
              stringResource(StringsR.string.bookdownload_details_files, trackCount),
          )
        }
      }
    }
    download.download.state == DownloadState.DONE ->
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          VoiceIcons.DownloadDone,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
          stringResource(StringsR.string.bookdownload_details_in_library),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
      }
    else -> {
      val state = download.download.state
      LinearProgressIndicator(
        progress = { download.fraction },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
      )
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val text =
          when (state) {
            DownloadState.QUEUED,
            DownloadState.RUNNING ->
              if (download.download.torrent != null) {
                listOfNotNull(
                    stringResource(
                      StringsR.string.bookdownload_details_progress_bytes,
                      formatBytes(download.download.progressBytes),
                      formatBytes(download.download.totalBytes ?: 0),
                    ),
                    download.seeds?.let { seeds ->
                      download.peers?.let { peers ->
                        stringResource(StringsR.string.bookdownload_details_peers, seeds, peers)
                      }
                    },
                  )
                  .joinToString(" · ")
              } else {
                stringResource(
                  StringsR.string.bookdownload_details_progress,
                  download.download.completedFiles,
                  download.download.files.size,
                )
              }
            DownloadState.RESOLVING ->
              stringResource(StringsR.string.bookdownload_details_resolving)
            else -> download.download.error?.let { errorText(it) } ?: ""
          }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        when (state) {
          DownloadState.QUEUED,
          DownloadState.RUNNING,
          DownloadState.RESOLVING ->
            IconButton(onClick = onPauseClick) {
              Icon(
                VoiceIcons.Pause,
                contentDescription = stringResource(StringsR.string.bookdownload_downloads_pause),
              )
            }
          else ->
            OutlinedButton(onClick = onResumeClick) {
              Text(stringResource(StringsR.string.bookdownload_downloads_resume))
            }
        }
      }
    }
  }
}

/** Formats a byte count without pulling in a formatter, e.g. `12,3 МБ`. */
internal fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes Б"
  val units = listOf("КБ", "МБ", "ГБ")
  var value = bytes.toDouble()
  var unit = "Б"
  for (candidate in units) {
    if (value < 1024) break
    value /= 1024
    unit = candidate
  }
  return String.format(java.util.Locale.getDefault(), "%.1f %s", value, unit)
}

@Composable
internal fun errorText(error: voice.core.downloader.DownloadError): String =
  when (error) {
    voice.core.downloader.DownloadError.NETWORK ->
      stringResource(StringsR.string.bookdownload_error_network)
    voice.core.downloader.DownloadError.CHANGED ->
      stringResource(StringsR.string.bookdownload_error_changed)
    voice.core.downloader.DownloadError.NO_FOLDER ->
      stringResource(StringsR.string.bookdownload_error_no_folder)
    voice.core.downloader.DownloadError.NO_METADATA ->
      stringResource(StringsR.string.bookdownload_error_no_metadata)
    voice.core.downloader.DownloadError.UNKNOWN ->
      stringResource(StringsR.string.bookdownload_error_unknown)
  }
