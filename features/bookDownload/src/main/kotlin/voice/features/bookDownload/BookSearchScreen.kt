package voice.features.bookDownload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import voice.core.booksources.SearchResult
import voice.core.common.rootGraphAs
import voice.core.strings.R as StringsR
import voice.core.ui.R as UiR
import voice.core.ui.icons.VoiceIcons

@Composable
internal fun BookSearchScreen() {
  val viewModel = retain { rootGraphAs<Graph>().bookSearchViewModel }
  BookSearchScreen(
    query = viewModel.query,
    searching = viewModel.searching,
    searched = viewModel.searched,
    results = viewModel.results,
    failedSources = viewModel.failedSources,
    sources = viewModel.sources,
    hasMore = viewModel::hasMore,
    onQueryChange = viewModel::onQueryChange,
    onSearch = viewModel::search,
    onBackClick = viewModel::onBackClick,
    onOpenDownloads = viewModel::onOpenDownloads,
    onResultClick = viewModel::onResultClick,
    onLoadMore = viewModel::loadMore,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSearchScreen(
  query: String,
  searching: Boolean,
  searched: Boolean,
  results: List<SearchResult>,
  failedSources: Set<String>,
  sources: List<voice.core.booksources.BookSource>,
  hasMore: (String) -> Boolean,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onBackClick: () -> Unit,
  onOpenDownloads: () -> Unit,
  onResultClick: (SearchResult) -> Unit,
  onLoadMore: (String) -> Unit,
) {
  val keyboard = LocalSoftwareKeyboardController.current
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.bookdownload_search_title)) },
        navigationIcon = {
          IconButton(onClick = onBackClick) {
            Icon(VoiceIcons.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          IconButton(onClick = onOpenDownloads) {
            Icon(
              VoiceIcons.Download,
              contentDescription = stringResource(StringsR.string.bookdownload_downloads_title),
            )
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(StringsR.string.bookdownload_search_hint)) },
        singleLine = true,
        trailingIcon = {
          if (searching) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
          }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions =
          KeyboardActions(
            onSearch = {
              keyboard?.hide()
              onSearch()
            },
          ),
        shape = RoundedCornerShape(16.dp),
      )
      if (searching && results.isEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      }
      if (failedSources.isNotEmpty()) {
        Text(
          stringResource(StringsR.string.bookdownload_search_source_failed),
          modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
      when {
        results.isEmpty() && searched && !searching ->
          Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
              stringResource(StringsR.string.bookdownload_search_no_results),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        else ->
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(results, key = { it.url }) { result ->
              SearchResultRow(
                result,
                sourceName(result, sources),
                onClick = { onResultClick(result) },
              )
            }
            if (searching && results.isNotEmpty()) {
              item(key = "searching") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
              }
            }
            sources
              .filter { hasMore(it.id) }
              .forEach { source ->
                item(key = "more-${source.id}") {
                  TextButtonRow(stringResource(StringsR.string.bookdownload_search_load_more)) {
                    onLoadMore(source.id)
                  }
                }
              }
          }
      }
    }
  }
}

@Composable
private fun SearchResultRow(
  result: SearchResult,
  sourceName: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      AsyncImage(
        model = result.coverUrl,
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
        error = painterResource(UiR.drawable.album_art),
        contentScale = ContentScale.Crop,
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1F), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          result.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        listOfNotNull(result.author, result.narrator)
          .joinToString(" · ")
          .takeIf { it.isNotEmpty() }
          ?.let {
            Text(
              it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          SourceBadge(sourceName)
          result.torrent?.let { torrent ->
            torrent.seeders?.let { seeders ->
              Text(
                "↑$seeders",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SourceBadge(name: String) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ) {
    Text(
      name,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun TextButtonRow(
  text: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
      Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

private fun sourceName(
  result: SearchResult,
  sources: List<voice.core.booksources.BookSource>,
): String = sources.firstOrNull { it.id == result.sourceId }?.name ?: result.sourceId
