import sys

with open("app/build.gradle.kts", "r") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if line.strip() == "alias(libs.plugins.google.services)":
        new_lines.append(line)
        new_lines.append("  alias(libs.plugins.hilt)\n")
    elif line.strip() == "applicationId = \"com.example\"":
        new_lines.append("    applicationId = \"com.aistudio.heymanager.xyzk\"\n")
    elif line.strip() == "android {":
        new_lines.append(line)
    elif line.strip() == "testOptions { unitTests { isIncludeAndroidResources = true } }":
        new_lines.append(line)
    else:
        new_lines.append(line)

# Add dependencies to the bottom of the dependencies block.
# Actually we can just find dependencies { ... } and add them inside.
# We will use simple replace for dependencies block.
with open("app/build.gradle.kts", "w") as f:
    f.writelines(new_lines)
