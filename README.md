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

التطبيق لا يحتوي أي رابط GitHub/jsDelivr. العنوان الثابت الوحيد هو ملف التوجيه على GitLab:
`https://gitlab.com/msms22315/media-download-apk/-/raw/main/source_url.txt`

سطره الأول يحمل رابط المشروع الحالي، مثلاً `https://github.com/<owner>/<repo>` (يُحوَّل وقت التشغيل إلى
`https://cdn.jsdelivr.net/gh/<owner>/<repo>@main/downapp`)، أو رابط `https://` كامل لمجلد `downapp`.
إذا توقف حساب GitHub: غيّر هذا السطر فقط وسيتبعه كل التطبيقات المثبتة.

سلوك الفشل صامت دائماً: الوسيط غير متاح ← آخر رابط صالح محفوظ؛ لا رابط/404/انقطاع ← تُستخدم محركات
بايثون المحلية دون أي رسالة. أخطاء `HASH_MISMATCH` و`5xx` تُعالج بصمت مع إعادة محاولة في الخلفية.

بعد أي تعديل داخل `downapp/`:

1. ارفع الرقم في `downapp/version.txt` (رقم صحيح أكبر من السابق).
2. شغّل `python3 tools/gen_manifest.py` (يولّد `manifest.json` ويتحقق من `app_version.json`).
3. ادفع إلى `main` — workflow `purge-cdn.yml` يمسح كاش jsDelivr تلقائياً.

إصدار APK جديد: ارفع الـ APK في GitHub Releases ثم عدّل `downapp/app_version.json`
(`versionCode`, `versionName`, `apkUrl`) وادفعه، فقط بعد أن يصبح رابط الـ APK متاحاً.

الجدولة الخلفية كل 3 أيام + فحص صامت عند كل فتح للتطبيق. الإقلاع النظيف يُنزّل المحركات من الشبكة أولاً، ولا تُستخدم نسخة `assets` إلا دون اتصال.
