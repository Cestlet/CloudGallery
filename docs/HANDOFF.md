# PixNexus Gallary 交接文档

> 当前版本 0.6.12：针对 vivo API 36 的 23 节点日志，兼容“复制链接”非 Button 父节点和唯一可点击叶子节点；补充同次导图的控件类型、目标、点击结果与阶段耗时诊断。旧日志没有控件类型，不能断言这是该实机的唯一故障；vivo 实机尚未验证。见 [0.6.12 验证](VALIDATION_V0612.md)。

> 0.6.11：真实 XHS 复现并修复直接图源 2560×2081、页面却标注 3366×2736 的尺寸误判；取消和任务状态保护、备用图源、图库 HEIF 预览及原图并行加载。83 项测试通过，修正后真实 XHS 连续 4 次成功。见 [0.6.11 验证](VALIDATION_V0611.md)。

> 0.6.10：原图下载到手机相册、相册页面新建网盘文件夹及子文件夹、空相册持久化；Room 2→3 非破坏迁移。78 项单元测试、模拟器图库回归、lint 通过。安装包 `exports/PixNexus Gallary-0.6.10.apk`。详见 [0.6.10 验证](VALIDATION_V0610.md)。

> 0.6.6 修复分享控件对象误判、共享下载连接、最多两个导入并发、云端 HEIF/HEIC 扫描及升级索引刷新。67 项单元测试通过；模拟器连接及无障碍绑定已恢复，云端 HEIF/HEIC 显示与连续去重通过；新笔记首次复制有 1 次失败，仍待修复。详见 [0.6.6 验证](VALIDATION_V066.md)。

> 历史版本 0.6.6，应用名统一为 PixNexus Gallary，新增小红书快速导图、独立网盘目标目录和可选手机相册保存。模拟流程通过，分享误触后已改为语义按钮动作，模拟器真实小红书到模拟网盘链路已通过，实体手机及真实网盘仍待验证；详见 [README](../README.md) 和 [0.6.0 验证](VALIDATION_V060.md)。下方是 0.2.0 的历史交接记录，旧连接模块及部分计划已过时。

这份文档用于**新开一个线程**继续 CloudGallery 的开发，替代此前已经膨胀到 6 MB 的旧会话。

开工前先读这里，再按需查 [构建大纲](BUILD_OUTLINE.md)。

- 交接时间：2026-09-27 01:30（Asia/Shanghai）
- 上一版本：0.2.0（已完成并验证）
- 工作目录：`<PROJECT_ROOT>`

---

## 一、项目是什么

Android 云图库。用户把自己的 Alist 照片同步成**本地索引**，缓存已浏览的图片，原图按需下载 —— 目标是浏览体验接近本地图库。

技术栈（版本已锁定，别随手升级）：

| 项 | 版本 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose |
| 构建 | AGP 8.7.3 / Gradle 8.10.2 / Kotlin 2.1.0 |
| Compose BOM | 2024.12.01 |
| SDK | minSdk 26，compileSdk / targetSdk 35，JDK 17 |
| 存储 | Room（索引与扫描队列） |
| 网络 | OkHttp（Alist API） |
| 图片 | Coil |
| 异步 | Coroutines |
| 架构 | UI → ViewModel → Repository → Room / StorageProvider |

---

## 二、当前状态：0.2.0 已完成

能跑通的闭环：**配置 Alist → 扫描 → 本地索引 → 图片查看 → 离线缓存**。

已实现：

- 单个 Alist 实例 + 单个照片根目录；支持地址、根目录、Token、可选目录密码，公开目录可不填 Token
- 递归分页扫描 JPEG / PNG / WebP，Room 保存索引和扫描队列
- 暂停、失败或进程被杀后可点「同步图库」续扫
- **只有整轮扫描成功才移除未发现的本地记录**，网络或认证失败绝不触发删除
- 时间轴按文件修改日期分组；相册按完整目录路径分组
- 优先读远端缩略图；没缩略图时进详情才拉原图；相邻未选中页不自动加载原图
- 原图直链按需解析；缓存按内容版本区分，上限 256 MB，最多同时下载 3 个
- 连接信息用 Android Keystore AES-GCM 加密保存

