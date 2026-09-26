# دليل البدء السريع — DroidPilot AI

## ما هو؟
وكيل ذكاء اصطناعي على هاتفك الأندرويد: تتكلم معه، يفهم طلبك عبر نموذج AI
(GPT-OSS 20B على Cloudflare)، ثم ينفّذ الأوامر فعلياً على الهاتف (فتح تطبيقات،
ضربات شاشة، تمرير، كتابة…) عبر خدمة Accessibility — **بدون Root وبدون Shizuku**.

## الروابط
- **واجهة الويب**: https://droidpilot-ai.turkjgastroenterol-org.workers.dev
- **الكود**: مستودع GitHub الخاص بك (راجع رسالة النشر)

## خطوات التشغيل على الهاتف (OPPO Reno5)

1. حمّل ملف APK من تبويب **Actions** في مستودع GitHub (الأرتيفاكت
   `DroidPilot-debug-apk`) أو ابنِه محلياً، ثم ثبّته.
2. افتح التطبيق → الصق رابط الـWorker → **Save URL**.
   *(ملاحظة: مفاتيح AI لا تُدخل في التطبيق أبداً — تبقى في Cloudflare Secrets.)*
3. اضغط **Connect** → سيظهر `DROID-XXXX` ورمز PIN من 6 أرقام.
4. اضغط **Accessibility** → فعّل خدمة DroidPilot AI من إعدادات النظام.
5. اضغط **Start Connection** → سيظهر إشعار دائم "DroidPilot AI is connected".
6. **مهم جداً على ColorOS**: الإعدادات → البطارية → إدارة التطبيقات →
   DroidPilot AI → اسمح بـ *النشاط في الخلفية* + *التشغيل التلقائي*، ثم ثبّت
   التطبيق من شريط المهام الأخيرة — وإلا سيقتل النظام الخدمة.
7. افتح واجهة الويب → Device Pairing → اختر جهازك → أدخل الـPIN → **Pair**.

## جرّبها
اكتب في الدردشة:
> افتح TikTok وانتقل للفيديو التالي

سترى في المباشر: `open_app ✓` ثم `get_current_package ✓` ثم `swipe_up ✓` ثم
الرد النهائي — بدون أي Screenshot (السياسة: Accessibility أولاً).

## اختبار بدون هاتف
```bash
python3 scripts/fake_device.py https://<worker-url> "افتح TikTok"
```

## أمان المفاتيح — احذفها الآن
مفاتيح GitHub وCloudflare التي استُخدمت في النشر الأولي **مشاركة في محادثة
نصية = تعتبر مكشوفة**. بعد التأكد من عمل كل شيء:
1. احذف توكن GitHub من: Settings → Developer settings → Personal access tokens.
2. احذف توكن `droidpilot-deploy-temp` من Cloudflare و**بدّل Global API Key**.
3. لاحقاً أنشئ توكنات جديدة محددة الصلاحيات عند الحاجة (الأوامر في
   `docs/DEPLOYMENT.md`).

## الأوامر الشائعة
```bash
cd worker
npm run deploy        # نشر تحديثات الـWorker
npm run tail          # متابعة سجلات مباشرة
python3 ../scripts/fake_device.py https://<url> "..."   # اختبار E2E
```
