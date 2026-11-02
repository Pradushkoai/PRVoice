plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.strings)
  implementation(projects.core.ui)
  implementation(projects.core.booksources.api)
  implementation(projects.core.downloader)
  implementation(projects.navigation)
}
