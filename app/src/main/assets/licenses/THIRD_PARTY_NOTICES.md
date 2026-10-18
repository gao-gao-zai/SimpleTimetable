# Third-party software

SimpleTimetable uses the following libraries; upstream notices and license terms apply.

| Dependency | Version | License / source |
| --- | --- | --- |
| Kotlin / Compose compiler plugin | 2.1.20 | Apache-2.0; https://github.com/JetBrains/kotlin |
| Android Gradle Plugin | 8.9.1 | Apache-2.0; https://android.googlesource.com/platform/tools/base/ |
| Gradle wrapper | 8.11.1 | Apache-2.0; https://github.com/gradle/gradle |
| AndroidX Compose / Material 3 | BOM 2025.04.01 | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Activity | 1.10.1 | Apache-2.0; same AndroidX source |
| AndroidX Lifecycle | 2.8.7 | Apache-2.0; same AndroidX source |
| Material Components for Android / Material Color Utilities Java | 1.12.0 | Apache-2.0; https://github.com/material-components/material-components-android |
| Gson | 2.11.0 | Apache-2.0; https://github.com/google/gson |
| JUnit (tests) | 4.13.2 | EPL-1.0; https://github.com/junit-team/junit4 |
| Robolectric (tests) | 4.14.1 | MIT; https://github.com/robolectric/robolectric |
| AndroidX Test (tests) | core 1.6.1 / runner 1.6.2 / ext JUnit 1.2.1 | Apache-2.0; https://github.com/android/android-test |

Theme generation uses the official Material Color Utilities HCT, SchemeTonalSpot and semantic color roles bundled in Material Components. Neutral palette chroma is adjusted for the accepted prototype's clearly visible seed tint. The Java API is marked library-internal by Material Components; the dependency is pinned and theme regression tests must run before upgrades. It is not a hand-written RGB interpolation algorithm and does not require a network theme service.

ClassIsland's model and selection behavior were studied at commit 235914a2fac1733cc2a169457c7bad4edb2ca2f6 (https://github.com/ClassIsland/ClassIsland). This project implements its own Android engine and explicitly documents import differences; no ClassIsland automation code is executed.
