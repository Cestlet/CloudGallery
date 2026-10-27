# 0.6.6 导入稳定性及 HEIF 云端索引修复

日期：2026-09-27。用户确认 HEIF 无法显示发生在 PixNexus Gallary 云端图库。

## 修改

- 分享动作不再比较动画前后 AccessibilityNodeInfo 对象是否相同。每次从当前完整节点树重新选取唯一允许的按钮，保留来源包、可见性、按钮分支和图片页检查。最多等待 360 ms 稳定目标；已发出点击后不重复点击。未新增坐标或朋友圈操作。
- XhsSource 默认复用同一个 OkHttpClient，让不同任务复用连接池；连接超时 10 秒、读取 20 秒、单次请求 45 秒。真实速度仍受图源和网络影响。
- 最多两个任务并行，同一任务保护临时文件，同一图片串行保存相册及上传，清理临时文件时排除运行中的任务。每秒最多更新一次下载大小，保持原图字节不转码。
- 云端扫描扩展名加入 heif/heic，忽略大小写；本地副本 MIME 与实际格式对应。升级时有已连接图库则安排一次索引刷新，遵循同步移动数据开关，已有缓存继续显示。

## 已完成验证

- real/mock Debug APK 构建及 mock AndroidTest APK 构建通过。
- 67 项 realDebug 单元测试通过，0 failures / 0 errors。新增格式识别、跨图源实例连接复用、两任务并发上限、同任务临时文件互斥、同图发布互斥、取消释放资源的回归测试。
- realDebug lint：0 errors / 12 warnings。
- AndroidTest 增加 operation=heif：用临时 Room 和实际 GalleryScanner 索引 .heic/.HEIF，再通过 Coil 解码文件并检查成功。设备恢复后已运行：INSTRUMENTATION_RESULT: report=indexed=2, decoded=1440x960；INSTRUMENTATION_CODE: 0。

## 尚未完成的设备验证

本轮 adb devices 为空。尝试启动原雷电实例，但实例随后停止，adb connect 127.0.0.1:5555 被拒绝。未修改虚拟机磁盘、重置模拟器或更改主机虚拟化配置。

因此没有本版本连续触发、菜单变化恢复、实际下载速度或 HEIF 原图/缩略图显示的设备通过结论。0.6.5 的模拟器实测是历史证据，不能代替本版本回归。实体小米 13 / Android 16 / 小红书 9.48 也未连接测试。

待设备恢复：安装 mock APK 和 AndroidTest APK，将 Nokia HEIF 样本放入 mock 应用 files/heif-test.heic，运行 operation=heif；随后使用真实小红书图片页连续双音量触发，核对任务状态、模拟上传次数及去重。仅上传到模拟网盘。

HEIF 测试样本为 Nokia 官方仓库 summer_1440x960.heic（200508 字节，预期 1440×960），位于 app/build/tmp/heif-test/sample.heic；不打包进正式 APK。样本来源：https://github.com/nokiatech/heif/tree/gh-pages/content/images 。

## 连接恢复补充

已通过雷电内置 ld 通道确认 sys.boot_completed=1，但 init.svc.adbd=stopped。LdNatNetwork0.log 同时记录 VBoxNetNAT 的 0xc0000005 异常，NAT 进程消失、DHCP 进程残留；5555 没有监听。

清理明确属于 LdNatNetwork0 的残留 DHCP 并重启现有 NAT，依据该实例 wlan0 地址 172.16.1.4 添加仅绑定 127.0.0.1 的 PixNexusDebug0 转发规则，设置本次运行的 service.adb.tcp.port=5555 并启动 adbd。adb devices -l 已显示 127.0.0.1:5555 device，HEIF 设备测试随后通过。未重置磁盘、账户或应用数据，也未修改防火墙。adbd 启动参数是本次运行状态，不保证下一次雷电启动自动保持；必要时可运行 tools/recover-ldplayer.ps1（仅针对原实例 0），脚本在当前实例重复运行连接成功。

