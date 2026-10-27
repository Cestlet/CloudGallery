# 百度网盘内置接入

## 本阶段目标与状态

App 采用手机内置 Kotlin 连接器直接请求百度接口。0.4.2 起只保留百度网盘接入，没有中转服务或其他连接入口。

0.3.0 已接通底层连接器、授权页面、令牌刷新、加密会话保存、身份读取、目录选择及自动图库加载。源码默认不包含应用凭据。本机真实授权已成功，App 完成令牌交换、身份读取并展示真实目录选择页，等待用户指定照片目录验证图片读取。独立模拟版通过本机假服务验证完整流程。

## 接入模块

- BaiduProvider：实现 StorageProvider，目录按名称分页，start/limit 配合，web=1 获取缩略图。
- 保存 fs_id 到 Room 索引。查看原图通过 fs_id 获取 dlink，不依赖扫描期间缓存的临时直链。
- 下载请求设置 User-Agent: pan.baidu.com。API 请求和图片下载均不输出访问令牌或完整响应到日志。
- 0.3.2 修复原图下载缺少 access_token 导致的 HTTP 403。filemetas 返回的 dlink 追加当前令牌，保留签名参数并检查初始下载域名；已完成真实原图读取验证。
- 数据源标识包含账户标识和根目录；读取路径必须在选择范围内。
- BaiduOAuth：设备码请求、授权状态轮询、等待/限频/取消/过期处理、refresh_token 刷新。
- BaiduSession：并发访问时仅刷新一次；先调用设备内加密存储回调，再发布新令牌。刷新时保留当前目录选择。
- Room 1→2 显式迁移增加 remoteId，保留原有索引及扫描队列。

## 组合方式

```kotlin
val oauth = BaiduOAuth(appKey, secretKey)
val session = BaiduSession(savedTokens, oauth, persist = { tokens ->
    // 接入设备密钥加密存储；此回调完成持久化后才返回。
})
val provider = BaiduProvider(accountId, selectedFolder, { session.accessToken() })
GalleryScanner(database).scan(provider) { status -> /* 更新状态 */ }
// PhotoCache 已接受 StorageProvider，可用于该挂载来源。
```

accountId 应使用已授权用户稳定标识。应用 App Key、Secret Key 与账户访问令牌是不同配置；不使用其他项目公开的应用凭据。真实凭据仅配置在被忽略的本机文件中，不写入源码和示例。

正式发版前必须确定开放平台允许的移动端授权方式及凭据交换方式。设备码模块接受配置参数，用于已登记应用和模拟测试；不能把 APK 中的应用 Secret 当作可保密的服务端凭据。

## 用户界面与自动加载

1. “添加百度网盘”进入授权页，成功后读取账户身份并加密保存会话。
2. 用百度目录接口展示可选择的文件夹，用户选定后直接进入图库。
3. 进入图库自动更新；启动先读缓存，不等待授权刷新或全库遍历。
4. 可见图片按需加载，按滑动方向预取附近 24 张缩略图；非计费网络后台缓存最近最多 1000 张。缓存命中直接显示，详情渐进加载原图。Room 当前仍为完整列表 Flow，分页是下一阶段工作。
5. 缺少缩略图时生成小图，处理网盘权限、频率限制及普通账户下载速度差异。

授权页支持取消，成功后自动展示目录选择。目录浏览以网盘根目录为范围，图库扫描严格限定在选定目录内。进入前台检查变化，60 秒内不重复启动；每小时 WorkManager 检查受系统调度限制，不保证精确执行时刻。

缺少缩略图时，非计费网络会下载原图并生成最大边不超过 768 像素的小图；计费网络只生成已缓存原图的小图。大图库分页、真实网盘限速及过期缩略图恢复仍待完善。

## 配置和模拟隔离

真实授权配置、构建和验收步骤见 [真实授权接入](REAL_AUTH_SETUP.md)。增加 `:app:verifyBaiduConfig` 构建任务，不输出密钥；支持 CLOUDGALLERY_BAIDU_APP_KEY 和 CLOUDGALLERY_BAIDU_SECRET 环境变量，优先于本地配置。

`local.properties` 可设置 `baidu.appKey` 和 `baidu.secret`，真实版默认空配置。禁止使用其他项目的公共应用凭据或提交本机配置。此入口用于开发联调，正式发布需确认密钥交换方案。

`mock` flavor 使用独立包名 `com.cloudgallery.app.mock`、假凭据及 `http://127.0.0.1:5246/`；页面明确提示模拟。运行 `node tools/mock-baidu.cjs` 并映射 adb reverse 端口可验证。真实 flavor 使用百度 HTTPS 端点，模拟凭据不会进入真实版。

## 验证

`gradlew.bat assembleRealDebug assembleMockDebug testRealDebugUnitTest lintRealDebug` 通过，完整记录见 [0.3.0 验证](VALIDATION_V03.md)。

当前测试覆盖百度目录分页、fs_id 原图、认证错误、路径隔离、身份读取、授权状态处理、并发刷新、持久化失败、缺少配置及授权页地址校验。最新验证见 [0.4.2 验证](VALIDATION_V042.md)。

lint 未发现错误，依赖版本提示详见构建报告。

覆盖安装到 Android API 28 模拟器后，Room 1→2 迁移成功，原先 200 张图片索引及未完成扫描状态保留。界面证据：screenshots/migration-baidu.xml。

真实设备码请求、用户授权、身份读取、806 张照片扫描、缩略图缓存及两张真实原图读取已验证。大规模性能验收尚未完成，详见 [缩略图优化记录](THUMBNAIL_PERFORMANCE.md) 和 [0.3.2 验证](VALIDATION_V032.md)。

## 接口参考

- 文件列表参考实现：https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/file_list.go
- 文件元数据：https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/download_meta.go
- 设备授权：https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/auth_device_code.go
- 设备令牌：https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/auth_device_token.go
- 百度开放平台：https://pan.baidu.com/union
