# 0.3.0 验证记录

日期：2026-09-27。Windows、Android Studio JBR 21、Gradle 8.10.2；Android API 28 模拟器 emulator-5554，1440×2560。

## 构建与静态检查

执行 `gradlew.bat assembleRealDebug assembleMockDebug testRealDebugUnitTest lintRealDebug` 成功。

17 个 JVM 测试全部通过：Alist 5 个、BaiduProvider 6 个、BaiduOAuth 6 个。新增覆盖账户身份读取、伪装百度域名授权页拒绝、凭据持久化失败不发布新令牌。lint：0 errors、10 warnings，均为依赖版本更新提示。

真实版和模拟版 APK 均已安装到模拟器。真实版覆盖安装后保留原来的 200 条 Alist 索引。真实版未配置百度应用凭据；设置页提示“此版本尚未开放百度登录”，按钮父节点 disabled，未发起真实百度授权。

## 模拟授权与目录加载

运行 `node tools/mock-baidu.cjs`，通过 adb reverse tcp:5246 映射到模拟器。

1. 空图库进入设置，点击连接百度网盘，显示 TEST-1234 模拟验证码。
2. 通过本机 fixture 批准授权，App 自动完成轮询、读取模拟账户身份并进入文件夹选择。
3. 浏览到 /Photos，点击“使用此文件夹”，自动进入图库并完成索引：201 张图片和子目录中 1 张无缩略图图片，共 202 张。
4. 请求记录显示 /Photos 分页 start=0、200，并扫描 /Photos/Nested；图库没有扫描 /Other。
5. 可见缩略图实际显示；无缩略图图片在模拟器非计费网络生成小图。点击详情加载对应 fs_id 的原图，未选中的相邻页不自动请求原图。
6. 强制停止并重启，授权和目录自动恢复，显示 202 张，自动进行新一轮更新。

截图：[模拟图库](screenshots/baidu-gallery.png)。模拟图片内容是项目已有图库截图，所有照片是测试响应，不是真实网盘文件。

## 自动更新回归

选好目录后运行 `tools/smoke-baidu.ps1`，结果全部 PASS：

- 正常模式重启：202 张 · 同步完成。
- 返回接口错误重启：202 张 · 暂时无法更新，已保留本地图库。
- 删除两张图片的模式重启：200 张 · 同步完成。
- 恢复正常模式重启：202 张 · 同步完成。
- 请求范围检查通过：扫描限制在选定目录；重启无需重新授权或选择目录。

检查真实版的 [设置截图](screenshots/baidu-real-settings.png) 与 [UI 节点](screenshots/baidu-real-ui.xml)，确认模拟数据与真实配置使用独立应用。

## 未验证范围

没有真实百度应用凭据或账号授权，不宣称真实 API/CDN、限速或所有格式可用。未进行十万张规模、滚动帧率、首屏耗时基准测试。WorkManager 调度代码已编译接通，但未等待一小时验证后台周期触发；后台更新不是实时推送。当前 Room 仍读取完整列表，首次未缓存内容仍需网络等待，过期缩略图自动恢复尚待实现。
