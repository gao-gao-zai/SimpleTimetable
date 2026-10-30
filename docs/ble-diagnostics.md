# 独立 BLE 诊断工具重建

Android `ble-diagnostics` 为 1.0.3，BlueOS `blueos/diagnostics` 为 1.0.1。这是行为重建，原工具源代码、包身份、UUID 字节未找回；本次使用独立 `d731....` UUID，和课表的 `d730....` 完全分离。不读写课表，不使用验证码，Android manifest 无联网权限，报告只在本地显示和复制，MAC 仅尾部。

手表可切换“先监听后注册／先注册后监听”，并提供 60 秒短 UUID 广播测试。普通诊断提供静态读、动态读回调计数、写后读、无响应写、通知回显和 64 B 分块整段校验。初始读值刻意无效，动态值只有真实回调才生成。Android 对读取失败/回调超时继续后续写和通知测试；缺少独立服务记录未测试，不能误报通过。

诊断固定数据为 `byte[i] = i XOR 0xa5`（0..63）。64 B 分四个 20 B ATT 包：`d7, sequence(0..3), count(4), length(16), body(16)`，接收方逐块重组、比较固定内容及 CRC32。动态读取计数包含 read/write/notify 调用数，通知调用成功不等同于对端收到。实际布局是本次重建选择，不宣称兼容丢失的诊断安装包。

Android 会记录发现的服务 UUID 清单，可显式触发第二次发现。缓存刷新仅针对当前选定设备、每连接轮最多一次、不解绑；接受结果只表示请求被接受，等待后重新发现并以新清单为证据。没有把 `refresh=true` 说成已完成清除。

构建：`./gradlew :ble-diagnostics:testDebugUnitTest :ble-diagnostics:lintDebug :ble-diagnostics:assembleRelease`。无原签名时产物为 unsigned APK。BlueOS 工程需官方完整 Toolkit，本次尚未取得工具包、未原生编译或真机测试。手机端旧版成功记录不能替代本次互通结果。
