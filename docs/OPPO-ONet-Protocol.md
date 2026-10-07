# OPPO 互传 ONet/P2P 传输协议（逆向还原）

> **文档状态（2026-08-14 整理，防误导）**：
> - 第 1~6 节（ONet/P2P 帧格式、端口 8959/8960、命令/JSON）仍有效，对应 HMTA
>   现有互传联盟（非 NFC）链路的传输层。
> - 第 10~12 节（NFC 一碰传深挖）为**早期探索结论，已过时**：
>   其中 12.10「OPPO NFC 自动流程依赖潘塔纳尔、华为端不可接入」已被推翻——
>   现已有实机验证的可行线路（OPPO→华为 iOS 兼容线路；华为→OPPO app/ptctouch
>   同品牌线路）。NFC 一碰传请以
>   [NFC-OPPO互传-完整移植文档.md](NFC-OPPO互传-完整移植文档.md) 为准。

> 来源：OPPO 真机 `com.coloros.oshare` 16.10.10（OShare.apk，ColorOS 15/Android 16）反编译。
> 用途：为 HMTA 兼容 OPPO 互传的 P2P 传输层提供报文格式与端口约定。

> 更新：NFC 一碰传实现已深挖（NfcNci/TapToShareEvent + com.heytap.accessory/interconnect.nfc + pantaconnect），见第 10 节。

## 1. 总体架构

OPPO 一碰传的完整链路：

```text
NFC 触碰（系统私有，com.oplus.oshare.FILE_TRANSFER_SERVICE + OSHARE_SWITCH 权限）
        │ 携带 deviceId（对端 BLE MAC）
        ▼
OShare NfcTransferManager（com.oplus.oshare.nfc.d）
        │ WifiP2pManager 建 WiFi Direct（GO/GC）
        ▼
P2P TCP 通道（Apache MINA，端口 8959/8960）
        │ 命令帧(4096) / 文件帧(8192) / 完成帧(12288) / 心跳帧(16384)
        ▼
文件写入 Download/ColorOS/Oshare（接收方）
```

接收方（P2pServer，`s8.g`）监听 TCP 端口；发送方（P2pClient，`s8.b`）主动连接。

## 2. 端口约定

| 项 | 值 |
|---|---|
| 主监听端口 | **8959** |
| 备选监听端口 | **8960**（8959 被占用时） |
| 客户端连接规则 | 协商端口 ≤ 5 时用协商端口，否则连 **8959** |
| 端口下发 | 服务端通过 `type 6` 命令发送 `{"type":6,"port":<port>,"ip":"<ip>"}` |
| 会话 ID（channelId） | 默认 **1001** |

## 3. 帧格式（统一 16 字节头）

所有帧前 16 字节固定：

```text
[4B] channelId     （默认 1001）
[4B] dataType      （4096=命令, 8192=文件, 12288=文件完成, 16384=心跳）
[8B] payloadLength
```

### 3.1 命令帧（dataType=4096）

```text
[16B] 头
[4B]  commandType      命令类型（见第 4 节）
[4B]  strLen          UTF-8 字符串长度
[str] 字符串体
[4B]  flag            标志位
```

payloadLength = 命令部分长度（不含 16B 头）。字符串体为 JSON 或 `&&_&&` 分隔数组。

### 3.2 文件帧（dataType=8192）

```text
[16B] 头               其中 payloadLength = 元数据区总长
[8B]  metaLength       元数据长度（不含 16B 头与自身 8B）
[4B]  nameLen + [name] 文件名（UTF-8）
[4B]  fileType         文件类型
[8B]  fileSize         文件字节数
[4B]  flags
[fileSize] 原始文件内容
```

说明：
- 文件内容**不加任何编码/分块**，头后紧跟原始字节。
- 文件名若 flags 同时含 `0x02` 与 `0x04`，接收端会在文件名前拼接接收目录前缀。
- 文件写完后，发送方再发一帧 `dataType=12288`（负载为空）表示该文件完成。

### 3.3 心跳帧（dataType=16384）

```text
[16B] 头   payloadLength = 4
[4B]  值   1=心跳请求(request)，2=心跳应答(response)
```

间隔 10s，超时 30s。

## 4. 命令类型表

命令帧 commandType：

