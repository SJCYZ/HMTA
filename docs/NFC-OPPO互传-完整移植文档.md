# HMTA · NFC OPPO 互传完整移植文档

> 版本：2026-08-14 · 状态：协议在 NFCProbe 实机验证通过（双方向、含大文件/多文件）
> 参考实现：`F:\26973\MyProjects\NFCProbe`（华为端调试 APK）
> 本文档是 HMTA 移植 NFC 一碰传的**唯一权威依据**；HMTA `docs/OPPO-ONet-Protocol.md`
> 第 10~12 节为早期探索结论，其中“华为端不可接入”的结论已过时（见文末“旧文档整理”）。

---

## 0. 结论速览

| 方向 | 触发线路 | 华为角色 | 状态 |
|---|---|---|---|
| OPPO → 华为（接收） | iOS 兼容线路（NDEF URI + BLE 0x9999 + 热点 + WS） | iOS 客户端 | NFCProbe 实机通过 |
| 华为 → OPPO（发送） | 同品牌线路（`app/ptctouch` NDEF + GATT 0x9955 写 0x9953 + P2P + 8959） | 发送方 | NFCProbe 实机通过 |

关键约束：
- **OPPO 互传开关必须开启**（系统级 NFC 一碰传开关，对应 OPPO 侧 accessory NFC share）；
- 华为端第三方应用 `WifiManager.addNetwork` 被拒，加入/创建热点需 **Shizuku 提权**；
- 华为 HCE 卡模拟走**鸿蒙 HOSP 通道**（manifest `ohos.nfc.cardemulation.action.HOST_APDU_SERVICE` + other-aid/payment-aid 元数据）；
- iOS 线路（华为→OPPO）已被实机判定**不可用**：OPPO 接收侧只注册 `app/ptctouch` MIME，
  `connect.oppo.com` URI NDEF 会触发 OPPO 的 NFC 应用选择器而非一碰传（详见第 5.4 节）。

---

## 1. 方向 A：OPPO → 华为（华为接收）

### 1.1 链路

```text
OPPO 相册选图→分享→OPPO互传（发送方）
  └ NFC 触碰：OPPO 发布 NDEF（AID D2760000850101）
华为（接收方）：
  1. 读 NDEF → 提取 code/devId
  2. BLE 连 OPPO 0x9999（iOS 特征）→ MTU=512
  3. 读 0x9897 band（须 OPPO 上滑后）→ state=0
  4. 写 init（明文 JSON，version=10302）→ 收 {"account_id"} → 回写
  5. 收 {"wlan",...} → 写确认 → OPPO 建热点
  6. 收 {"ssid","psk","ip","port"}（AES/CBC）
  7. Shizuku 加入 OPPO 热点 → 回写 {"ip","port"}（触发 OPPO 启动 HTTP 服务器）
  8. WS 连 ip:port/websocket → versionNegotiation → sendRequest → ack
  9. GET /download?taskId=<id>（chunked ZIP）→ 流式解压落盘
  10. 回 status(type=1) → 收 ack → 关闭 WS（OPPO 收起热点）
```

### 1.2 NDEF

```text
AID: D2760000850101
记录: tnf=1 type='U'
payload: connect.oppo.com/oshare/clips/seo?version=1&devId=<id>&code=<16hex>&devType=3&devName=<名>
```

华为提取 `code`（BLE init 的 `rdcode` 必须回传，OPPO 校验随机码表）与 `devId`。

### 1.3 BLE GATT 0x9999

| 特征 | 方向 | 用途 |
|---|---|---|
| 0x9897 | 读 | band：`{"state","key"(OPPO EC公钥),"version":"161010","pv":6}`；state=0 才继续 |
| 0x9896 | 写 | init / account / wlan / ip-port（串行写队列，等上次写回调） |
| 0x9898 | 通知 | 服务器→客户端消息 |

### 1.4 加密（与方向 B 的 0x9953 不同）

