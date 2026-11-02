package voice.core.booksources.bazaknig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import voice.core.booksources.SourceChanged
import voice.core.booksources.SourceUnavailable

/**
 * baza-knig hides the urls of its audio files in an encoded string that is decoded by the site's
 * own `/player/decode.js`. That script is obfuscated and its version changes, so instead of porting
 * it we run the original script on the JVM through Rhino. The script only needs plain ECMAScript
 * builtins.
 */
internal class PlaylistDecoder(
  /** Provides the current decode.js, e.g. downloaded from the site. */
  private val decodeJsProvider: suspend () -> String,
) {

  @Volatile private var decodeJs: String? = null

  /**
   * Runs the site's decode.js over the encoded playlist. When the script fails it is probably
   * outdated, so it is dropped and fetched again once; a second failure is reported.
   */
  suspend fun decode(encoded: String): String {
    var attempts = 0
    while (true) {
      val js =
        decodeJs
          ?: decodeJsProvider().also { fetched ->
            decodeJs = fetched
          }
      try {
        return withContext(Dispatchers.Default) { run(js, encoded) }
      } catch (exception: SourceChanged) {
        decodeJs = null
        attempts++
        if (attempts >= 2) throw exception
      }
    }
  }

  /** Drops the cached script so the next decode picks up a new version from the site. */
  fun invalidate() {
    decodeJs = null
  }

  private fun run(
    decodeJs: String,
    encoded: String,
  ): String {
    val context = Context.enter()
    try {
      // Rhino cannot generate dalvik bytecode, so it must interpret the script
      context.setInterpretedMode(true)
      val scope = context.initStandardObjects()
      context.evaluateString(scope, decodeJs, "decode.js", 1, null)
      val function = scope.get("strDecode", scope)
      if (function !is Function) throw SourceChanged("decode.js does not define strDecode")
      val result = function.call(context, scope, scope, arrayOf(encoded))
      return result?.toString() ?: throw SourceChanged("strDecode returned nothing")
    } catch (exception: RhinoException) {
      throw SourceChanged(cause = exception)
    } catch (exception: RuntimeException) {
      throw SourceUnavailable(cause = exception)
    } finally {
      Context.exit()
    }
  }
}