| type | 内容 | 说明 |
|---|---|---|
| 1 | JSON 设备信息 | 握手，见 §5.1 |
| 5 | `&&_&&` 分隔数组 | 传输元信息：`taskId &&_&& file/*|http/*|text/plain &&_&& 保存路径/备注` |
| 6 | JSON ACK | `{"type":6,"port":<port>,"ip":"<ip>"}`，服务端告知监听端口 |
| 8 | JSON 空消息 | `{"type":8}` |
| 9 | JSON 数据 | `{"type":9,"data":"{...}"}`，内容如 `{"p2p_reused":true,"is_p2pserver":true}` |
| 10 | `&&_&&` 分隔数组 | 文本内容：`textContent` |

## 5. JSON 消息结构（ka.c 构造器还原）

### 5.1 type 1 设备信息

```json
{
  "type": 1,
  "device_id": "<BLE MAC / 设备ID>",
  "version": 17,
  "display_name": "<显示名>",
  "device_name": "<设备名>",
  "account_id": "<账号ID>",
  "key": "<配对密钥>",
  "flag": 0,
  "is_support_5g": true,
  "vender": 0
}
```

### 5.2 type 6 ACK

```json
{ "type": 6, "port": 8959, "ip": "192.168.49.1" }
```

### 5.3 接收方状态（v 系列，pv=6）

```json
{ "state": 0, "key": "<密钥>", "version": "17", "pv": 6 }
{ "state": 0, "key": "<密钥>", "version": "17", "timeout": 300, "pv": 6 }
```

### 5.4 WiFi 凭据（iOS 场景）

```json
{ "ssid": "<ssid>", "psk": "<psk>", "ip": "<ip>", "port": "<port>", "isFast": 0 }
{ "ssid": "<ssid>", "psk": "<psk>", "ip": "<ip>", "port": "<port>", "key": "<key>", "isFast": 0 }
```

## 6. 传输时序（推测，符合代码调用顺序）

```text
发送方(P2pClient)                    接收方(P2pServer)
      │  P2P 建立后连接 ip:8959            │ 监听 8959/8960
      │──────────────────────────────────▶│
      │  type 1 设备信息（握手）            │ 解析对端设备，记录连接
      │◀──────────────────────────────────│
      │  type 6 {port, ip}                │
      │──────────────────────────────────▶│  type 5 {taskId, "file/*", 保存路径}
      │  type 5 传输元信息                  │
      │──────────────────────────────────▶│
      │  文件帧 8192（头+原始内容）          │ 按 name/fileSize 落盘
      │  完成帧 12288                      │
      │  心跳 16384（10s 间隔）             │
```

## 7. 接收目录

默认保存路径：`Download/ColorOS/Oshare`（对端 type 5 中下发）。
重名文件自动追加时间戳 `_yyyyMMddHHmmssS`。

## 8. 对接 HMTA 的要点与风险

可复用部分（协议层）：
- HMTA 接收方可直接实现 P2pServer：监听 8959，解析命令帧/文件帧/心跳。
- HMTA 发送方可实现 P2pClient：连对端 8959，先发 type 1 握手再发 type 5 + 文件帧。
- 文件内容原样传输，无加密/压缩，实现成本低。

尚未还原/存在风险的部分：
1. **发现与配对**：OPPO 端设备列表依赖 BLE 广播 + HeyTap Accessory 框架（`com.heytap.accessory`，服务端系统应用）。HMTA 需模拟 OShare 的 BLE 广播特征（`ADV_SERVICE_UUID 00003331-...` 等）才可能被 OPPO 端发现为可接收设备——这需要另行逆向 `com.heytap.accessory` 系统服务。
2. **NFC 触发**：`com.oplus.oshare.FILE_TRANSFER_SERVICE` 广播受 `OSHARE_SWITCH` 权限保护，HMTA 无法直接接入 OPPO 的 NFC 触发链；只能依赖分享面板手动选设备。
3. **签名/校验**：type 1 中 `key`、`account_id`、`flag` 存在配对校验逻辑（代码中 `d0.b()`、`AccountManger`），跨厂商是否强制校验需真机抓包确认。
4. **版本兼容**：`version: 17` 为当前 OShare 版本号，协议字段随版本可能变化。

## 9. 逆向产物