连续导入仍未通过本版本回归：无障碍设置显示快速导图已开启，但系统 dumpsys accessibility 的 services 为空，uiautomator 与探针取得空节点树。通过设置界面重新切换已有授权服务后仍未绑定。小红书 9.33.4 可正常显示发现页，此问题尚未解决。HEIF 本次证明实际索引和 Coil 本地解码，不代表真实百度网盘 HEIF 缩略图链路已验证。

## 后续真实 App 回归（19:06）

Android 9 / API 28，真实小红书 9.33.4，PixNexus Gallary 0.6.6-mock，上传仅限本机模拟网盘。原雷电实例正常关闭再启动后，无障碍绑定和节点树恢复，adb 正常注册为 emulator-5554。旧图源诊断服务通过系统设置关闭，保留快速导图服务。

测试中小红书在 UiAutomation 扫描阶段退出；系统日志有 ART Profile Saver 的 SIGSEGV 和 dex2oat 的 32/64 位库不匹配。用户确认手动浏览正常。随后停止扫描小红书的 UiAutomation 节点，改用截图安排测试导航；导图动作仍由正式服务执行语义 ACTION_CLICK，双音量触发来自模拟器输入设备。为隔离系统崩溃，曾将本实例 dalvik.vm.usejitprofiles 临时置 false，测试结束已恢复原值 true。不将这次隔离措施打入 App 或推广到实体手机。

| 检查 | 实际结果 |
| --- | --- |
| 首次真实笔记导入 | 1/4，324 ms 请求剪贴板捕获，约 2.52 秒达到完成；WebP 上传 /Other，保存 URI 1119 |
| 同图连续再触发 5 次 | 全部完成，约 1.45–1.53 秒；复用 URI 1119；模拟 uploads/creates 保持 1/1 |
| 切换第 2 张 | 成功保存 URI 1120；第二次仍在第 2 张时正确去重，没有多创建 |
| 切换第 3 张 | 3/4，约 2.02 秒，URI 1121；此阶段累计模拟 uploads/creates 为 3/3 |
| 分享菜单已打开时触发 | 不新增任务，明确失败，未继续点击；系统 vibrator 记录 [0,70,100,70] 波形、通知用途，符合失败双震 |
| HEIF/HEIC 云端图库 | 模拟 /Photos/Nested 加入真实 HEIF 字节、无远端缩略图；扫描后 3 张，两张 HEIF/HEIC 在网格生成缩略图，sample.HEIC 和 sample.heif 原图查看均显示成功 |
| 关闭手机相册保存 | 新笔记首次失败；再次触发约 3.33 秒完成，实际源格式 HEIF，saveLocal=0、localUri 为空，仅上传 /Other |

上述计时为脚本从触发到观察任务状态的近似时间，包含复制、解析、下载和本机模拟上传，不代表真实百度网盘上传速度。首批 11 个真实笔记导入任务为 10 个 completed、1 个 failed（另外一次分享菜单打开的预期失败未创建任务）。没有观察到朋友圈编辑页，也没有“分享按钮已变化”错误。完成任务清除了链接，私有 quick-import 临时目录为空。

### 剩余失败

新笔记首次触发失败，detail=未取得新复制的帖子链接，请重试；313 ms 已请求剪贴板捕获，约 1.67 秒终止；再次触发成功取得 HEIF。这一失败说明新笔记的复制/剪贴板就绪仍有时序问题；当前证据没有分辨是哪一段延迟，尚未修改生产逻辑，不能把这次回归描述成全程稳定。实体小米 13 / Android 16 / 小红书 9.48 仍未实测。

### 测试状态恢复和工具

quickSaveLocal 恢复 true，quickDestination 保持 /Other，quickImport 保持已启用；未操作真实网盘。mock-baidu.cjs 新增 mode=heif，仅在测试模式读取 Nokia 样本，并保留模拟上传的真实字节供原图接口使用。正式 APK 无新增代码或版本变更。

截图：screenshots/v066-heif-grid.png、screenshots/v066-heic-detail.png、screenshots/v066-heif-detail.png；结构化任务快照在 app/build/tmp/v066-native-results.json（仅含任务状态，不含源链接）。
