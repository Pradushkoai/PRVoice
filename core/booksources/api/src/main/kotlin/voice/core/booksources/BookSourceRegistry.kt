package voice.core.booksources

/** Looks up sources by their id, e.g. to load the details of a search result again. */
class BookSourceRegistry(private val sources: Set<BookSource>) {

  val all: Collection<BookSource> = sources.sortedBy { it.name }

  fun byId(id: String): BookSource? = sources.firstOrNull { it.id == id }

  fun byIdOrThrow(id: String): BookSource = requireNotNull(byId(id)) { "No source with id $id" }
}