- `OShare.apk`（25.5MB，`com.coloros.oshare` 16.10.10）
- `OplusNearComm.apk`（HeyTap Accessory SDK 宿主）
- `BeaconLink.apk`
- `NfcNci.apk`（系统 NFC 服务，含 TapToShareEvent）
- `HeytapAccessory.apk`（63MB，系统互连服务，含 NFC 协议栈）
- 反编译源码：`OShare-jadx/`、`NearComm-jadx/`

关键类：
- `com.oplus.oshare.nfc.d`：NfcTransferManager（P2P 建链、type 1/6/8/9 收发）
- `s8.b`：P2pClient；`s8.g`：P2pServer
- `ab.j`：FileClient（MINA 客户端）；`ab.p`：FileServer（MINA 服务端）
- `ab.e/ab.f`：帧解码/编码；`ab.i`：文件头编码
- `ka.c`：JSON 消息构造器；`db.c/d`：命令/文件消息封装
- `com.heytap.accessory.discovery.OOBKManager`：NFC 带外密钥协商

## 10. NFC 一碰传实现（深挖还原）

### 10.1 总体架构

```text
两台 OPPO/一加/realme 手机 NFC 区域靠近
        │
        ▼
NFC RF 层（系统 NfcNci 的 TapToShareEvent）
   ├─ Type A（ISO-DEP / HCE）：SELECT AID + GET DATA，双向交换 payload（≤200B）
   └─ FeliCa（NFC-F）：System Code FEFE，NFCID2=OPPO.COM
        │ payload 经 content://com.heytap.accessory.nfc.NfcContentProvider
        ▼
互连协议层（com.heytap.accessory → com.oplus.interconnect.nfc）
   NfcInit(7) → Oobkey/Ksc/UKey2 密钥协商 → NfcDataSync → Receiver/SenderConfirm(112)
        │ 配对成功
        ▼
NfcOShareP2pActor 拉起 OShare（FILE_TRANSFER_SERVICE，携带对端 BLE MAC）
        ▼
WiFi Direct + TCP 8959/8960 传输（见第 2~6 节）
```

### 10.2 关键常量（TapToShareEvent）

| 常量 | 值 | 含义 |
|---|---|---|
| TAP_SHARE_AID | `F000004F50504F2E434F4D` | HCE AID = `F00000` + `OPPO.COM`（11 字节） |
| SELECT AID 命令 | `00A404000BF000004F50504F2E434F4D` | CLA=00 INS=A4 P1=04 P2=00 Lc=0B |
| GET DATA 命令 | `00CA0101` | 其后跟 `len + payload` |
| 成功 SW | `9000` | 安全模式（protocol=0） |
| 非安全 SW | `494E5345435552459000` | `INSECURE`+9000（protocol=1） |
| 失败 SW | `FF9000` | |
| FeliCa System Code | `FEFE` | |
| FeliCa NFCID2 | `4F50504F2E434F4D` | `OPPO.COM` |
| FeliCa PMM | `FFFFFFFFFFFFFFFF` | |
| payload 长度 | 6 ~ 200 字节 | |
| NDEF URI 标识 | `https://connect.oppo.com/oshare/clips` | iOS 碰一碰识别用 |
| 场景枚举 | SYSTEM_SHARE=0 / GALLERY_SELECT=1 / GALLERY_SHARE=2 / OSHARE_SHARE=3 | |

### 10.3 Type A（HCE）握手

读取方（Reader）流程（`TapToShareEvent.tapToShareReaderBasedOnTypeA`）：

```text
1. isoDep.connect()
2. transceive(00A404000BF000004F50504F2E434F4D)   -- SELECT AID（F000004F50504F2E434F4D）
   响应必须为 9000（安全）或 494E5345435552459000（非安全）
3. 从本地互连服务取自己的 payload（≤200B）
4. transceive(00CA0101 <len> <payload>)           -- GET DATA，把自己的 payload 发给对端
5. 响应 = 对端 payload + 9000，去掉末尾 2 字节 SW 后交给互连服务解析
```

卡模拟方（HCE，`OplusHostApduService.processCommandApdu`）：

```text
- 收到 00A40400 0BF000004F50504F2E434F4D → 返回 9000（AID=F000004F50504F2E434F4D）
- 收到 00CA0101 <len> <changedData> → 把 changedData 交给协议层
    生成自己的 payload，返回 payload + 9000
- 其他命令 → FF9000
```