```text
密钥: ECDH(华为 EC 私钥, OPPO 公钥) → TlsPremasterSecret
AES 密钥: Base64(共享密钥) 前 16 字符（不足补 '0'）
算法: AES/CBC/PKCS5Padding，IV="0102030405060708"（ASCII）
```

### 1.5 关键时序与报文

```json
// init（明文，version>=10302）
{"key":"<华为EC公钥b64>","isFast":false,"version":"10302","pv":6,
 "type":"file/*","number":1,"dname":"HUAWEI Mate","rdcode":"<NDEF code>"}

// 收热点信息后回写（关键！否则 OPPO 不启动 HTTP 服务器）
{"ip":"<华为热点IP>","port":8959}
```

### 1.6 WS 协商与下载

```text
信封: <action>:<type>:<name>?<json>   action∈{action,ack}
服务器→客户端: action:0:versionNegotiation?{"versions":[1]}
客户端→服务器: ack:0:versionNegotiation?{"version":1}
服务器→客户端: action:0:sendRequest?{"id","senderId","senderName","fileName",
              "mimeType","fileCount","totalSize","thumbnail_height","thumbnail_width"}
客户端→服务器: ack:0:sendRequest
客户端: GET http://ip:port/download?taskId=<id>（明文；服务器按对端版本决定 TLS，
        版本>=10015 明文）→ 响应 Transfer-Encoding: chunked + ZIP
客户端: action:0:status?{"taskId":"<id>","type":1}
服务器→客户端: ack:0:status?{"type":1} → 客户端关闭 WS（OPPO 收起热点）
```

> 实测教训：下载期间 OPPO 不在 WS 上发消息，客户端 WS 读超时需“继续等待”而非断连；
> 大文件必须流式（chunked → ZipInputStream 逐文件写盘），禁止整读内存。

---

## 2. 方向 B：华为 → OPPO（华为发送）

### 2.1 链路

```text
华为选文件 → 启动 8959 发送服务（真实文件）→ 建 WiFi Direct 组（GO）
  → 触碰 OPPO（华为 HCE 呈现 app/ptctouch NDEF）
OPPO（无操作自动）：读到 NDEF → DIRECT_DISCOVERED → 解析 NfcPublishData → 弹接收确认
  → 用户确认 → OPPO 0x9955 GATT 服务 + BLE 广播就绪
华为：扫描 OPPO → GATT 连 0x9955 → 读 0x9954 → 写 0x9953（热点凭据 ka.c.i）
OPPO：加入华为 P2P 组 → 连华为 8959（wss）→ versionNegotiation → sendRequest → ack
  → GET /download（chunked ZIP）→ 解压落盘 → status(type=1)
华为：收到 status → 收起热点
```

### 2.2 华为 NDEF（同品牌触发，OppoNdefHceService）

```text
AID: D2760000850101
记录: MIME "app/ptctouch"
payload: [header 0x03] + [12字节IV] + AES-GCM-128 密文(NfcPublishData protobuf)
key: D78FE3197EA65E04635262D97A7BBE9DB59D1F660D0F42D3（固定，同 OPPO）
```

NfcPublishData protobuf 字段（fd/d0.proto）：

| 字段 | 值 |
|---|---|
| 1 deviceType | 8（PAD，OPPO DIRECT_DISCOVERED 同品牌路径要求） |
| 2 connectType | 32 |
| 3 btMacAddress | 华为真实蓝牙 MAC（Shizuku 读取） |
| 4/5 btEnabled/wifiEnabled | true |
| 9 version | 1 |
| 17 topActivityPackageName | 发送应用包名 |
| 18 peerPTCVersion | "16.35.0" |

### 2.3 GATT 0x9955 写 0x9953（ka.c.i）

```json
{"id":"<本机MAC>","mac":"<AES/CTR密文>","freq":5220,"port":8959,
 "ssid":"<AES/CTR密文>","psk":"<AES/CTR密文>","key":"<本机EC公钥b64>"}
```

加密：ECDH(本机私钥, OPPO 0x9954 返回公钥) → TlsPremasterSecret → AES/CTR，
IV=0102030405060708（与方向 A 的 AES/CBC 不同）。

