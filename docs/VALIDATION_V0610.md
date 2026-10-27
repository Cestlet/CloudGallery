# 0.6.10 原图下载与新建相册验证

日期：2026-09-27。包名保持 com.cloudgallery.app，应用名 PixNexus Gallary，versionCode 22。

## 功能

- 查看原图的顶部工具栏增加下载按钮；下载中转圈并禁止同图重复触发。复用完整原图缓存，按实际格式保留 JPEG/PNG/WebP/HEIF/HEIC 原字节，通过 MediaStore 保存到 Pictures/PixNexus Gallary。成功/失败有提示，私有临时副本结束后清理。Android 8/9 未授权时在用户点击后请求存储权限，Android 10+ 无需此权限。按设置的原始内容移动数据偏好加载原图。
- 相册页面与相册内增加新建相册按钮，输入名称在当前目录创建文件夹，可创建子相册。名称校验防止路径穿越与非法字符，冲突提示并保留输入，不自动重命名、不覆盖文件。按同步文件移动数据偏好执行。
- 空目录独立存入 Room albums 表，不依赖照片生成相册；空相册可打开及上传。Room 2→3 迁移保留媒体和既有相册，索引扫描失败不清空相册，完整成功扫描后移除失效目录。
- 文件夹协议参照 [百度官方 SDK Mkdir](https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/file_manager_mkdir.go)：POST create，isdir=1，rtype=0。

## 检查结果

- :app:testMockDebugUnitTest：78 tests，0 failures，0 errors，0 skipped；新增真实 HTTP 模拟服务器创建请求、同名冲突、图库范围和非法名称检查。
- real/mock Debug 及 mock AndroidTest 构建成功；:app:lintRealDebug：0 errors，14 warnings。
- emulator-5554 / Android 9 通过普通界面创建 /Photos/TestAlbum-0610 及其子文件夹 NestedAlbum，空相册显示、上传选择器打开、重启恢复、同名冲突错误提示通过。
- 普通界面 no-thumb.png 与 sample.HEIC 点击下载成功并显示手机相册保存提示。
- instrumentation operation=galleryFeatures：Room v2→v3 实际 SQLite 升级保留旧图片及 remoteId；空目录重开数据库仍存在；网络失败保留旧目录，恢复完整扫描后清理删除目录；PNG/JPEG/HEIC 保存后读取 MediaStore 与源文件逐字节相同；复制中断不留下不完整记录。测试自己创建的媒体及数据库结束后清理。INSTRUMENTATION_CODE=0。
- 本轮只访问本机模拟网盘；实体手机、Android 10+ 分区存储分支及真实百度账户的目录创建/下载未实机验证。

界面证据：

- [原图下载按钮](screenshots/v0610-viewer.png)
- [新建相册](screenshots/v0610-create.png)
- [空相册和上传入口](screenshots/v0610-empty-album.png)
- [同名冲突提示](screenshots/v0610-duplicate.png)
- [HEIC 下载成功](screenshots/v0610-heic-download.png)

## 安装包

- exports/PixNexus Gallary-0.6.10.apk（real Debug，现有调试签名，可覆盖同签名版本）。
- SHA-256：c0fe788d46663026075b6f4cdf4fd3a4c4bb573d142364883fe8fe10e2a21a3c
- 字节：19204401