一次触碰即双向交换 payload（读取方先发，卡模拟方回）。

### 10.4 FeliCa 模式

发送方模拟 FeliCa Type 3 Tag（SYSCODE `FEFE`、NFCID2 `OPPO.COM`）：

- Check 命令（`06`）：响应 `1D074F50504F2E434F4D00000101 <payloadLen> 9000...`
- Update 命令（`08`）：响应 `0C094F50504F2E434F4D0000`，payload 交给互连服务

### 10.5 互连协议层（interconnect.nfc.pair）

消息类型（`NfcPairMessageType`）：

| 消息 | 阶段 |
|---|---|
| NfcInit（7） | 配对初始化 |
| Oobkey / Ksc / UKey2 | 带外密钥协商（KSC 与 UKey2 均实现） |
| NfcDataSync | 数据同步（交换设备信息/分享数据） |
| ReceiverConfirm / SenderConfirm（112） | 收发双方确认 |

配对成功后 `NfcOShareP2pActor` 拉起 OShare 的 P2P 传输。

### 10.6 payload 生成入口

- Provider：`content://com.heytap.accessory.nfc.NfcContentProvider`
- 调用权限：`com.oplus.permission.safe.CONNECTIVITY`（仅系统/签名应用）
- 方法：`notifyAndGetApduData`（sdk_version=1 走 Parcelize，=2 走 byte[]）
- 服务名：`tap_share`；实现：`OplusNfcProtocolImpl.exchangeApduPayload`
- 手机用 `OplusNfcProtocolImpl`，平板/键盘用 `OplusKeyboardNfcProtocol`

### 10.7 HMTA 接入建议（华为端）

可实现的读取方向：

```text
华为端 NFC Reader Mode + IsoDep
  1. SELECT AID F000004F50504F2E434F4D
  2. GET DATA 00CA0101 <len> <HMTA payload>
  3. 收到 OPPO 端 payload + 9000
```

要点与风险：
1. HMTA 需要系统 NFC 读取权限（NFC 权限为 normal 权限，普通应用可用）。
2. 对端 payload 是否明文取决于密钥协商：OPPO 一碰传走 KSC/UKey2 带外密钥协商，NfcDataSync 内容大概率加密。HMTA 若只做 RF 层握手，可拿到 payload 原始字节，但解析需复刻协商协议（复杂度高，且属于 HeyTap 私有协议）。
3. 轻量方案：HMTA 先实现"读取方"（SELECT AID + GET DATA 交换），把 OPPO 端返回的 payload 字节记录/上报，真机验证后再决定是否复刻密钥协商。
4. OPPO 端只会在"一碰互联/分享页"开启 NFC 分享模式时响应 HCE，需用户在 OPPO 端处于分享状态。
5. 实测确认：OPPO 端 `com.heytap.accessory` 的 `OplusHostApduService` 已注册 AID `F000004F50504F2E434F4D`（Category other，enabled）；华为端第三方 HCE 被系统过滤（Registered HCE services 仅华为钱包，华为官方问答确认"智闪卡优先、第三方 HCE 收不到 APDU"），因此华为端只能做读取方，无法做卡模拟。

## 11. 实机验证结果（华为读 OPPO，2026-08-10）

### 11.1 Type A（ISO-DEP）握手成功

华为端 NFCProbe（Reader Mode）触碰 OPPO（一碰互联开启）：

```text
标签: ID 21BE7A53, Tech: IsoDep/NfcA/MifareClassic, SAK=0x28
>> SELECT AID: 00A404000BF000004F50504F2E434F4D
<< 响应: 9000                                  ← 安全模式(protocol=0)，命中 OPPO 互传 HCE
>> GET DATA: 00CA0101 08 A1A2A3A4A5A6A7A8      ← 本机测试 payload 8 字节
<< 响应: <137 字节 payload> + 9000
```

OPPO 端返回 137 字节 payload（hex 前缀）：

```text
03DAEC8E77FB8290B412F58EC157521A8DE5649E602977D764FC91931798F7B9
2F41A40E2C444966FCF7C775927D24BA73ABC2CA8E77A004975D29E8BD28D7C8
80F8FA0F40D966506FB82D974631035A72FA2FCD9C0C56B11AE217777B8E878EE
AB8CB8AD106B3BD3A8902EB521ABEE2D79E1F870812B75EE806D7F2579D9A126
10EA92077C6A02624C111F5C5389F3C258B930D0E706D6F3A280A379ECC5AC5F
B6E232DF01E302F
```