### 2.4 8959 发送服务

- TLS(wss) 自签名证书（assets/wss.p12）；OPPO 客户端信任任意证书；
- WS 升级 → versionNegotiation → sendRequest（真实文件列表，fileCount/totalSize）→
  ack → GET /download → chunked ZIP 流式返回（多 entry，level 0 不压缩）；
- 收 status(type=1) → onComplete → 收起热点；
- **WS 读超时需“继续等待”**（下载期间 OPPO 无 WS 消息），帧上限放宽（心跳）；
- 大文件/多文件：全部流式（URI 逐个 entry 写 zip），禁止整读内存；
- zip entry 名去重（同名加 (1)(2)）。

---

## 3. 华为端基础设施

### 3.1 Shizuku（必须）

- 激活：`moe.shizuku.privileged.api`（无线调试/adb），授权 shell uid=2000；
- 用途：`cmd wifi connect-network "<ssid>" wpa2 "<psk>" -m -h` 加入 OPPO 热点
  （EMUI 拒绝第三方 addNetwork，返回 -1；`-h` 使命令立即返回，否则等扫描阻塞）；
- 读取真实蓝牙/p2p MAC（sysfs 被随机化遮蔽，需提权）；
- 实现：`ShizukuMacHelper` + `ShizukuMacService`（UserService + IUserService.aidl）。

### 3.2 HCE 路由（鸿蒙 HOSP 通道）

Manifest 中 HCE 服务同时声明：

```xml
<intent-filter><action android:name="ohos.nfc.cardemulation.action.HOST_APDU_SERVICE"/></intent-filter>
<meta-data android:name="other-aid" android:value="..."/>
<meta-data android:name="payment-aid" android:value="..."/>
```

- 方向 A 华为是**读卡方**（前台 NFC 分发），不需 HCE；
- 方向 B 华为是**卡片方**，需 NDEF AID `D2760000850101` 进路由表；
  **不能登记为 payment 类别**（实测会触发系统卡模拟选择器反复弹窗），只保留 other；
- 触碰时华为自身读卡也会命中对端卡片 → 前台 NFC 分发必须**始终启用**
  （`onResume` 注册、`onPause` 注销），否则系统弹“打开方式”选择器。

### 3.3 权限

`NFC`、`INTERNET`、蓝牙全套、`ACCESS_WIFI_STATE`、`ACCESS_NETWORK_STATE`、
`CHANGE_WIFI_STATE`、定位、`NEARBY_WIFI_DEVICES`、Shizuku provider 声明。

---

## 4. NFCProbe 参考实现（移植对照）

| 文件 | 职责 |
|---|---|
| `IosGattClient.kt` | 方向 A BLE 客户端：0x9999、band/init/account/wlan/ip-port、串行写队列、ECDH+AES/CBC |
| `OshareWsClient.kt` | 方向 A WS 客户端：明文优先+TLS 回退、信封解析、流式 /download、进度、取消、status、关 WS |
| `OppoNdefHceService.kt` | 方向 B NDEF 卡模拟：app/ptctouch 构造 + AES-GCM + SELECT/READ BINARY |
| `OshareGattClient.kt` | 方向 B GATT 客户端：0x9954 读 + 0x9953 写（ka.c.i） |
| `P2pProbeServer.kt` | 方向 B 8959 服务：WS + sendRequest + chunked 多文件流式 + status |
| `NfcProbeActivity.kt` | 主流程：NDEF 解析、触碰门槛、扫描、建组、进度、取消 |
| `ShizukuMacHelper/Service` | Shizuku 提权执行命令 + 真实 MAC |

### 4.1 关键实现细节

- **0x9896/0x9953 写入必须串行**（等上次 onCharacteristicWrite 回调）；
- **读 band 时机**：OPPO 上滑后（否则 state=1 且 OPPO 自杀）；
- **触碰门槛（方向 B）**：每次发送需一次新的 NDEF HCE SELECT（计数对比），
  避免 OPPO 上一轮接收状态残留导致免触碰直发；
