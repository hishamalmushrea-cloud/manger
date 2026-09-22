# 🌟 manger

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white) ![Repo Size](https://img.shields.io/github/repo-size/hishamalmushrea-cloud/manger?style=for-the-badge) ![Issues](https://img.shields.io/github/issues/hishamalmushrea-cloud/manger?style=for-the-badge) ![Last Commit](https://img.shields.io/github/last-commit/hishamalmushrea-cloud/manger?style=for-the-badge) [![License](https://img.shields.io/github/license/hishamalmushrea-cloud/manger?style=for-the-badge)](https://github.com/hishamalmushrea-cloud/manger/blob/main/LICENSE)

## 📖 About this Project
control phone by voice an writing like agent 

## 🚀 Tech Stack
- **Primary Language:** Kotlin

## 🔗 Connect & Support
[![Trendshift](https://trendshift.io/api/badge/repositories/4119)](https://trendshift.io/)
[![Discord](https://img.shields.io/badge/Discord-Join%20Us-7289DA?style=for-the-badge&logo=discord&logoColor=white)](https://discord.com/)
[![X (formerly Twitter) Follow](https://img.shields.io/twitter/follow/hishamalmushrea-cloud?style=social)](https://x.com/hishamalmushrea-cloud)

---

<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/6e8fb8fb-b76f-44dd-82bb-5b9d200fec2d

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device