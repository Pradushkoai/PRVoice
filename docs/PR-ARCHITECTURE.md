# Архитектура надстройки PRVoice

Документ описывает слой источников, загрузчик и UI поверх базового Voice. Всё, что не описано
здесь, устроено как в апстриме (см. `AGENTS.md`, кодовая база Voice).

## Обзор

```
┌──────────────────────────────────────────────────────────┐
│ :features:bookDownload (Compose UI)                      │
│   поиск → результаты → детали книги → «Скачать» → загрузки│
└──────────────┬─────────────────────────┬─────────────────┘
               │                         │
┌──────────────▼────────────┐  ┌─────────▼─────────────────┐
│ :core:booksources:api     │  │ :core:downloader          │
│ BookSource / SearchRegistry│  │ DownloadEngine (HTTP)     │
│  ├ bazaknig               │  │ TorrentEngine (libtorrent4j)│
│  ├ rutor                  │  │ DownloadQueue (DataStore) │
│  └ rutracker (M4)         │  │ BookImport → core:scanner │
└───────────────────────────┘  └───────────────────────────┘
```

Принципы:

- Источник — подключаемый адаптер за стабильным контрактом `BookSource`. Появление нового сайта
  не трогает UI и загрузчик.
- Загрузчик оперирует `DownloadSpec` (список файлов по HTTP или торрент), не зная деталей сайта.
- Все сетевые вызовы — через общий OkHttp-клиент с настройками (таймауты, UA, прокси — M4).
- Скачивание — в папку, которую Voice уже считает библиотекой (или пользователь выберет),
  после чего стандартный `core:scanner` подхватывает книгу. Без кастомных схем хранения.

## Контракт источника (`:core:booksources:api`)

```kotlin
interface BookSource {
  val id: String                // стабильный идентификатор, напр. "bazaknig"
  val name: String              // человекочитаемое имя для UI

  suspend fun search(query: String, page: Int): SearchPage
  suspend fun details(result: SearchResult): BookInfo
  // BookInfo содержит DownloadSpec:
  //  - HttpFiles(list<Track(url, name)>)         — baza-knig
  //  - Torrent(magnet | .torrent-url, list<TorrentFile>) — rutor, rutracker
}
```

- `SearchPage` — страница результатов + флаг наличия следующей страницы.
- Ошибки — типизированные (`SourceUnavailable`, `SourceChanged`, `CaptchaRequired`), UI показывает
  человекочитаемое сообщение, а не стектрейс.
- Реестр источников — `Set<BookSource>` через Metro (`@IntoSet`), UI агностичен к составу.

## Результаты исследования источников (2026-11-02)

### baza-knig.top (DLE / DataLife Engine)

- Поиск: `GET /index.php?do=search&subaction=search&story=<запрос>&search_start=<N>` —
  55 результатов на страницу, пагинация через `search_start`.
- Карточка результата: `div.short` → `short-title a` (название+URL), `short-img img` (обложка),
  `ul.short-items li` (Автор / Читает / Длительность / Жанр), `short-text` (аннотация).
- Страница книги: обложка `/uploads/posts/…`, метаданные, плеер Playerjs.
- **Файлы книги**: на странице есть `var fileN = strDecode("<encoded>")`; `strDecode` объявлена в
  `/player/decode.js?NN` (обфусцирован, версия меняется). Декодированное значение — JSON:
  `[{"title": "Глава_01", "file": "https://archive.org/download/…/01.mp3"}, …]`.
  Файлы физически лежат на archive.org — прямые ссылки, стабильные и быстрые.
- **Решение:** не портировать обфускатор, а исполнять оригинальный `decode.js` в Rhino
  (чистая JVM JS-машина, `org.mozilla:rhino`). Сайт обновил декодер — мы просто скачиваем новый.
  Скрипт кешируется и обновляется при несовпадении версии.
- Прямая ссылка `index.php?do=download&id=…` требует сессию и бесполезна без браузера —
  используем плейлист.

### rutor.info (публичный торрент-трекер)

- Поиск: `GET /search/0/0/000/0/<запрос>` (зеркала: rutor.is, rutor.lib и др.).
- Строка результата: `tr.gai` / `tr.tum` → дата, `a.downgif` (`d.rutor.info/download/<id>` —
  `.torrent`-файл), magnet-ссылка, ссылка на раздачу `/torrent/<id>/<slug>`, размер, сиды/пиры
  (`span.green` / `span.red`).
- MVP: парсим выдачу, magnet используется TorrentEngine'ом; `.torrent` — резервный путь.

### rutracker.org (M4)

- Требует логин; капча на некоторых входах. Поиск по разделу аудиокниг, раздачи со списком файлов.
- Хранилище cookie + настройки кредов; прокси для обхода блокировок.

## Менеджер загрузок (`:core:downloader`)

- `DownloadEngine` (HTTP): последовательное скачивание треков книги (OkHttp), пауза/резюм
  (Range), прогресс на файл и на книгу; файлы сразу получают осмысленные имена
  (`01 - Глава_01.mp3`).
- `TorrentEngine` (M3): libtorrent4j (`org.libtorrent4j:libtorrent4j:2.1.0-35` + артефакты
  `*-android-arm/arm64/x86/x86_64`), `SessionManager`, magnet → метаданные → выбор файлов →
  sequential download с приоритетом первого/последнего кусков.
- Очередь: одна активная книга, остальные ждут (настройка «параллельность» — потом).
- Состояние — DataStore (`core:datastore` паттерн), метаданные загрузки — JSON-файл в папке книги.
- Уведомления через существующий паттерн foreground-сервиса (образец —
  `AudiobookshelfDownloadService`).
- После завершения — `BookImport`: скан папки через `core:scanner` + `core:data:api`, книга
  появляется в библиотеке.

## UI (`:features:bookDownload`)

- `Destination.BookDownload` в `:navigation` (sealed interface — реализации только в этом модуле).
- Провайдер экрана: `@BindingContainer @ContributesTo(AppScope::class)` + `@Provides @IntoSet`
  `NavEntryProvider<Destination.BookDownload>` (паттерн `AudiobookshelfLoginProvider`).
- ViewModel: `@AssistedInject` + `@AssistedFactory`, `MainScope(dispatcherProvider)`,
  состояние — `mutableStateOf` (паттерн `AudiobookshelfLoginViewModel`).
- Вход: иконка в тулбаре библиотеки (`LibraryTopBar`) + пункт в настройках.
- Строки: `core/strings/src/main/res/values/strings.xml` (+ `values-ru`).

## Юнит-тесты

Парсеры — чистый Kotlin (Jsoup + Rhino + OkHttp), тестируются на фикстурах — сохранённых
HTML-страницах и `decode.js` из исследования (коммитятся в `src/test/resources`). Тесты не ходят
в сеть: воспроизводимость и стабильность CI.

## Лицензионные заметки

- Надстройка — часть производного произведения Voice → GPL-3.0, исходники открыты (PRVoice).
- Rhino: MPL-2.0 (совместим с GPL). libtorrent4j: MIT (совместим). Jsoup: MIT. OkHttp: Apache-2.0.
- Приложение не содержит контента; источники подключаются по желанию пользователя.