该 payload 长度 137 字节，无明文特征（无 JSON/ASCII），推测为 KSC/UKey2 带外密钥协商后的加密数据（NfcDataSync 阶段）。

### 11.2 FeliCa 卡模拟命中

第二次触碰命中 OPPO 的 FeliCa 卡模拟：

```text
标签 ID: 4F50504F2E434F4D（即 "OPPO.COM" hex）
Tech: NfcF
SYSCODE: FEFE
```

### 11.3 OPPO 弹门禁卡的性质

OPPO 端日志显示 `RealTimeSwitchCardManager` 检测到射频场后自动切换到门禁卡（AID `A0000003964D344D1061707056434D02`），弹出钱包刷卡界面（`com.finshell.wallet` 的 `SwipeNfcCardWidgetProvider`）。

**结论：门禁卡是 OPPO 端"智能选卡"的 UI 干扰，与 APDU 数据交换并行发生，不影响握手与 payload 交换。**

### 11.4 HMTA 接入结论

- 华为端第三方 HCE（卡模拟）被系统过滤 → HMTA 不能模拟 OPPO.COM 卡。
- 华为端 Reader Mode 读 OPPO → APDU 握手成功、可拿到 OPPO payload → **这是唯一可行方向**。
- HMTA 接入流程：Reader Mode 触发 → SELECT OPPO.COM AID → GET DATA 交换本机 payload → 解析 OPPO payload（需复刻 KSC/UKey2 密钥协商或先做字节级验证）→ 触发 P2P 传输。

## 12. NFC payload 结构与加密方案（已破解）

### 12.1 总结构

```text
payload = [1B header][serviceData]
         └─ 0x03 = DeviceType.PHONE（OPPO 手机）
```

实际抓包（137 字节）：

```text
03 | DA EC 8E 77 FB 82 90 B4 12 F5 8E C1 | 57 52 1A 8D E5 64 9E 60 29 77 D7 64 ...
└┬┘ └────────── 12B IV ────────────────┘ └────────── AES-GCM 密文（124B）──────────┘
header=3（PHONE）
```

serviceData = `[12B IV][AES/GCM/NoPadding 密文]`（密文含 16B GCM tag，明文约 108 字节）。

### 12.2 明文内容：protobuf NfcPublishInfo（ai.c）

解密后为 Google protobuf，字段号与含义：

| 字段号 | 名称 | 类型 | 说明 |
|---|---|---|---|
| 1 | deviceType | int | 设备类型 |
| 2 | connectType | int | 连接类型 |
| 3 | btMacAddress | string | 蓝牙 MAC |
| 4 | btEnabled | bool | 蓝牙开关 |
| 5 | wifiEnabled | bool | WiFi 开关 |
| 6 | nfcEvent | message | NFC 事件 |
| 7 | d2dEnabled | bool | 设备直连开关 |
| 8 | nfcExcludeEvent | bool | |
| 9 | version | int | 协议版本 |
| 10 | serviceParams | message | 服务参数 |
| 11 | networkName | string | **WiFi SSID** |
| 12 | networkPwd | string | **WiFi 密码（PSK）** |
| 13 | networkFreq | int | WiFi 频段 |
| 14 | capabilityFlags | int | 能力标志 |
| 15 | wlanMode | int | WLAN 模式 |
| 16 | deviceMode | int | 设备模式 |
| 17 | topActivityPackageName | string | 前台应用包名 |
| 18 | peerPtcVersion | int | 对端 PTC 版本 |

即：**NFC 一碰传交换的是 WiFi Direct 连接凭据（SSID/PSK/频段）+ 设备信息**，与 HMTA 通过 BLE GATT 交换 P2pInfo 完全同构。

### 12.3 加密实现（nf/e + nf/b + nf/d）

- 算法：`AES/GCM/NoPadding`，GCM tag 128 位
- 密文格式：`[12B 随机 IV][密文]`
- 密钥来源：OPPO 硬件加密引擎 `CryptoEngManager.cryptoEngCommand(...)`（命令基于固定字符串 `"com.heytap.accessory"` + 固定 hex 指令），`SecretKeyManager` 缓存
- 加密/解密器：`NfcDataEncryptorImpl` / `NfcDataDecryptorImpl`
- **调试开关**：`Settings.Secure` 的 `nfc_tag_de_state`，非 0 时关闭加密直接透传明文（OPPO 内部调试用，adb 可设，测试后已恢复）

