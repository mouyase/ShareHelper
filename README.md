# 分享助手

一个极简 Android 分享中转工具。它不提供主界面，只出现在系统分享面板里：接收图片或视频，生成一份临时处理副本，再立刻调起系统分享面板交给其他 App。

## 它会做什么

- 支持单个或批量分享，图片/视频混合也可以。
- 处理后的文件只放在 app cache，不保存到相册或公共目录。
- 每次导出都会写入新的 `ExportID`，让临时副本和原文件产生差异。
- 图片优先写入 EXIF 元数据；必要时重新编码成 JPEG 副本。
- MP4 优先重新封装并写入 metadata track，其他视频走保守临时副本。

## 怎么用

安装后，在相册或文件管理器里选择图片/视频，点系统分享，选择「分享助手」。处理完成后，它会再次打开系统分享面板。

## 构建

```bash
./gradlew :app:assembleDebug
```

## 发布

推送 `v*` tag 会触发 GitHub Actions 构建 signed release APK。发布产物命名为 `ShareHelper-v<version>.apk`。

本地发布脚本：

```bash
scripts/publish-release.sh 0.0.1
```
