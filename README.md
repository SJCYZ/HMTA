# HMTA

基于 CatShare 的互传客户端，支持与 OPPO/一加等品牌互传联盟设备进行文件传输。

## 功能
- [x] 蓝牙发现
- [x] 文件接收
- [x] 文件发送（需要 Shizuku 支持）
- [x] 双向大文件传输（5GHz Wi-Fi Direct + Wi-Fi 高性能锁）
- [x] 悬浮窗设备选择
- [x] 文本传输（复制至剪贴板）
- [ ] NFC 一碰传（协议与实现已在 NFCProbe 实机验证，移植方案见
  [docs/NFC-OPPO互传-完整移植文档.md](docs/NFC-OPPO互传-完整移植文档.md)）

## 架构

HMTA 基于 CatShare 实现，采用「BLE 握手 + Wi-Fi Direct 组网 + WebSocket/HTTP 传输」的互传联盟兼容协议：

| 阶段 | 说明 |
| --- | --- |
| 发现 | BLE 广播携带设备名与 5GHz 支持标志，扫描端解析后展示设备列表 |
| 握手 | 发送方创建 Wi-Fi Direct 组（5GHz，失败自动降级 2.4GHz），通过 BLE GATT 交换组凭据（SSID/PSK/端口，ECDH 加密） |
| 传输 | 发送方起本地 HTTPS 服务（自签名证书），接收方经 WebSocket 协商后通过 HTTP 下载 Zip 流（媒体类不压缩） |
| 落盘 | 接收方将 Zip 流逐文件写入 MediaStore（Download/HMTA） |

关键目录：

```
app/src/main/java/com/sjcyz/hmta/
├── services/          # 发送/接收/BLE GATT/组网服务
│   ├── P2pSenderService.kt    # 发送端：建组、BLE 握手、HTTP 服务
│   ├── P2pReceiverService.kt  # 接收端：连组、WebSocket、下载落盘
│   ├── GattServerService.kt   # BLE 服务端：广播与 GATT 响应
│   └── BaseP2pService.kt      # Wi-Fi Direct 广播基类
├── utils/             # P2P/BLE/通知/权限工具
├── models/            # 协议消息与 Parcelable 模型
├── ShareActivity.kt   # 分享面板（悬浮窗设备选择 + 发送进度）
└── ReceiveConfirmActivity.kt  # 接收确认悬浮窗（询问 + 进度）
```

## 构建

需要 JDK 17 与 Android SDK（`local.properties` 指向 SDK 路径）。

```shell
# Debug APK（默认调试签名）
./gradlew :app:assembleDebug

# Release APK（需 signing.properties，含 R8 收缩）
./gradlew :app:assembleRelease

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug
```

CI（GitHub Actions）会在推送时自动运行单元测试、Lint 与 Debug 构建；推送 `v*` 标签时构建 Release 并创建发布草稿（需配置 `KEYSTORE` / `KEYSTORE_PROPERTIES` Secrets）。

## 支持设备（已测试）
| 品牌        | 向该设备发送 | 从该设备接收 |
| ----------- | ------------ | ------------ |
| OPPO/一加等 | Y            | Y            |
| 小米        | Y            | Y            |
| vivo        | Y            | Y            |

## 汇报问题

你可以在该项目的 issue 区汇报你在使用 HMTA 期间遇到的问题，尽量的，请附上 HMTA 的 adb logcat 日志。

通过该命令获取 HMTA 的日志。
<details>
<summary>debug(测试版)</summary>

shell(linux)
```shell
adb logcat --pid $(adb shell pidof -s com.sjcyz.hmta.debug)
```
cmd(windows)
```shell
for /f "tokens=1" %i in ('adb shell pidof -s com.sjcyz.hmta.debug') do adb logcat --pid %i
```
</details>

建议尽可能完整的截取日志，并注释从什么时候发送或接收内容，尽量使用折叠块语法来包裹日志内容。

````markdown
<details>
<summary>Details</summary>

```
在此处填入日志内容，注意其应被包裹在反括号代码块内
```

</details>
````
