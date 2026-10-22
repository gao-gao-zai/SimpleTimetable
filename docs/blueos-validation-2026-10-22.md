# BlueOS 恢复验证报告 — 2026-10-22

本次按需求重建，原 BlueOS 工作区与源码未取得。旧会话 22/56 项测试声明没有被沿用为证据。

实现提交：[e70cfbc8e41b4fe28ad7b6700b52dc77cd4af031](https://github.com/gao-gao-zai/SimpleTimetable/commit/e70cfbc8e41b4fe28ad7b6700b52dc77cd4af031)。独立分支：[codex/recover-blueos-v0.1.0](https://github.com/gao-gao-zai/SimpleTimetable/tree/codex/recover-blueos-v0.1.0)。安卓基线 `874e49f702ed4925fd78e35b8a30f2dfbf0ed3a6` 是祖先，保留原 0.1.2 实现。主分支、安卓分支与 work 分支未改写，没有强推或合并主分支。

## 本次实测

| 检查 | 实际结果 | 范围 |
| --- | --- | --- |
| `node --test blueos/tests/*.test.js` | 35/35 通过 | 课表规则、优先级/临时层/到期、交换、UTF-8/CRC、重传/断连恢复模拟、故障存储、预览绑定、原生编辑控制器 |
| `node blueos/scripts/verify-source.mjs` | 19 个脚本及路由/相对依赖/共享源码副本通过 | 只做源码静态检查，不能代替 Toolkit 编译 |
| `:app:testDebugUnitTest` | 66/66 通过 | 52 项原有测试与 14 项新增测试 |
| `:app:testReleaseUnitTest` | 66/66 通过 | Release 源集逻辑测试；不是优化 APK 真机测试 |
| `:app:lintDebug` / `:app:lintRelease` | 成功，0 错误 | Debug 34 / Release 33 个警告；未声称零警告 |
| `:app:assembleDebug` | 成功 | Android API 26+，target/compile 35，版本 0.1.2-blueos.1，versionCode 4 |
| `apksigner verify --verbose app-debug.apk` | 成功，APK v2 签名 | 临时开发签名包，可用于开发安装，不是原发布签名 |
| `:app:assembleRelease` | 成功，R8/资源压缩完成 | 生成 unsigned APK，无维护者发布密钥，不能直接安装 |
| [GitHub core CI](https://github.com/gao-gao-zai/SimpleTimetable/actions/runs/39831495419) | success | 实现提交 e70cfbc |
| [GitHub Android CI](https://github.com/gao-gao-zai/SimpleTimetable/actions/runs/39831495294) | 打包时仍在运行 | 三尺寸 Android 11 模拟器及 release smoke 结果须以该 run 最终状态为准；不预先记为通过 |
| BlueOS Toolkit 编译 | 未运行，受阻 | 工具包未成功下载到消费工作区 |
| GT2 安装 / 无线双向收发 / 标准卡 / 表盘共享 | 未实测 | 没有 GT2 硬件，不以逻辑模拟冒充端到端实测 |

JDK 为官方 Temurin 17.0.21+9，Gradle 8.11.1，Android SDK 35 / Build Tools 35.0.0。下载工具和 JDK 按官方发布校验值校验后使用。环境缓存与系统受信任证书配置仅用于本次构建，未修改仓库依赖来源、未关闭 TLS 校验。

## 恢复差异矩阵

| 功能 | 源码恢复情况 | 验证/待办 |
| --- | --- | --- |
| 安卓 0.1.2 原生管理、导入、主题、时钟、通知、小组件 | 保留；新增 BLE 同步入口 | 原有 52 项测试继续通过，物理设备回归未做 |
| 手表离线档案/科目/教师/收藏/户外、作息、课表群、多周规则 | 原生 UX + JS 逻辑已实现 | 编辑控制器/规则测试通过，原生渲染和交互待 Toolkit/真机 |
| 创建/复制/删除、预设改名、浏览与显式启用分离、引用保护 | 已实现整页流程与确认/放弃 | 控制器及数据验证通过，GT2 长列表/大字/圆屏待测 |
| 临时换课、跨日/永久交换、指定日期停课/撤销、覆盖到期 | 已实现，采用安卓模型 | 规则测试通过；默认日期与编辑交互待设备验证 |
| 作息时段增删复制、整体偏移、时分秒三列 | 已实现无输入法流程 | 控制器与数据边界测试通过；设备交互待测 |
| 手表首页当前课/倒计时/进度/下一节、单日浏览 | 已实现；亮/深两套、固定绿色、系统时间 | 未经原生布局/性能验证；Android 颜色与未知扩展字段在传输中保留 |
| 独立 BLE、六位码、按需广播 | 双端原生 API 已接入；不依赖运动健康/BlueXlink | 未做 GT2 无线测试；连接码只防误连，不宣称加密/强认证 |
| 完整档案分包、校验、ACK、重试、断连恢复 | 新 v1 协议已实现并记录 | 跨语言帧/版本向量与模拟故障测试通过；无线栈行为待真机 |
| 预览确认、落盘后回执、单端方向提示/双端选择/副本 | 已实现，不逐节自动合并 | 原子存储、过期预览和预览替换保护测试通过；各端显示/校准不随档案传输 |
| 新标准卡 | `router.widgets.type:js` 原生标准卡源码已实现 | minCardPlatformVersion 2000；GT2 支持范围未确认，不冒充旧快捷卡或轻卡 provider |
| 同签名参考表盘 / App 只读数据接口 | exchange + event 提示 + 本地缓存/过期提示源码已实现 | App 解析规则后发布时间轴/版本/更新时间/有效期；只适用于同签名参考消费者，后台刷新不保证；watch3000 是本地示例 ID，正式 ID 未分配 |
| 可用 BlueOS RPK/RPKS | 缺失 | Toolkit 下载受阻，未编译，不能宣称可安装 |

## 当前精确阻塞

动作：将父会话已保存的 89,167,767 字节 BlueOS Linux Toolkit 1.0.12-beta.13 从 Library 物化到消费环境。Library 引用成功解析，但 `workspace_path` 为空；使用返回地址下载时网络代理 CONNECT 返回 **403 Forbidden**。通过受支持的权限申请重试同一下载后仍是 403。结果：下载被拒绝；工作区没有该文件，未能核实本地 SHA-256、读取包内 README 或执行工具。没有绕过网络访问控制，也没有将父环境校验冒充本环境验证。

预期工具包 SHA-256：`ae3f3cdd328e781fec1b642ef356b0ad15993914a578eef5fc80e64c92847b1c`。继续原生编译需要受支持的可访问下载/直接物化途径；不是需要新发布密钥。取得工具后仍须读 README、校验和检查来源，再运行原生构建；随后按设备核对卡片版本、GATT、exchange、表盘签名和安装包。

## 交付与校验

交付包只包含 Git 跟踪源码、可安装的开发版 Android APK、测试摘要/构建日志/本报告。源码包不含 Git 凭据/历史、工具包、SDK/JDK、缓存、debug keystore、发布私钥、私人课表或手机号。测试使用合成样例。

- Debug APK：21,079,179 字节；SHA-256 `58d7024e317fb1ce8bc3a4fa0b768aeb2b046b1041db81903e138e0c352af7ac`。
- Release unsigned（仅构建结果，不作为可安装产物交付）：2,536,262 字节；SHA-256 `ea7fd9eadb89c68898a939d5dc23a98c70824a4f483702bec404292a1eb58d1c`。

开发 APK 与现有发布 APK 可能签名不同，不能据此承诺覆盖升级；不要为测试直接卸载有私人课表的现有安装。未新建维护者持久发布凭据。源代码许可及第三方说明随包附上。
