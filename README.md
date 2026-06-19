# HMTA

基于 CatShare 的互传客户端，支持与 OPPO/一加等品牌互传联盟设备进行文件传输。

## 功能
- [x] 蓝牙发现
- [x] 文件接收
- [x] 文件发送（需要 Shizuku 支持）
- [x] 双向大文件传输（5GHz Wi-Fi Direct + Wi-Fi 高性能锁）
- [x] 悬浮窗设备选择
- [x] 文本传输（复制至剪贴板）
- [ ] NFC 一碰传

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
