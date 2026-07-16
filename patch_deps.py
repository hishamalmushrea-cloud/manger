import sys

with open("app/build.gradle.kts", "r") as f:
    content = f.read()

deps_to_add = """
  implementation(libs.hilt.android)
  "ksp"(libs.hilt.compiler)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.hilt.work)
  "ksp"(libs.androidx.hilt.compiler)
  implementation(libs.timber)
  implementation(libs.porcupine)
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.datastore.preferences)
"""

content = content.replace("  // implementation(libs.accompanist.permissions)", "")
content = content.replace("  // implementation(libs.androidx.navigation.compose)", "")
content = content.replace("  // implementation(libs.androidx.datastore.preferences)", "")
content = content.replace("  implementation(libs.androidx.room.ktx)", "  implementation(libs.androidx.room.ktx)" + deps_to_add)

with open("app/build.gradle.kts", "w") as f:
    f.write(content)
