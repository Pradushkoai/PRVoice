package voice.core.booksources

/** The source could not be reached or answered with an unexpected status. Retry may help. */
class SourceUnavailable(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The source responded, but the page didn't have the structure the parser expects. The site
 * probably changed and the source needs an update.
 */
class SourceChanged(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)
