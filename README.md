# RootHelperStandaloneApp

这是一个openclaw在安卓受限环境下桥接root和CDP工具

## 现在的实现
- 独立 App
- 独立前台服务 `RootHelperService`
- App 内嵌本地 HTTP 服务 `RootCommandServer`
- 监听：root`127.0.0.1:18765`CDP´127.0.0.1:1922`
- 接口：
  - `GET /ping`
  - `POST /exec`
- 通过 **libsu** 直接以 root 执行命令
- 可切换开机自启（BOOT_COMPLETED）


## 配置
文件：`app/src/main/java/com/dadatu/roothelper/RootHelperConfig.java`
- `TOKEN`
- `PORT`

## 构建
Android Studio 打开整个 `RootHelperStandaloneApp` 目录即可。
需要：
- JDK 17
- Android SDK / Gradle 环境

## 当前仍建议后续继续补的点
- 更严格的命令白名单
- token 改成首次生成并持久化
- 更完善的异常提示/日志页
- 更稳的前台服务状态展示
