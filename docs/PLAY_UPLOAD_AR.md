# رفع كفّ على Google Play، خطوة بخطوة

كل الملفات والنصوص جاهزة في المشروع. هذا الدليل يمشي معك داخل Play Console من أول ضغطة لين الإرسال للمراجعة.
التفاصيل الإنجليزية الكاملة (إجابات «أمان البيانات» وتبرير الأذونات) في `docs/PLAY_RELEASE.md`.

## قبل ما تبدأ: الملفات

| الشي | مكانه |
| --- | --- |
| ملف التطبيق | `app/build/outputs/bundle/release/app-release.aab` |
| الأيقونة 512 | `fastlane/metadata/android/ar/images/icon.png` |
| صورة الواجهة 1024×500 | `fastlane/metadata/android/ar/images/featureGraphic.png` |
| صور الشاشات (عربي) | `fastlane/metadata/android/ar/images/phoneScreenshots/` |
| صور الشاشات (إنجليزي) | `fastlane/metadata/android/en-US/images/phoneScreenshots/` |
| النصوص | `fastlane/metadata/android/ar/` و `en-US/` |
| سياسة الخصوصية | https://kaff.dpdns.org/privacy.html |
| إيميل التواصل | kaff.app.support@gmail.com |

## 1. إنشاء التطبيق
1. ادخل [play.google.com/console](https://play.google.com/console) واضغط **Create app**.
2. اسم التطبيق: `كفّ: نقل الملفات بحركة اليد`
3. اللغة الافتراضية: **العربية (ar)**.
4. App or game: **App**. Free or paid: **Free**.
5. وافق على الإقرارات واضغط **Create app**.

## 2. إعداد التطبيق (Set up your app)
في لوحة التطبيق تطلع قائمة مهام. سوّها كلها:

- **Privacy policy**: الصق رابط سياسة الخصوصية فوق.
- **App access**: اختر «All functionality is available without special access».
- **Ads**: «No, my app does not contain ads».
- **Content rating**: عبّي الاستبيان (Category: Utility). جاوب «لا» على العنف والمحتوى الجنسي والقمار. عن تبادل المحتوى بين المستخدمين: التبادل مباشر بين أجهزة المستخدم نفسه فقط، بدون مشاركة عامة على الإنترنت.
- **Target audience**: اختر 13 وفوق (13-15، 16-17، 18+).
- **News app**: No.
- **Data safety**:
  - Does your app collect or share any of the required user data types? **No**.
  - Is all of the user data collected by your app encrypted in transit? **Yes**.
  - (الملفات تنتقل بين أجهزة المستخدم مباشرة، ما تُرسل لنا ولا لأي طرف ثالث.)
- **Government apps**: No. **Financial features**: None. **Health**: None.

## 3. صفحة المتجر (Main store listing)
- **App name / Short description / Full description**: انسخها من `fastlane/metadata/android/ar/` (title.txt, short_description.txt, full_description.txt).
- ارفع **App icon** و **Feature graphic** وصور **Phone screenshots** من الجدول فوق.
- **Category**: Tools. **Email**: kaff.app.support@gmail.com.
- أضف ترجمة إنجليزية: **Manage translations** ← English (United States) ← انسخ النصوص من `en-US/`، وارفع صور الشاشات الإنجليزية من `en-US/images/phoneScreenshots/`.

## 4. أذونات حساسة (تطلب منك أثناء الرفع)
- **Photo and video permissions** (READ_MEDIA_IMAGES/VIDEO): اختر أن الوظيفة الأساسية تحتاجها، والتبرير:
  «Kaff is a file-transfer app. Its main screen is a picker of the user's photos and videos to send to another device (multi-select, live thumbnails).»
- **Foreground service** (connectedDevice و specialUse): التبريرات جاهزة في `PLAY_RELEASE.md`. لـ specialUse يطلبون فيديو قصير: صوّر شاشة الإعدادات وأنت تشغّل «الفقاعة العائمة» وتظهر الفقاعة.

## 5. الإصدار
1. **Production** ← **Create new release**.
2. وافق على **Play App Signing** (Google تحفظ مفتاح التطبيق، ومفتاحك للرفع بس).
3. ارفع `app-release.aab`.
4. Release notes: انسخ `ar/changelogs/1.txt` و `en-US/changelogs/1.txt`.
5. **Countries/regions**: كل الدول، أو ابدأ بالدول العربية.
6. **Review release** ← **Start rollout to Production**.

المراجعة لتطبيق جديد تاخذ عادة من يومين إلى أسبوع. لما يتنشر، قول لي أفعّل أزرار التحميل في الموقع.

## كل تحديث جاي
1. زِد `versionCode` و `versionName` في `app/build.gradle.kts`.
2. `./gradlew :app:bundleRelease`
3. اكتب `changelogs/<versionCode>.txt`، وارفع الـ AAB في إصدار جديد.

مفتاح التوقيع في `C:\Users\MSI\.kaff-signing\`. لا تحذفه، ولا ترفعه على GitHub.
