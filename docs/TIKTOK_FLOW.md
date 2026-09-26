# DroidPilot AI — TikTok Automation CUJ

## Overview

The primary Critical User Journey (CUJ) demonstrates autonomous execution without screenshot reliance:

**User Prompt:**
> "افتح TikTok وانتقل للفيديو التالي" (Open TikTok and swipe to the next video)

---

## Autonomous Step-by-Step Sequence

```
1. User submits prompt: "افتح TikTok وانتقل للفيديو التالي"
2. AI selects tool: open_app({"package_name": "TikTok"})
   └── Android PackageManager launches `com.zhiliaoapp.musically` (or `com.ss.android.ugc.trill`)
3. AI verifies foreground: get_current_package()
   └── Android returns `{"package": "com.zhiliaoapp.musically"}`
4. AI inspects active nodes: get_screen_nodes()
   └── Android AccessibilityService parses interactive window elements
5. AI triggers smooth scroll: swipe_up()
   └── AccessibilityService executes GestureDescription Path (from 78% height to 22% height)
6. AI verifies new video load: get_screen_nodes()
   └── Android returns updated tree with new element hash
7. AI responds to User: "تم فتح TikTok بنجاح والانتقال إلى الفيديو التالي!"
```

---

## Screenshot Policy Enforcement

1. Accessibility Nodes are the primary source of truth.
2. Screenshots are **never** taken automatically for standard feed interactions.
3. Screenshots are reserved strictly for:
   - Explicit user request (`take_screenshot`)
   - Complex visual CAPTCHAs where Accessibility tree contains no text or click targets
   - Error recovery after repeated node lookups fail
