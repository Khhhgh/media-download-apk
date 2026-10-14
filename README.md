<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/62f354d0-b375-4c02-8ec5-0191ce1fdfb0

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## نشر تحديث للمحركات (OTA) — بدون إصدار APK جديد

التطبيق يقرأ `downapp/version.txt` عبر jsDelivr فقط (لا `api.github.com` ولا `raw.githubusercontent.com`):
`https://cdn.jsdelivr.net/gh/Khhhgh/media-download-apk@main/downapp/version.txt?t=<timestamp>`

بعد أي تعديل داخل `downapp/`:

1. ارفع الرقم في `downapp/version.txt` (رقم صحيح أكبر من السابق).
2. شغّل `python3 tools/gen_manifest.py` (يولّد `manifest.json` بنفس رقم الإصدار وبصمات SHA-256 صحيحة).
3. ادفع إلى `main`.
4. (اختياري، لتسريع الوصول) امسح كاش jsDelivr للملفات المتغيرة عبر واجهة الـ purge الخاصة بهم (`purge.jsdelivr.net`)؛ كاش فرع `@main` قد يصل لساعات.

الجدولة الخلفية كل 3 أيام + فحص صامت عند كل فتح للتطبيق. الإقلاع النظيف يُنزّل المحركات من الشبكة أولاً، ولا تُستخدم نسخة `assets` إلا دون اتصال.
