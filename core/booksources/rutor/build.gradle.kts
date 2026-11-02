plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.booksources.api)

  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.serialization.json)
  implementation(libs.coroutines.core)

  testImplementation(libs.okhttp.mockwebserver)
}