### 12.4 角色机制（关键）

| OPPO 端状态 | NFC 角色 | 华为端可做什么 |
|---|---|---|
| 分享面板（选文件→分享→一加互传→设备选择页） | **读卡器**（TapToShareEvent Reader） | 华为需做 HCE 卡模拟 → **华为禁止第三方 HCE，不可行** |
| 一碰互联开关（设置→连接与共享→一碰互联） | **卡模拟**（OplusHostApduService，Type A ISO-DEP + FeliCa） | 华为 Reader 读卡成功（已实测：SELECT 9000 + payload） |

OPPO 端"智能选卡"（RealTimeSwitchCardManager）会在射频场激活时自动切到门禁卡（Mifare/TypeB），与互传 HCE 竞争，导致华为有时只读到门禁卡（SAK=0x08，无 IsoDep）。

### 12.5 HMTA 接入结论（最终）

1. 华为端第三方 HCE 被系统过滤（智闪卡优先，官方确认）→ **华为无法模拟 OPPO.COM 卡**，OPPO 分享面板（读卡器）场景无法对接。
2. 华为端 Reader 读 OPPO（一碰互联卡模拟状态）→ APDU 握手成功、可拿 payload，但 payload 用 OPPO 硬件密钥 AES-GCM 加密，**HMTA 无密钥无法解密**。
3. 现实结论：**HMTA 与 OPPO 互传的 NFC 一碰传互通受双端系统限制，不可行**；HMTA 维持现有蓝牙/WiFi 发现 + 设备列表传输方案即可。NFC 分析成果可用于理解协议与排查问题。

### 12.6 OPPO 设备间 payload 解密机制（补充）

**接收方（读卡器）解密流程**：

```text
收到 payload = [1B header][12B IV][AES-GCM 密文]
1. header 丢弃（0x03 = PHONE 类型标识，仅用于设备分类）
2. IV = payload[1..13]
3. 密钥 = SecretKeyManager.d()（见下）
4. Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(DECRYPT_MODE, key, GCMParameterSpec(128, IV))
5. 明文 = cipher.doFinal(payload[13..])
6. 明文为 protobuf NfcPublishInfo（WiFi SSID/PSK/频段/能力标志等）
```

**密钥来源（SecretKeyManager）**：

```text
CryptoEngManager.getInstance().cryptoEngCommand(命令)
命令 = [4B 26 大端][4B 20 反转][20B "com.heytap.accessory"][4B 命令长度 反转][固定 TEE 命令负载]
返回 = [4B resultCode][4B dataLen 大端][AES 密钥字节]
```

- 实现基于 OPPO TEE 安全世界（`com.allawn.cryptography.teesdk` / `com.oplus.hardware.cryptoeng`）
- 命令负载为**固定 hex**（无 IMEI/SN/MAC 等设备唯一输入），且 OPPO 设备间互传必须互相解密 → **所有 OPPO 设备共享同一派生 AES 密钥**（或同一算法派生结果）
- 加密器（NfcDataEncryptorImpl）与解密器（NfcDataDecryptorImpl）均使用该密钥

**对 HMTA 的意义**：

- 密钥派生在 TEE 安全世界内完成，用户空间无法直接读取；
- 若要离线复现，需逆向 TEE 命令算法（TrustZone/安全固件级）或从 OPPO 系统提取密钥（需 root/系统权限）；
- 结论不变：HMTA 无法在无 OPPO 系统能力的情况下解密真实 payload。

### 12.7 实机解密验证（root 提取密钥，2026-08-10）

在 OPPO 端（KernelSU root）用 `app_process` 调用 `CryptoEngManager.cryptoEngCommand(固定命令)` 成功提取密钥：

```text
resultCode=0  dataLen=24
AES 密钥 = D78FE3197EA65E04635262D97A7BBE9DB59D1F660D0F42D3
```

用该密钥解密实测 137 字节 payload（`03` + 12B IV + 124B 密文），AES/GCM/NoPadding 解密成功，明文 protobuf 字段：

