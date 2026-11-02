plugins {
  id("voice.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.metro)
}

dependencies {
  api(libs.serialization.json)
  implementation(libs.okhttp)
}
