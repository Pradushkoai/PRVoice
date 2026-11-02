plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.booksources.api)
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.datastore)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)
  implementation(projects.core.scanner)
  implementation(projects.core.strings)

  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.datastore)
  implementation(libs.androidxCore)

  testImplementation(libs.okhttp.mockwebserver)
}
