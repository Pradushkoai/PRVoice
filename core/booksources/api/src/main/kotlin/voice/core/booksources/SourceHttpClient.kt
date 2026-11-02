package voice.core.booksources

import dev.zacsweers.metro.Qualifier

/**
 * Marks the shared [okhttp3.OkHttpClient] that talks to the book sites. Sites expect browser
 * headers, so everything that requests pages or files from a source uses this client.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SourceHttpClient
