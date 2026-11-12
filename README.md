# BookFlip — PDF/EPUB রিডার

## নতুন/ঠিক করা
- **বাস্তব বইয়ের মতো PDF রিডার**: আঙুলে টেনে পেজ-কার্ল, ভাঁজের ছায়া, পাতার পিছনের দিক, দুই পাশে পাতার স্তূপ, স্পাইন; ল্যান্ডস্কেপে দুই-পাতার খোলা বই; পিঞ্চ-জুম (হাই-রেজ রি-রেন্ডার); নাইট মোড; পেজ-ফ্লিপ সাউন্ড।
- **Intent দিয়ে PDF/EPUB নেওয়া** (`ImportActivity`): ফাইল ম্যানেজার "Open with", Chrome/Telegram/WhatsApp/Gmail থেকে Share — সব কাজ করে। আগের বাগ: ফিল্টার ছিল `exported=false` রিডারে আর Uri পড়াই হতো না।
- স্টোরেজ পারমিশন বাদ (SAF/Uri-ই যথেষ্ট) — আগে পারমিশন denied হলে বই যোগই করা যেত না।
- একই নামের দুই বই আর একটা আরেকটাকে ওভাররাইট করে না (ফাইল SHA-1 নামে সেভ, ডুপ্লিকেট ইমপোর্ট ধরা পড়ে)।
- কভার আগে কখনো সেট হতো না (এক্সট্রাক্টর ডাকাই হতো না) — এখন PDF-এর প্রথম পাতা / EPUB-এর কভার অটো।
- EPUB: ভাঙা Maven repo-র `epublib` বাদ, নিজস্ব পার্সার; WebView-তে অফলাইন সার্ভিং, TOC, সার্চ, নাইট/ফন্ট সাইজ।
- ভাঙা/মৃত ডিপেন্ডেন্সি (JitPack AndroidPdfViewer/PhotoView, Lottie, Compose, kapt) সরানো; Room এখন KSP-তে।
- রিডিং-টাইম স্ট্যাটসে নেগেটিভ সময়ের বাগ, বইমার্ক "Page N" অফ-বাই-ওয়ান, Undo-ডিলিটে ফাইল আগেই মুছে যাওয়া — ঠিক।
- নতুন UI: চামড়া-ও-কাগজ থিম, লাইট/ডার্ক, "Continue reading" হিরো কার্ড, বুক-কভার গ্রিড, ফ্লোটিং রিডার বার।

## GitHub Actions (অটো বিল্ড)

`.github/workflows/android.yml` যা করে:

| কখন | কী হয় |
|---|---|
| `main`/`master`-এ push, Pull Request, বা Actions → *Run workflow* | **Debug APK** + **Signed Release APK** দুটোই বিল্ড হয়, Artifacts-এ পাওয়া যায় |
| `v1.2.0` ধরনের ট্যাগ push | উপরের দুটো APK সহ **GitHub Release** তৈরি হয় |

### নিজের কী দিয়ে সাইন করতে (ঐচ্ছিক, কিন্তু আপডেটের জন্য জরুরি)
```bash
keytool -genkeypair -v -keystore release.jks -alias myalias -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # আউটপুটটা KEYSTORE_BASE64 সিক্রেটে বসান
```
Repo → Settings → Secrets and variables → Actions → এই ৪টা যোগ করুন:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

সিক্রেট না দিলে workflow প্রতি রানে একটা অস্থায়ী কী বানিয়ে সাইন করে (APK ইনস্টল হবে, তবে আলাদা কী-র APK-এর ওপর আপডেট হবে না)।

ভার্সন বদলাতে `gradle.properties`-এর `APP_VERSION_NAME` এডিট করুন; `versionCode` = CI run number।