| 字段号 | 名称 | 实测值 |
|---|---|---|
| 1 | deviceType | 8 |
| 2 | connectType | 32 |
| 3 | btMacAddress | `04:24:05:D3:43:CC` |
| 4 | btEnabled | true |
| 5 | wifiEnabled | true |
| 7 | d2dEnabled | true |
| 9 | version | 3 |
| 11 | networkName | `DIRECT-4Q-ToK` |
| 12 | networkPwd | `rLa29d4r` |
| 13 | networkFreq | 2412（2.4GHz） |
| 14 | capabilityFlags | 15 |
| 17 | topActivityPackageName | `com.coloros.gallery3d` |
| 18 | peerPtcVersion | `16.35.0` |

**结论**：

- 密钥为 OPPO 设备共享的**固定密钥**（非设备唯一），这就是设备间能互相解密的原因；
- 解密流程完全复现：`[1B header][12B IV][AES-GCM 密文]` → 明文 protobuf；
- **安全提示**：该密钥可解密任意 OPPO 一碰传 NFC payload（含 WiFi 凭据），属厂商固定密钥设计，本机提取方式可用于研究；文档中的密钥为 OPPO 系统共享密钥，注意保存范围。

### 12.8 新方向：华为端默认 NFC 状态 + OPPO 发起（2026-08-11 实测）

用户实测：**华为端不启用 Reader Mode（保持默认 NFC 状态）时，OPPO 靠近华为能稳定触发 OPPO 的互传申请，不与门禁卡冲突**。

机制（与 §12.4 角色机制吻合）：

```text
华为默认 NFC 状态（读卡 + 卡模拟并存，系统卡模拟激活）
        │ OPPO（分享面板=读卡器）靠近
        ▼
OPPO 读到华为的卡模拟 → 识别为"可互传设备" → 弹申请（上滑确认）
        │ 同时 OPPO 广播 NDEF URI：
        │   https://connect.oppo.com/oshare/clips/seo?version=1&devId=...&code=...&devType=3&devName=...
        ▼
华为默认读卡功能读到该 NDEF URI（系统提示"发现 NFC 标签/打开链接"）
        │ HMTA 前台分发 / intent-filter 接住
        ▼
HMTA 提示碰一碰 → 启动接收服务（BLE 广播 + WiFi Direct 接收）
        │ OPPO 确认后通过发现链路连接华为端 HMTA
```

NDEF URI 参数（OPPO 设备信息）：

| 参数 | 含义 | 示例 |
|---|---|---|
| version | 协议版本 | 1 |
| devId | 设备 ID（12 hex） | 41EEF98C1656 |
| code | 一次性 code（16 hex） | 5D65FC52A1614499 |
| devType | 设备类型 | 3 |
| devName | URL 编码设备名 | 霍倾城_ |

**方向评估**：

- 这是**唯一不与 OPPO 门禁卡冲突、且华为端无需 HCE/Reader 的 NFC 触发路径**；
- 华为端第三方 HCE 仍被系统过滤（§12.5），但此方向不需要华为做卡模拟——OPPO 是读卡器，华为只需默认卡模拟在场；
- 决定性验证点：OPPO 的"接收询问"能否通过现有发现链路（BLE 广播 0x3331 + GATT 0x9955 握手 + 传输）到达 HMTA。HMTA 广播已确认可正常启动（手动开启接收开关，`Advertising started OK`）；
- 待验证：① OPPO 分享面板能否发现华为（广播匹配）；② OPPO 确认后询问/传输是否走 HMTA 兼容通道。

### 12.9 静态分析确认：HMTA UUID 与 OShare 一致，但 NDEF URI ≠ WiFi 凭据（2026-08-11）

**UUID 一致性（关键）**：

OShare（c8/c.java）与 HMTA（BleUtils）的 UUID 完全一致：

| 用途 | OShare | HMTA |
|---|---|---|
| BLE 广播服务 | `00003331-...-008123456789` | 相同 |
| GATT 服务（公共兼容） | `00009955`（c8.c.I，createCommonBleService） | 相同 |
| 状态特征 | `00009954`（c8.c.J） | 相同 |
| P2P 特征 | `00009953`（c8.c.K） | 相同 |

