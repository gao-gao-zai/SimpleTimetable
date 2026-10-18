# 简单课表 / SimpleTimetable

离线、免账号的原生 Android 课表应用。Kotlin + Jetpack Compose，面向普通手机竖屏与非 Wear OS Android 手表，最低 Android 8.0（API 26）。当前版本为 0.1.0，设计以已确认的 v18 原型为准。

- 科目、共享时间表、课表群、按星期与多周轮换的课表管理。
- 实际启用和浏览编辑分离；单节替换、跨日交换、临时整张课表与临时层，保存后生效并按有效期失效。
- 秒级倒计时、已过去比例、按日期左右翻页的时间轴、手机与小屏独立布局。
- 自选种子色与七组预设，完整语义配色、深浅模式、大字号、高对比度、减少动画。
- 可关闭的静默通知，以及课程卡片、可滚动今日课表两种原生桌面小组件。
- ClassIsland JSON、Excel XLSX 与 CSV 导入；可选 NTP 软件校时，设备校准与学校偏移分离。

不含作业、响铃、振动、提醒、自动化、账号或云同步。vivo WATCH / BlueOS 及其 SDK、传输和快捷卡片已延期，本项目当前没有这些平台的实现。

## 构建与测试

安装 JDK 17 或兼容的 JDK、Android SDK 35 与 Build Tools 35.0.0，在 `local.properties` 设置 `sdk.dir`，或者设置 `ANDROID_SDK_ROOT`，运行：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

第二条命令需要已启动的 Android 设备或模拟器。APK 在 `app/build/outputs/apk/debug/app-debug.apk`，这是开发签名包。首次进入创建空白档案，可以新建数据、导入文件，或在“新建档案”时主动勾选示例数据。核心功能不要求联网权限授权、通知授权或账号。

GitHub Actions 包含 debug/release 单元测试、Lint，以及 R8 优化后 release 在 Android 11 的 410×502、410×410、411×891 测试矩阵。CI 设备测试用临时测试签名，分发密钥不会上传至 GitHub。

### 发布构建

发布版关闭调试，启用 R8 代码与资源压缩；Gson 持久化模型有保留规则，确保已有课表 JSON 的字段与枚举不随混淆改变。

将签名配置放在仓库外，通过 `SIMPLETIMETABLE_SIGNING_PROPERTIES` 指向它，或使用已被 Git 忽略的根目录 `signing.properties`。配置包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；`storeFile` 可用相对于配置文件的路径。签名文件与密码不得提交到仓库。

```sh
./gradlew testReleaseUnitTest lintRelease assembleRelease bundleRelease
```

APK 位于 `app/build/outputs/apk/release/app-release.apk`，AAB 位于 `app/build/outputs/bundle/release/app-release.aab`。没有提供签名配置时仅生成 unsigned APK，不能直接安装，也不会自动回退到开发签名。保留同一发布密钥用于后续更新；开发版和发布版签名不同，切换前应先在应用中导出档案备份。

可在已连接的测试设备上运行 `./gradlew -PtestBuildType=release connectedReleaseAndroidTest`，需要提供发布签名；仅 CI 显式指定 `-PciReleaseSigning=true` 时使用临时测试证书。

## 文档

- [当前实现与验证边界](docs/implementation.md)：已实现功能、平台刷新策略、导入差异、实机待验证项。
- [需求基线](docs/requirements.md)、[验收基线](docs/acceptance.md)、[历史决策](docs/decisions-and-open-questions.md)：保留 2026-10-11 的原始记录，后续 v18 设计与延期决定见当前实现文档。
- [第三方依赖](THIRD_PARTY_NOTICES.md)。

## 许可

[GNU GPL v3](LICENSE)。用于自用及开源发布安装包，不上架应用商店。发布密钥由项目维护者私下保管，安装包及公开源码均不包含密钥或密码。
