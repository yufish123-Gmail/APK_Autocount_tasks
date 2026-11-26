# Mgame 自动加次数工具 · Android APK 移植分析

分析日期为 2026-11-26，源码基线为 `d352788`。目标是把任务流程移到已授权的 Android 手机，在用户主动启动后独立运行，安装完成后日常使用无需电脑、USB 调试或 Root。

## 1. 技术结论

建议首版采用 **Kotlin + Android 无障碍服务 + OpenCV Android + 内置中文 OCR**，最低支持 Android 11（API 30），先适配当前使用的 1080×2400 竖屏设备。由无障碍服务观察前台窗口、执行点击和返回、按需截图。识别及任务状态机全部在手机内运行。Android 14 及以上可按需使用指定窗口截图。Android 11 是这条截图方案的最低版本，手势注入本身从 API 24 起可用。[Android AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

现有脚本适合作为规则基线。可沿用任务顺序、PNG 模板、奖励核对、失败分级和日志字段。平台层有三处需要替换。设备操作由 `Phone` 发出 ADB 命令，识字通过 `read_ocr` 调用 Windows PowerShell 和 `read-progress.ps1`，会话互斥依赖 Windows 的 `msvcrt`。其中 `am force-stop` 依赖桌面版 ADB 的 shell 权限，普通 APK 需要改用页面级恢复并为确有必要的冷重启提供人工接力。[项目源码](https://github.com/yufish123-Gmail/Mgame-auto-count-tasks/blob/d352788a8018635857e485f22c4d198f7e942a95/coin_helper.py) · [Android 14 进程管理限制](https://developer.android.com/about/versions/14/behavior-changes-all#kill-background-processes)

| 方案 | 适用情况 | 主要代价 |
|---|---|---|
| Kotlin 原生 + 无障碍服务 | 推荐。适合最后交付一个可独立安装的 APK | 把 Python 状态机迁移到 Kotlin，重新验证识别和机型兼容性 |
| Kotlin 外壳 + Chaquopy | 希望先复用纯 Python 规则时可做过渡原型 | Android 控制和 OCR 依然要用原生接口。需验证 NumPy、OpenCV 的 Android ABI 与构建版本，再评估 Python 桥接和 APK 体积 |
| 无障碍服务 + MediaProjection | 确实需要持续画面流时的备选 | 录屏只负责采集，点击和切换仍由无障碍服务执行。增加授权会话与前台服务生命周期。只采集美团窗口时，跳转后的其他 APP 会不在采集范围内 |

方案依据见 [Chaquopy 官方文档](https://chaquo.com/chaquopy/documentation/)、[OpenCV Android AAR](https://opencv.org/opencv4android-usage-models/) 和 [Android MediaProjection](https://developer.android.com/media/grow/media-projection)。

## 2. 在手机上控制和调用

| 桌面版能力 | APK 对应实现 | 核对要点 |
|---|---|---|
| `dumpsys activity` 确认前台 | 用无障碍窗口事件、活动窗口和根节点的包名确认当前 UI | 单条事件的 `className` 可能是控件类名。计时或点击前还要确认窗口和画面；系统弹窗、分屏和未知包进入暂停路径 |
| `input tap`、`input swipe` | 可用节点优先调 `ACTION_CLICK` 或滚动动作。自绘页面用 `dispatchGesture()` | 节点可能缺失或已经变化。坐标点击之前重新确认同一任务、按钮、窗口和屏幕映射，动作之后检查页面变化 |
| `screencap` | SDK 30+ 用 `takeScreenshot()`。SDK 34+ 有可靠窗口 ID 时可用 `takeScreenshotOfWindow()` | 截图回调异步，把 `HardwareBuffer` 转成可处理的位图并释放。截图过频时短暂退避。窗口截图与屏幕手势的坐标原点可能不同 |
| Windows OCR、OpenCV | `com.google.mlkit:text-recognition-chinese` 返回文字和边界框，`org.opencv:opencv` 执行局部匹配 | 只识别标题、按钮、数字等区域。在真机重标 MIUI／HyperOS 系统栏、显示缩放、深色模式和广告样式 |
| `input keyevent`、`am start` | 用 `performGlobalAction()` 执行 Back／Home。需要从入口打开美团时使用公开的 Launcher Intent | 优先返回当前任务栈。启动后重新确认美团确实在前台；系统不允许拉起时交给用户点通知或手动切回 |
| `pm list packages` | 通过 `PackageManager` 按已知包名检查安装情况，有需要时在 Manifest 中逐个声明 `<queries>` | 安装检查和“谁在前台”是不同问题。缺少目标 APP 就记录跳过或等待人工处理 |
| `am force-stop` 后回任务页 | Back → 唤回美团 → 从「我的 → 领币页 → 加次数」重新进入 | 返回页面可能刷新奖励，也可能还保留当前状态。确需冷重启时，由用户在手机上操作，再从实际进度继续 |

接口依据见 [无障碍服务指南](https://developer.android.com/guide/topics/ui/accessibility/service)、[AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[ML Kit 中文 OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android?hl=zh-cn)、[应用可见性](https://developer.android.com/training/package-visibility/declaring)、[PackageManager API](https://developer.android.com/reference/android/content/pm/PackageManager) 和 [后台启动 Activity 的限制](https://developer.android.com/guide/components/activities/secure-bal)。

**应用间调用保留美团的原始任务路径。** 例如「跳转登录 APP 领奖励」应点击美团里对应的「去完成」，等目标 APP 的前台窗口得到确认后连续停留 10 秒，再返回美团等待「可领取」，点击后核对 `x/8`。直接从助手打开目标 APP 无法验证是否走过美团的任务入口。发生切页时重新计时。桌面实测中，三个目标 APP 都能打开，任务状态在重新打开美团后才陆续更新。安卓版需实测页面级恢复是否够用。[项目状态](https://github.com/yufish123-Gmail/Mgame-auto-count-tasks/blob/d352788a8018635857e485f22c4d198f7e942a95/%E9%A1%B9%E7%9B%AE%E7%8A%B6%E6%80%81.md#跳转登录-app-奖励复盘)

## 3. 权限和运行条件

| 权限或能力 | 谁授权／怎样配置 | 首版计划 |
|---|---|---|
| 无障碍服务 | Manifest 的 `<service>` 声明 `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`、服务 intent filter 和配置元数据；由用户进入系统「无障碍」手动开启 | 必需。`BIND_ACCESSIBILITY_SERVICE` 是服务绑定保护，系统用它绑定服务 |
| 读控件、手势、截图 | 服务 XML 声明 `canRetrieveWindowContent`、`canPerformGestures`、`canTakeScreenshot`。若需枚举交互窗口，配置 `flagRetrieveInteractiveWindows` | 必需或按功能启用。只在用户主动开启的一次运行会话中，处理预期任务涉及的包名和页面 |
| 侧载安装与受限设置 | 用签名 APK 通过系统安装。Android 13+ 某些安装来源触发「受限设置」，需要用户在系统允许的情况下手动确认，再开启无障碍 | 首次配置。系统、设备管理策略或厂商 ROM 仍可能限制启用 |
| 通知 | 如果使用通知显示进度和提供「停止」，Android 13+ 按系统规则请求 `POST_NOTIFICATIONS` | 可选。助手页面保留停止入口 |
| MediaProjection | 每次录屏会话由用户确认。针对 Android 14+ 的应用还要配置 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `mediaProjection` 服务类型 | 备选截图方案才引入 |
| 日志和截图 | 写入应用私有目录 `filesDir/runs`，需导出时由用户选择或分享指定文件 | 私有文件无需广泛存储授权。默认保留状态和计时，调试时有选择地保存裁剪图 |

权限依据见 [无障碍服务声明](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[Android 受限设置](https://support.google.com/android/answer/12623953)、[通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、[录屏会话](https://developer.android.com/media/grow/media-projection) 和 [应用专属存储](https://developer.android.com/training/data-storage/app-specific)。

首版可省去相机、麦克风、通讯录、位置、所有应用查询及悬浮窗权限。用内置 OCR 模型，识别无需在首次运行时下载模型，助手也无需为 OCR 申请网络权限。美团、游戏及外部 APP 的活动仍使用各自网络。首轮真机运行时手机需解锁并保持亮屏；助手自己的 `FLAG_KEEP_SCREEN_ON` 随 Activity 退到后台便失效。锁屏、拼图验证、登录提示、系统权限弹窗或受保护画面应暂停交由用户处理。[ML Kit 内置模型](https://developers.google.com/ml-kit/vision/text-recognition/v2/android?hl=zh-cn) · [亮屏行为](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on) · [受保护窗口](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)

## 4. 迁移和验收

1. **先做只读原型。** 用户从助手进入美团任务列表，手机断开 USB。原型能在目标手机稳定识别领币次数、任务标题和按钮，并正确处理截图失败、屏幕缩放及旋转。用仓库现有模板和本地历史截图对照 Android 识别结果。
2. **验证一个完整闭环。** 先做独立的跳转任务或单项浏览，确认「点击 → 目标窗口 → 前台计时 → 返回 → 奖励核对」。用同一串行会话执行，页面变化时停止输入。记录计时结束和奖励确认两个事件；超时后先核对平台进度，再决定是否重试。锁屏、服务被撤销或用户点停止后，取消后续操作。
3. **迁移八个日常阶段。** 保留日常和独立跳转两个入口。先把 5／10 秒活动、新旧游戏计时路径、听书／短剧及人工验证接力逐项验明，再串联测试。对比同日同任务数下的真实到账、重复操作和恢复耗时。移除 PC 传输会减少一部分调用开销。首轮验证沿用桌面版的 10 秒跳转、62 秒游戏后台等待和 70 秒前台补跑等设置。
4. **生成并验证安装包。** 建议在仓库内建 `android/` 模块。用 Android Studio 或 Gradle Wrapper 生成可安装 APK，文件位于模块的 `build/outputs/apk/`。用于手机日常安装的版本应固定包名并使用一致的签名以支持覆盖升级，签名材料单独保存并排除在 Git 外。确认目标手机 ABI，并验证 OpenCV 等 native 库的 16 KB 页兼容性。最后用系统安装器安装，再实际验证断开电脑后的完整流程。[构建 APK](https://developer.android.com/build/build-for-release) · [应用签名](https://developer.android.com/studio/publish/app-signing) · [16 KB 页兼容](https://developer.android.com/guide/practices/page-sizes)

如果后续选择 Google Play 分发，本项目应按预定义任务辅助的实际用途申报，保持 `isAccessibilityTool=false`，在应用内显著披露用途并取得用户同意。Google Play 对自动化用途有范围限制，是否符合发布要求取决于具体实现和审核结果。签名侧载的安装条件取决于设备与地区规则。[Google Play 无障碍 API 政策](https://support.google.com/googleplay/android-developer/answer/10964491) · [Android 开发者验证 FAQ](https://developer.android.com/developer-verification/guides/faq)

本次交付为代码和官方接口分析。无障碍服务能否在当前手机的 ROM 下稳定取得游戏及广告窗口、驱动返回美团，并读出最终到账，需用原型真机验证。