验证结论（详见 [0.2.0 验证记录](VALIDATION_V02.md)）：

- 编译、Android lint、JVM 单元测试全部通过；lint 为 **0 errors / 9 warnings**（9 条都是"依赖有新版本"的提示）
- 5 个单元测试覆盖：请求参数与认证头、新版分页、旧版 total 分页、认证错误、空分页、地址/路径/来源标识
- API 28 模拟器实测通过：分页根目录 + 子相册共索引 202 张；第二页报错时保留 202 张和扫描队列，服务恢复后扫描成功；模拟删除 2 张后完整刷新为 200 张；强制停止重启仍读到 200 张；认证失败不清空索引；无缩略图文件在网格显示提示、进详情后解析原图成功；关掉模拟服务后重启 App 仍能看到索引和已缓存原图

---

## 三、下一步（按优先级）

**1. 真实 Alist 实例联调（最高优先级，也是当前最大的未知）**

至今只在本地模拟服务上验证过，**没有碰过真实 Alist**。以下都还没被证明兼容：

- 不同存储驱动（本地 / 阿里云盘 / OneDrive / S3 等）返回的字段差异
- 鉴权方式：目前只支持 Token 或公开访问，**没有账号密码登录流程**
- 带 Referer 校验的媒体直链
- 缩略图字段为空的驱动

联调需要用户提供：Alist 地址、版本、认证方式、照片目录。凭据不要提交进仓库，也不要打进日志。

**2. 后台自动刷新** —— 目前只有手动同步，没接 WorkManager。

**3. 大图库性能与 Room 分页** —— 现在用完整列表 Flow，**十万张量级完全没验证过**。大纲要求先测 1 千、1 万，再测 10 万条索引。

**4. EXIF 拍摄时间** —— 现在时间轴用的全是文件修改时间。注意：不得为了拿 EXIF 而批量下载所有原图。

**5. 缓存管理界面** —— 容量设置与清理入口还没有 UI。

**6. 缩略图独立压缩** —— 目前缓存直接保存下载到的文件，没有把大原图压成独立小图。

完整的页面规划、数据模型、扫描与图片管线设计都在 [构建大纲](BUILD_OUTLINE.md)，动手前值得通读。

---

## 四、代码地图

```
app/src/main/java/com/cloudgallery/app/
  MainActivity.kt                       入口（很小，逻辑都在别处）
  ui/CloudGalleryApp.kt                 18.7 KB，Compose UI 主体，改动最频繁
  GalleryViewModel.kt                   5.1 KB，状态与业务编排
  data/
    AlistProvider.kt                    4.0 KB，Alist API 协议实现
    GalleryScanner.kt                   2.2 KB，目录遍历与索引写入
    GalleryDatabase.kt                  1.8 KB，Room 定义
    ConnectionStore.kt                  2.3 KB，Keystore 加密凭据
    PhotoCache.kt                       3.2 KB，缩略图/原图缓存与淘汰
    DemoRepository.kt                   0.8 KB，72 张离线示例数据
app/src/test/java/com/cloudgallery/app/data/
  AlistProviderTest.kt                  5 个单元测试
app/schemas/com.cloudgallery.app.data.GalleryDatabase/1.json   Room schema v1，改表要留意迁移
```

配套资源：

- `docs/BUILD_OUTLINE.md` —— 完整构建计划与验收标准
- `docs/VALIDATION.md` / `docs/VALIDATION_V02.md` —— 各版本实测记录
- `docs/screenshots/` —— 验证截图（png）与 uiautomator 层级转储（xml）
- `tools/mock-alist.cjs` —— 本地模拟 Alist 服务
- `tools/smoke-sync.ps1` —— 可复跑的同步冒烟脚本

---

## 五、构建与测试

```powershell
cd "<PROJECT_ROOT>"
.\gradlew.bat assembleDebug                          # 产出 APK
.\gradlew.bat lintDebug testDebugUnitTest            # 检查
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（最近一次构建 2026-09-27 01:22，16.01 MB，可用）

Android SDK 路径在 `local.properties`（`D:/Download/AndroidStudio/AndroidSDK`），该文件已被 `.gitignore` 忽略。

---

## 六、本地联调流程

```powershell
# 1. 启动模拟 Alist 服务，监听 127.0.0.1:5245
node tools/mock-alist.cjs