- **多实例防抖**：握手代际（generation）让旧回调失效；相同 NDEF code 防抖；
- **进度与取消**：下载/发送线程流式+每 1MB 上报；`cancel()` 关闭 socket 打断阻塞读；
- **日志**：页面只显示步骤/结果/进度，完整日志存档到
  `/sdcard/Android/data/<pkg>/files/logs/`。

---

## 5. 已实测的坑（必须遵守）

| 现象 | 根因 | 处理 |
|---|---|---|
| 方向 A 弹“打开方式” | 前台 NFC 分发未启用，标签走系统分发 | onResume 始终启用前台分发 |
| 方向 B 反复弹选择器 | NDEF AID 误登记 payment 类别 → HCE 选择器循环 | NDEF 只留 other |
| 方向 B 免触碰直发 | OPPO 上轮接收状态残留 | 触碰门槛（NDEF SELECT 计数） |
| 大文件 OOM / 空文件 | 下载/发送整读内存；chunked 先攒后发 | 全链路流式；chunked 逐帧写 |
| 传输中“连接中断” | WS 读超时 15s 误断；心跳帧上限低 | WS 超时继续等待；帧上限 10000 |
| 下载后无 status | WS 被超时掐断，OPPO 收不到完成 | WS 存活到 status 发送后 |
| 热点不自动关 | 接收方未关 WS | 收 status ack 后主动关 WS |
| 方向 B iOS 线路无效 | OPPO 接收侧只认 app/ptctouch；URI NDEF 走普通标签分发 | 用同品牌 app/ptctouch |

---

## 6. HMTA 移植清单

1. **NFC 拦截**：前台分发 + `NDEF_DISCOVERED`（connect.oppo.com）解析 code/devId；
2. **方向 A BLE 客户端**：0x9999 协商（IosGattClient 逻辑）+ 串行写队列 + ECDH/AES-CBC；
3. **方向 A WS/下载**：明文优先、流式 chunked 解压落盘（MediaStore）、进度/取消、
   status、关 WS；
4. **Shizuku**：激活 + UserService + `cmd wifi connect-network -h` + 真实 MAC；
5. **HCE（方向 B）**：NDEF AID 仅 other 类别，app/ptctouch NDEF + AES-GCM，
   SELECT/READ BINARY 分页；
6. **方向 B GATT 客户端**：0x9954 → 0x9953（ka.c.i）；P2P 建组；
7. **8959 发送服务**：TLS + WS + sendRequest（多文件）+ chunked 流式 zip + status；
8. **触碰门槛 + 前台分发**：避免免触碰直发与选择器弹窗；
9. **权限与 Manifest**：见第 3.3 节；鸿蒙 HOSP 元数据；
10. **超大/多文件**：URI 流式、空间预检、进度上报、entry 去重。

---

## 7. 旧文档整理（防误导）

HMTA `docs/` 现有文档与本文档的关系：

| 文档 | 状态 | 说明 |
|---|---|---|
| 本文档 | **现行权威** | NFC 一碰传双方向完整实现依据 |
| `OPPO-ONet-Protocol.md` | 部分过时 | 第 1~6 节 ONet/P2P 帧格式仍有效（HMTA 现有互传联盟链路）；
  第 10~12 节 NFC 一碰传为早期探索，其中 **“OPPO NFC 自动流程依赖潘塔纳尔、
  华为端不可接入”（12.10）结论已过时**——现已有可行的 iOS 兼容线路（方向 A）
  与 app/ptctouch 同品牌线路（方向 B），以本文档为准 |
| `OPPO发送到华为-接收链路实现.md` | 仍有效（方向 A 专用） | 仅描述 OPPO→华为接收链路，
  细节与本文档第 1 节一致；双向实现统一以本文档为准 |
| `优化计划.md` | 仍有效 | HMTA 现有互传联盟（非 NFC）链路的优化记录，与 NFC 一碰传无关 |

> 迁移动作：新代码对接 NFC 一碰传时，请以本文档为准；不要在旧文档基础上继续推导
> NFC 接入方案。
