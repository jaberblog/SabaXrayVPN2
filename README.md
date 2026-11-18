# Saba Xray VPN (Kotlin)

یک پروژه‌ی آزمایشی اندروید با Kotlin و `libv2ray.aar` برای اتصال به سرور VLESS + REALITY.

## امکانات

- دکمه اتصال / قطع اتصال
- ساخت تونل از طریق Android `VpnService`
- اجرای Xray با `CoreController.startLoop(config, tunFd)`
- نمایش سرعت لحظه‌ای دانلود و آپلود با شمارنده‌های Xray
- اجرای سرویس در Foreground و اعلان وضعیت
- ساخت APK آزمایشی با GitHub Actions

## ساخت APK در GitHub

1. فایل ZIP را Extract کن.
2. کل پوشه‌ی `SabaXrayVPN` را در یک Repository جدید GitHub آپلود کن.
3. به تب **Actions** برو.
4. Workflow با نام **Build Android APK** را انتخاب کن و روی **Run workflow** بزن. با هر Push به شاخه‌ی `main` یا `master` هم اجرا می‌شود.
5. پس از سبز شدن Workflow، وارد اجرای آن شو و از بخش **Artifacts** فایل `SabaXrayVPN-debug-apk` را دانلود کن.
6. ZIP آرتیفکت را باز کن؛ فایل `app-debug.apk` داخل آن است.

اگر Workflow قدیمی را قبلاً اجرا کرده‌ای و خطای `Failed to find package 'tools'` دیده‌ای، نسخه‌ی اصلاح‌شده‌ی پروژه را دریافت کن یا مطمئن شو Workflow از `android-actions/setup-android@v4.0.2` استفاده می‌کند؛ پکیج منسوخ‌شده‌ی `tools` نباید جداگانه نصب شود.

GitHub Actions با `android-actions/setup-android@v4.0.2` ابزارهای Android SDK را آماده می‌کند و فایل `libv2ray.aar` نسخه‌ی `v26.11.9` را از Release رسمی دانلود می‌کند. فایل حجیم AAR داخل ZIP سورس قرار داده نشده است.

## ساخت محلی (اختیاری)

برای Build محلی باید Gradle 8.13 و JDK 17 نصب باشد؛ این پروژه عمداً Gradle Wrapper ندارد و روش پیشنهادی، GitHub Actions است.

1. فایل رسمی `libv2ray.aar` را از Release زیر بگیر:
   `https://github.com/2dust/AndroidLibXrayLite/releases/tag/v26.11.9`
2. آن را در مسیر `app/libs/libv2ray.aar` قرار بده.
3. از ریشه‌ی پروژه دستور `gradle assembleDebug` را اجرا کن.
4. APK در مسیر `app/build/outputs/apk/debug/app-debug.apk` ساخته می‌شود.

## ساختار پوشه‌ها

```text
SabaXrayVPN/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
│   ├── libs/
│   │   └── README.md
│   ├── src/main/
│   │   ├── java/com/example/xrayvpn/
│   │   │   ├── MainActivity.kt
│   │   │   ├── core/XrayConfigBuilder.kt
│   │   │   └── vpn/XrayVpnService.kt
│   │   ├── res/
│   │   │   ├── drawable/
│   │   │   ├── layout/activity_main.xml
│   │   │   └── values/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

## تغییر کانفیگ

کانفیگ نمونه‌ی ارسالی در فایل زیر قرار دارد:

`app/src/main/java/com/example/xrayvpn/core/XrayConfigBuilder.kt`

برای تغییر سرور، مقدارهای `address`, `port`, `id`, `flow`, `serverName`, `password` و `shortId` را مطابق لینک VLESS خودت عوض کن. مقدار `password` همان کلید عمومی `pbk` در لینک REALITY است.

## نکات مهم فنی

- این یک نسخه‌ی اولیه است؛ قبل از استفاده‌ی روزمره باید روی دستگاه واقعی و با شبکه‌های Wi-Fi و سیم‌کارت آزمایش شود.
- برنامه‌ی خودش با `addDisallowedApplication(packageName)` از تونل مستثنا شده است؛ چون Xray داخل همان UID اجرا می‌شود، سوکت‌های خروجی هسته دوباره وارد VPN نمی‌شوند. هنگام جابه‌جایی بین Wi-Fi و دیتای موبایل، VPN را قطع و دوباره وصل کن.
- فعلاً فقط IPv4 در مسیر VPN تعریف شده است تا در شبکه‌هایی که IPv6 ناقص دارند، مسیر سیاه ایجاد نشود. DNS سیستم نیز از همان تونل و از مسیر outbound پروکسی عبور می‌کند.
- آمار سرعت از شمارنده‌های outbound هسته Xray خوانده می‌شود؛ این سرعت لحظه‌ای است و با فاصله‌ی تقریبی یک ثانیه به‌روزرسانی می‌شود.
- لینک کانفیگ نمونه به‌صورت متن داخل سورس است. برای انتشار عمومی، بهتر است UUID و کلیدهای سرور را از سورس حذف کنی و یک صفحه‌ی وارد کردن کانفیگ بسازی.
- AAR رسمی شامل کد native است؛ نسخه‌ی استفاده‌شده را در workflow به‌صورت ثابت pin کرده‌ایم تا build قابل تکرارتر باشد.

## وابستگی‌ها

- Android Gradle Plugin 8.13.2
- Kotlin 2.2.20
- Gradle 8.13
- JDK 17
- Android SDK 36
- AndroidLibXrayLite `v26.11.9`