# 2. 把模拟器端口反向映射到本机
adb -s emulator-5554 reverse tcp:5245 tcp:5245

# 3. App 内进「设置」，连接 http://127.0.0.1:5245，根目录填 /
#    模拟服务只用项目截图当图片响应，不连真实网盘

# 4. 验证失败保留、恢复扫描、删除比对、重启读索引
tools/smoke-sync.ps1 -Device emulator-5554
```

---

## 七、环境与注意事项

**1. 这个项目不是 git 仓库。** 没有提交历史，任何改动都没有回滚点。建议开工第一件事就是 `git init` 并提交当前 0.2.0 状态，再动别的。

**2. Gradle 用全局的 `~/.gradle`，不要指向项目内的 `.gradle-user`。** 项目里那份 `gradle-8.10.2-bin` 只有一个 96.4 MB 的 `.part` 残包，下载没完成；全局 `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.10.2-bin\` 下才是完整解压可用的（有 `.ok` 标记）。

**3. 本机有两个 adb 设备在线**：`emulator-5554` 和 `127.0.0.1:5555`。所有 adb 命令都要带 `-s` 明确指定，否则容易装错设备。

**4. 凭据规范**：`local.properties` 只放本机 SDK 路径；Alist 的 Token 等一律不提交仓库、不输出到日志。

**5. 依赖警告不用管**：lint 的 9 条 warning 全是依赖有新版本的提示，不是代码问题。

---

## 八、给新线程的提醒：Codex 会卡死

旧的 Codex 会话膨胀到 6.24 MB、序号 600+ 之后，Codex 桌面端的某个渲染进程会跑满一个 CPU 核，导致**界面点击无反应**（但系统和其他程序正常）。本次交接就是因为这个。

规避方式：

- **按阶段开新线程**，单个 thread 的会话文件尽量别超过 2～3 MB
- 阶段结束时把状态写进这个文件（或 `README.md` / `VALIDATION_*.md`），新线程读文档继续
- 避免让 Codex 在一个线程里反复产生超大输出（整文件读取、巨型 diff、大日志）

如果界面又卡住，跑这个脚本定位（`-Fix` 会自动结束空转的子进程，不会碰持有主窗口的进程）：

```powershell
powershell -ExecutionPolicy Bypass -File D:\GithubProject\codex-watch.ps1
```

它只结束"CPU 接近跑满且没有窗口"的子进程，应用会自动重建，当前对话不丢。


## 2026-09-27 · 0.6.7 图库上传与排序

- 新增 ManualUploadWorker 持久化批次（私有 JSON、URI 授权），复用 BaiduUploader；成功项去重、失败重试、取消和状态条，上传后安排 GallerySyncWorker。
- GallerySort 六种排序，补充 MediaItem.modified/size 元数据；名字/大小不日期分组，浏览锚点按排序隔离。上传与排序 UI 在 GalleryActions.kt。
- 72 项测试通过，real/mock Debug 和 lint 通过，模拟器多选上传、指定子目录、部分失败 1/2 后重试至 2/2、原图显示、排序重启恢复通过。完整范围见 VALIDATION_V067.md。
- real Debug APK 导出 exports/PixNexus Gallary-0.6.7.apk。本轮不修改 XHS 导图剩余问题。
- 本地模拟服务仍在 5246，故障注入 uploadFailAfter=-1（关闭），mode=heif；只包含测试图片。新增 uploadFailAfter 参数用于验证部分失败重试；上传文件不再使用通用缩略图。


## 2026-09-27 · 0.6.8 Z50 Ultra / Android 14 按键问题

用户型号确认，尚无实机日志。修正 QuickImportService 在每个按键事件前检查根节点并 reset 的兼容隐患：先识别组合，再最多等待 240 ms 验证前台小红书；已知 SystemUI 浮窗可回退顶层应用，但其他应用与未知窗口拒绝。新增 quickKeyDiagnostic，设置的复制导图诊断合并最近音量事件和按钮诊断。74 项测试、real/mock Debug、lint 通过；mock 已安装，无本轮完整设备链路通过结论。用户最新要求导出，APK 为 exports/PixNexus Gallary-0.6.8.apk。模拟器无障碍服务当前关闭，排查系统设置时没有成功重新开启，不要把本轮当作服务绑定验证。详见 VALIDATION_V068.md。


## 2026-09-27 · 0.6.9 两类导图错误

用户说明两个错误来自不同手机笔记，无法提供复现信息，要求直接尝试修复。修正取链接 Activity 提前 finish 的交接风险：等待队列持久化后关闭；SQLite 条件更新避免晚到失败覆盖 queued。服务 4.5 秒交接看门狗、窗口关闭失败、过期最后阶段提示。增加 ImageDecoder 有界回退及明确 EXIF 旋转尺寸校验，复制诊断包含无链接的尺寸、方向、格式。75 项测试及 lint（0 errors / 14 warnings）、real/mock APK 构建通过。设备三个操作 captureWindow、importRecovery、heif 均通过，测试没有请求真实图源或上传真实账户；无用户原笔记复现。导出 exports/PixNexus Gallary-0.6.9.apk。完整范围见 VALIDATION_V069.md。


## 2026-09-27 · 0.6.10 原图下载与新建相册

Viewer 增加下载原图按钮与按图片的下载状态；GalleryViewModel.downloadPhoto 复用 PhotoCache 完整原图、复制私有临时文件、检查实际 MIME 后调用 MediaStore 保存，原图不转码。Android ≤28 主动点击时请求 WRITE_EXTERNAL_STORAGE；更高版本无需存储权限。成功和失败提示中英双语。相册页及相册内有新建相册按钮，当前相册路径作为父目录，BaiduProvider 使用官方 create/isdir=1/rtype=0，禁止重命名覆盖，同名错误保留输入。创建操作遵循同步移动数据偏好。新增 albums 表和迁移，scanner 保留空目录并在完整成功扫描后清除失效目录，失败扫描保留缓存。测试 Runner 的 galleryFeatures 在 mock 中验证迁移、空目录持久化与清理、PNG/JPEG/HEIC 原字节保存和失败 MediaStore 清理；普通 UI 验证新增与嵌套文件夹、重启恢复、同名错误、PNG/HEIC 下载和空相册上传入口。没有对真实百度账户执行本轮创建/下载。mock 服务 tools/mock-baidu.cjs 新增文件夹 create fixture；运行时服务器保存于内存，重启会丢失模拟新文件夹。


## 2026-09-28 · 0.6.11 无感导入与图库优化

用户要求之前的错误不再出现，同时优化图库。真实 XHS 双图笔记第二张直接来源只有 2560×2081，页面声明 3366×2736；旧尺寸规则拒绝它。acceptableXhsSourceDimensions 仅直接 fileId 来源允许长边至少 2048、至少 2MP、比例 1% 内、不超预期的高清版本，明确备用来源仍要求完整尺寸。不能当作接受任意小图的规则。修正后该图及去重、另一篇笔记连续 4 次通过，保存和上传为真实图源到本机模拟网盘。系统日志确认接收、成功及失败双震请求；实体机和真实百度上传速度未验证。

XhsSource.fetch 在可取消回调内读完响应体，慢下载取消会关闭连接；downloadXhsImage 尝试同图明确地址。QuickImportStore.update(runningOnly=true) 不覆盖已结束状态，cancel 不覆盖完成。active 检查按 500ms 节流，保存和上传边界强制检查。记录显示时间。图库并行预览/原图，GalleryPreview 使用有界 ImageDecoder/EXIF；不在图库范围的导入不再触发无关扫描。

测试时 mock 根目录改为 /Other，排序 NAME_ASC，布局 WATERFALL，quickDestination=/Other、saveLocal=true。快速导图服务经系统 UI 恢复并验证。fixture mode=source-fallback 需要 app/build/tmp/xhs-source/tiny.png；模拟文件保存在 Node 进程内，重启会消失。9 月 28 日收尾时 adb 无设备连接，没有为整理文档重启模拟器。最终包 exports/PixNexus Gallary-0.6.11.apk；详细命中场景与限制见 VALIDATION_V0611.md。
