# Psiphon Tunnel-Core — Open-Source Build Patches

هذا المجلد يحتوي تعديلاتنا على سكريبت بناء `ca.psiphon.aar`
من المصدر المفتوح الرسمي:
https://github.com/Psiphon-Labs/psiphon-tunnel-core

## الفرق عن النسخة الرسمية

| التعديل | السبب |
|---|---|
| `-target=android/arm64` فقط (بدل arm+arm64+x86+x86_64) | المستخدم على Android 15 arm64، لا حاجة لباقي المعماريات |
| `GOFLAGS=-trimpath` | يحذف مسارات الملفات من الـ binary (خصوصية + حجم أصغر قليلاً) |
| `-s -w` (موجود في الأصل) | يحذف debug symbols |
| `-checklinkname=0` (موجود في الأصل) | مطلوب لـ inproxy dependency |
| `16KB page size` (موجود في الأصل) | مطلوب لـ Android 15 |

## النتيجة

- حجم `libgojni.so` بعد التعديل: ~8MB (بدل ~26MB × 4 معماريات = 104MB)
- حجم `ca.psiphon.aar` بعد التعديل: ~10MB (بدل ~37MB)

## كيف يُستخدم في CI

الـ workflow (`.github/workflows/build.yml`) يقوم بـ:
1. جلب آخر commit من tunnel-core الرسمي
2. فحص cache (مفتاح = SHA للـ commit)
3. إذا لم يكن مكيّشاً: تثبيت Go 1.24 + gomobile + NDK r25c، البناء من المصدر
4. نسخ الـ AAR المبني إلى `app/libs/ca.psiphon.aar` (يستبدل نسخة LFS)
5. بناء APK بشكل طبيعي

## المصدر

```
github.com/Psiphon-Labs/psiphon-tunnel-core @ master
```
مثبّت بـ SHA في كل build لضمان reproducibility.
