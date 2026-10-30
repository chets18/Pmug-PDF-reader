plugins {
  id("com.android.dynamic-feature")
}

android {
  namespace = "com.example.piper"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 24 }
}

dependencies {
  implementation(project(":app"))
  implementation(libs.okhttp)
  implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
  implementation("org.apache.commons:commons-compress:1.27.1")
}