OShare 的 GATT server（d8/v.java BleServer）：
- `E0()` createCommonBleService：服务 **0x9955** + 特征 0x9954/0x9953（**HMTA 实现的正是这套**）
- `H0()` createService：服务 **0x9999** + 0x9981/0x9995 等完整特征集
- P2P 命令构造（J0）：`MAC去冒号 + "3" + "|" + freq + "|" + ssid + "|" + psk`——经 0x9953 特征交换（即 HMTA 的 P2pInfo）

**结论**：HMTA 的 BLE 层（0x3331 广播 + 0x9955 GATT）是 **OShare 的"公共兼容服务"**——OPPO 分享面板的设备发现/手动连接走的就是这套（用户确认：分享面板出现 HMTA 是标准 BLE 线路，与 NFC 无关）。

**NDEF URI ≠ WiFi 凭据**：

- 华为默认 NFC 状态读到的 **NDEF URI**（`connect.oppo.com/oshare/clips/seo?...`）只含设备信息（devId/code/devType/devName），**不含 WiFi Direct 凭据**；
- WiFi 凭据在 **NFC payload**（APDU GET DATA 交换，加密 protobuf，§12.7 已破解密钥）中；
- 因此仅收 NDEF URI 无法"自 NFC 直接触发"——必须补上 APDU 交换拿到 payload。

**正确的"自 NFC 直接触发"线路**：

```text
华为默认 NFC 检测到 OPPO 标签 → 系统分发 → HMTA 前台分发收到 Tag（含 IsoDep）
        │ HMTA 在已分发的 Tag 上执行（无需 Reader Mode 强制轮询）：
        │   IsoDep.connect()
        │   SELECT AID F000004F50504F2E434F4D → 9000
        │   GET DATA 00CA0101 <len> <HMTA payload> → OPPO payload + 9000
        ▼
用已破解密钥解密 OPPO payload → WiFi Direct SSID/PSK/频段
        ▼
HMTA 连接 OPPO 的 WiFi Direct → 传输（与 OPPO 分享面板/BLE 发现无关）
```

待验证：前台分发收到的 Tag 是否含 IsoDep 且可执行 APDU（华为系统分发后连接是否可用）。

### 12.10 最终结论：OPPO NFC 自动流程依赖潘塔纳尔，华为端不可接入（2026-08-11 实机）

**实机验证链**：

1. NFCProbe（前台分发）能拿到 OPPO payload 并解密出 WiFi 凭据（§12.9 验证通过）；
2. 但**任何运行在前台、注册了 NFC intent-filter 的应用都会破坏 OPPO 弹申请**（华为 NFC 行为变化）；
3. 华为 NFC 完全默认时 OPPO 正常弹申请，确认后 OPPO"自动发送接收询问"——但华为 HMTA 收不到；
4. OPPO 端日志（PTC.DISC / metis）：
   - `PTC.DISC.LanAdvertiser: afterSenseless failed. Same device type connected`——潘塔纳尔 LAN 发现失败（华为非潘塔纳尔设备）；
   - `metis_v2_BleScanEngine: filter rejected`——潘塔纳尔 BLE 扫描仅接受 4 个 Proximity UUID：
     - `F7A6B2D1-8E3C-4F52-9A1D-C5E8D09F4B67`
     - `933D8535-8665-477F-9F9F-078FB2F9F4CA`
     - `FDA60693-A4E2-4FB1-AFCF-C6EB07647825`
     - `01122334-4556-6778-899A-ABBCCDDEEFF0`

**结论（最终）**：

- OPPO 的 NFC 一碰传自动流程（弹申请→确认→连接）依赖**潘塔纳尔私有发现/连接协议**（PTC.DISC + metis BLE + DCP GATT），华为端既无潘塔纳尔服务、HMTA 的 OShare 兼容广播（0x3331/0x9955）也不匹配潘塔纳尔过滤器；
- 即使 HMTA 模拟潘塔纳尔广播 UUID，后续 DCP GATT 握手与密钥认证仍是 OPPO 私有协议，无法完成；
- **华为端 HMTA 无法接入 OPPO NFC 一碰传自动流程**；HMTA 维持 OShare 标准线路（BLE 0x3331 广播 + GATT 0x9955 + WiFi Direct 传输），OPPO 分享面板手动选择华为设备完成互传；
- NFC 在华为端仅能作为"让 OPPO 感知设备在场"的系统级辅助，应用层不参与。
