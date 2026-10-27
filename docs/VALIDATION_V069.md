# 0.6.9 取链接交接与尺寸校验修正

日期：2026-09-27。用户反馈“取图被中断”与“未取得完整尺寸图片”来自不同手机、不同笔记，无法提供型号、版本或分享链接，要求直接尝试修复。不能将两个报错均归因于 Z50 Ultra。

## 修改

- QuickImportCaptureActivity 先以条件更新将 capturing 交接为 queued，并等待 WorkManager 入队操作完成（最多 2 秒），再关闭焦点窗口；此前先 finish，再由后台线程写入和排队，存在交接尚未落盘就失去前台窗口的风险。
- queueCapture、failCapture、stageCapture 只更新 capturing 状态；迟到的窗口关闭、超时或服务中断不覆盖已排队、已失败或已取消的任务。
- 服务启动取链接窗口后最多等待 4.5 秒交接；窗口被关闭、无法获得焦点或未完成交接时及时失败并按现有偏好振动。进程被系统杀死时仍只能在恢复后识别遗留任务，不能保证杀进程时立即振动。
- capturing 记录保留最后阶段。30 秒的遗留任务过期提示包含所处阶段，替代笼统“取图被中断”。
- BitmapFactory 未返回有效尺寸时，Android 9+ 尝试 ImageDecoder，以 1×1 解码获取图片原尺寸与 MIME，不转码上传文件；限制尺寸不超过 1.5 亿像素。
- JPEG/HEIF 明确声明 90°/270° EXIF 旋转时允许对应宽高交换；没有旋转标记不猜测。仍拒绝较小图片、比例不符或超过像素限制的图片。
- 分离无法读取尺寸和实际尺寸不符的错误；后者显示取得与预期的像素尺寸。复制导图诊断增加最近下载尺寸、格式、方向和字节数，不包含帖子链接或正文。

## 验证

- real/mock Debug、mock AndroidTest 构建通过；75 项 realDebug JVM 测试通过，0 failures / 0 errors；lint 0 errors / 14 warnings。
- 模拟器 operation=captureWindow：真实焦点窗口读取新复制的支持域链接并持久化任务，后台 Worker 启动。测试账户故意不匹配，Worker 在请求图源或上传之前停止：focused-clipboard=received, handoff=persisted, worker=started, network=none。
- operation=importRecovery：独立测试数据库验证迟到失败不覆盖 queued、失败后迟到剪贴板不重新排队、过期保留最后阶段；生成带 EXIF orientation=6 的 JPEG，通过方向尺寸校验，较小图片仍被拒绝。
- operation=heif：实际 HEIF 样本1440×960成功读取元数据及解码，.heic/.HEIF索引2项；INSTRUMENTATION_CODE=0。
- 本轮无原报错笔记复现、无两个报错手机的实机测试，不能保证覆盖所有厂商进程限制或图源尺寸差异。若源站确实只返回小图，仍拒绝导入，未放宽最小尺寸或猜测 CDN 地址。

## 安装包

exports/PixNexus Gallary-0.6.9.apk（real Debug，开发签名，versionCode 21）。SHA256：8069080c80a0b24625741b87d664886593f71339d6dec31e8220260dde478542。
