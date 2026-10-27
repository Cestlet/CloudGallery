# 接入真实百度授权

真实版已通过百度 HTTPS 接口实现设备码授权、轮询、身份读取、令牌刷新及加密保存。完成真实授权需要自己的百度网盘开放平台应用和用户在百度页面确认。不能用百度账号密码替代应用凭据。

## 1. 准备应用

使用自己的百度账号打开 [百度网盘开放平台](https://pan.baidu.com/union)，申请接入并创建应用。在应用控制台获取 App Key（OAuth client_id）和 Secret Key（OAuth client_secret）。需要应用具备相应网盘读取权限；实际权限及审核状态以控制台为准。

这些是应用凭据，与用户的 access_token 不同。不使用其他项目的公共应用密钥。

## 2. 本地配置

参考根目录 `local.properties.example`，在现有 `local.properties` 中追加：

```properties
baidu.appKey=你的AppKey
baidu.secret=你的SecretKey
```

保留原来的 sdk.dir。local.properties 已被忽略，不要把真实值复制到示例文件或聊天中。也支持环境变量 CLOUDGALLERY_BAIDU_APP_KEY 和 CLOUDGALLERY_BAIDU_SECRET，环境变量优先。

此方案用于个人开发联调：配置会编译到真实版 APK，APK 中的 Secret Key 无法保密。发布给其他用户前需确定百度支持的客户端授权方案，或把需要密钥的令牌交换放到可信服务中。

## 3. 构建与安装

```powershell
.\gradlew.bat :app:verifyBaiduConfig :app:assembleRealDebug
adb install -r app/build/outputs/apk/real/debug/app-real-debug.apk
```

verifyBaiduConfig 只检查配置是否存在、是否为模拟值和格式，不输出凭据，也不代表百度已接受应用。不要安装 mock 版来验证真实授权。

## 4. 用户授权

真实版 App → 设置 → 连接百度网盘 → 打开授权页面。仅在百度页面登录并输入 App 显示的验证码，确认授权后返回 App；App 自动完成令牌交换、读取账户身份并进入文件夹选择。选择照片文件夹即可开始扫描。

设备码授权端点为 `https://openapi.baidu.com/oauth/2.0/device/code`，令牌端点为 `https://openapi.baidu.com/oauth/2.0/token`；目录和身份请求使用 `https://pan.baidu.com/rest/2.0/xpan/`。返回的访问令牌和刷新令牌通过设备密钥加密保存，不记录到日志。

## 验收

- 授权页显示自己的应用；身份名称对应授权用户。
- 能枚举自己的目录，选择后只索引该目录及子目录。
- 真实缩略图和原图均可读取；退出重启不要求再次登录。
- 授权失败时保留已有图库，按错误重新授权。

2026-09-27 联调进度：本机真实授权、身份读取、所选目录 806 张照片扫描、缩略图缓存及两张真实原图读取已验证。0.3.2 修复原图下载鉴权，支持网格、瀑布流、列表。大规模性能验收仍待完成。未记录应用密钥、设备码或访问令牌，未将真实账户目录名称写入文档。

接口参考：[设备码](https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/auth_device_code.go)、[设备令牌](https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/auth_device_token.go)。
