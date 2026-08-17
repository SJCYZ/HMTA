# HMTA NFC 一碰传实机验收清单

> 适用范围：本轮从 NFCProbe 移植到 HMTA 的 OPPO 一碰传双向链路。
> 当前状态：Debug 构建、单元测试和 Android Lint 已通过；2026-08-17 已完成 OPPO ↔ HMTA 双向小文件核心链路实机验收。异常恢复、大文件和特殊文件名仍按下列清单继续覆盖。

## 1. 测试准备

- 一台安装 HMTA Debug 包的华为/HarmonyOS Android 兼容设备，NFC、蓝牙、Wi-Fi 均已开启。
- 一台支持“一碰传/随身工作台/跨设备互联”的 OPPO 或 OnePlus 设备。
- HMTA 已获得附近设备、蓝牙、通知等运行时权限。
- Shizuku 已启动且已授权 HMTA；首页应显示 Shizuku 可用。
- HMTA 设置中的“启用 NFC 一碰传”已打开。
- 测试前记录两台设备系统版本、机型和 OPPO 跨设备互联组件版本。

安装包：`app/build/outputs/apk/debug/app-debug.apk`

## 2. OPPO → HMTA（HMTA 接收）

1. 保持 HMTA 首页在前台，在 OPPO 端选择一个小文件并进入一碰传发送状态。
2. 两台设备 NFC 区域贴近，确认 HMTA 显示“已识别 OPPO NFC”。
3. 在 OPPO 端按提示上滑/确认发送。
4. HMTA 未开启自动接收时，确认通知中出现“接收/拒绝”；先验证一次拒绝，再重新发起并验证接收。
5. 确认状态依次进入 BLE 协商、热点连接、文件接收、完成。
6. 在系统“下载/HMTA”目录确认文件存在，文件名、大小和内容与源文件一致。
7. 依次覆盖中文名、无扩展名、空文件、图片、视频及多文件批量发送。

通过标准：触碰不弹系统应用选择器；无需手工切换 Wi-Fi；文件完整落盘；取消或失败后不保留半成品；传输后普通 Wi-Fi 可恢复使用。

## 3. HMTA → OPPO（HMTA 发送）

1. 在 HMTA 选择一个小文件，进入发送页并点击“NFC 一碰传（OPPO）”。
2. 按页面提示将华为 NFC 区域贴近 OPPO；必须在本次任务的新触碰后才开始 BLE 扫描。
3. 确认 OPPO 出现 HMTA 设备/文件接收提示，并在 OPPO 端接受。
4. 确认 HMTA 状态依次进入 NFC 等待、BLE 协商、Wi-Fi Direct 建组、文件发送、完成。
5. 在 OPPO 端核对文件名、大小与内容。
6. 依次覆盖中文名、无扩展名、空文件、图片、视频及多文件批量发送。

通过标准：OPPO 能读取 HCE NDEF；HMTA 能创建 Wi-Fi Direct 组并写入正确凭据；8959 端口完成文件传输；完成或取消后 P2P 组被移除。

## 4. 异常与恢复

- 触碰后 30 秒无后续：任务应超时并允许立即重试。
- BLE/Wi-Fi/NFC 任一项中途关闭：任务应明确失败，不应长期卡在进行中。
- 发送或接收中点击取消：连接、扫描、WebSocket、P2P 组和前台通知均应清理。
- 连续快速触碰同一邀请：3 秒内只启动一个接收会话。
- HMTA 已有普通互传任务时再次发起 NFC：应提示正忙，不得并行抢占端口或网络。
- 屏幕旋转、切后台再返回：任务应由前台服务继续，页面进度能恢复。
- 进程被系统终止后重开：不得显示幽灵任务或沿用旧 NFC 会话。

## 5. 建议留档

每个失败用例至少保存：两台设备型号与系统版本、操作方向、失败阶段、HMTA 日志、OPPO 端提示截图，以及是否能稳定复现。协议验收建议至少完成一轮小文件和一轮 1 GB 以上大文件测试。

## 6. 2026-08-17 实机验收记录

测试设备：

- 华为 LIO-AN00，Android 12 / API 31，有线 ADB；NFC、HCE、Wi-Fi Direct 和 Shizuku 可用。
- OPPO/OnePlus PLQ110，Android 16 / API 36，无线 ADB。

核心链路结果：

- OPPO → HMTA：通过。实测接收 JPEG 文件 417545 字节，OPPO 显示正常完成，HMTA 收到 `ack:0:status`，文件完整落盘并恢复原 Wi-Fi。
- HMTA → OPPO：通过。OPPO 读取 HMTA 的 HCE NDEF，确认接收后完成 BLE、Wi-Fi Direct、GATT 凭据和 8959 文件传输，用户确认传输完成。

本轮实机修复：

- 接收端下载 TCP 连接保持到状态 ACK 后再关闭，避免 OPPO 报“发送失败：连接中断”。
- 按 NFCProbe 的华为兼容策略读取真实蓝牙 MAC：优先直查 Settings Provider，并保留 sysfs、`ip addr`、蓝牙适配器和厂商属性回退。
- HMTA 的 HCE 只声明 `other-aid=D2760000850101`，不声明 `payment-aid` 或付款类别。

环境收尾：

- 为避免 NFCProbe 与 HMTA 同时声明相同 NDEF AID 造成路由竞争，已在确认本地 APK 备份后从华为设备卸载 NFCProbe。
- 本地精确备份：`F:\26973\MyProjects\NFCProbe\app\build\outputs\apk\debug\app-debug.apk`。
- 备份与卸载前设备 APK 的 SHA-256 均为 `07EBACE61FFAE5B3BF8208AAA84793EA009B4A0D0DE648F01A6E4666F5D80A6D`。
- 卸载后已清除残留 `nfc_payment_default_component`；复核付款默认项、前台 HCE 优先项均为空，HMTA 仅归入 `Category: other`。
