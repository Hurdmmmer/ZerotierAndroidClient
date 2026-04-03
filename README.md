<h1 align="center">
  <img src="https://github.com/kaaass/ZerotierFix/blob/master/app/src/main/ic_launcher-playstore.png?raw=true" alt="Zerotier Fix" width="200">
  <br>Zerotier Fix<br>
</h1>

<h4 align="center">An enhanced unofficial ZeroTier Android client with routing-policy and observability improvements.</h4>

<p align="center">
  <img src="screenshots/main.png" alt="main" width="150"/>
  <img src="screenshots/peers.png" alt="peers" width="150"/>
  <img src="screenshots/moons.png" alt="moons" width="150"/>
</p>

<p align="center">
    <a href="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml">
        <img src="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml/badge.svg" alt="Build APP"/>
    </a>
</p>

## 项目简介

本项目是基于原始 Zerotier Android 客户端和 `kaaass/ZerotierFix` 的增强版本，重点在：

- 移动端连接稳定性
- 内网/外网场景下的自动路由策略
- 首页状态一致性与可观测性日志
- 网络切换场景下的自动恢复能力

## 核心升级：ZeroTier 内核 1.14.2

当前仓库已对齐 ZeroTier 内核 `1.14.2`，可在以下文件验证：

- `externals/core-upstream/version.h`
  - `ZEROTIER_ONE_VERSION_MAJOR 1`
  - `ZEROTIER_ONE_VERSION_MINOR 14`
  - `ZEROTIER_ONE_VERSION_REVISION 2`
- `externals/core-upstream/RELEASE-NOTES.md`（包含 `Version 1.14.2` 记录）

### 升级收益（工程视角）

- 与上游内核版本对齐，减少历史分叉带来的兼容性风险
- 为后续安全补丁和功能同步提供更清晰的基线
- Android 侧 JNI/Service 链路可持续跟进上游行为

## 功能清单

### 现有基础能力

- 自建 Moon 支持
- 自定义 Planet（文件与 URL）
- Peer 列表查看
- 中文界面支持

### 增强能力（本项目重点）

- 自动路由策略引擎（Route Policy）
  - 网络变化触发策略评估
  - 支持“监听态（Monitor-Only）”与“恢复转发”的切换
- 连接链路鲁棒性改进
  - 手动连接保护窗口，避免刚连接就被自动策略回切
  - 启停流程去竞态，避免 `node == null` 时 join 失败
- 首页状态一致性改进
  - 内核状态、开关意图、内网提示联动刷新
  - 网络切换后自动恢复时首页卡片即时更新
- 诊断可观测性增强
  - 策略判定日志
  - 路由下发决策日志
  - TunTap 转发采样日志（目标、路由命中、网关改写、发送结果）

## 架构说明

```text
+=============================================================================================================+
|                                          ZerotierFix Architecture                                          |
+=============================================================================================================+
|  [UI Layer]                                                                                                 |
|   +-----------------------------------------------------------------------------------------------+         |
|   | User/UI -> NetworkListFragment / NetworkDetailFragment / PrefsFragment                       |         |
|   | Action: switch on/off, route settings, dns settings                                           |         |
|   +-----------------------------------------------------------------------------------------------+         |
|                                         |                                                               |
|                                         | LiveData + EventBus (连接意图、状态回调)                        |
|                                         v                                                               |
|  [ViewModel Layer]                                                                                          |
|   +-----------------------------------------------------------------------------------------------+         |
|   | NetworkListModel: current connected network id                                                   |      |
|   | NetworkDetailModel: routeViaZeroTier / dnsMode                                                   |      |
|   +-----------------------------------------------------------------------------------------------+         |
|                                         | bind/start/stop                                                |
|                                         v                                                               |
|  [Service Orchestration Layer]                                                                             |
|   +-----------------------------------------------------------------------------------------------+         |
|   | ZeroTierOneService                                                                              |      |
|   |  - startOrResumeZeroTierAndJoin()                                                               |      |
|   |  - updateTunnelConfig()                                                                          |      |
|   |  - stopZeroTierRuntime(keepServiceAlive)                                                         |      |
|   |                                                                                                   |     |
|   |   +----------------------+     +-------------------------+     +------------------------------+ |     |
|   |   | RoutePolicyEngine    |<----| NetworkChangeObserver   |---->| triggerAutoRoutePolicyCheck | |     |
|   |   | ENTER/KEEP decision  |     | network callbacks       |     | (reason based)              | |     |
|   |   +----------------------+     +-------------------------+     +------------------------------+ |     |
|   |                                                                                                   |     |
|   |   +---------------------------+   +---------------------------+   +---------------------------+  |     |
|   |   | ZeroTierRuntimeController |   | ServiceNotificationCtrl   |   | EventBus Publisher        |  |     |
|   |   | node/udp/vpn lifecycle    |   | connected + traffic       |   | Node/Peer/Config events   |  |     |
|   |   +---------------------------+   +---------------------------+   +---------------------------+  |     |
|   +-----------------------------------------------------------------------------------------------+         |
|                                         | virtual frame / node api                                       |
|                                         v                                                               |
|  [Data Plane Layer]                                                                                        |
|   +-----------------------------------------------------------------------------------------------+         |
|   | TunTapAdapter                                                                                 |      |
|   |  - TUN read/write                                                                             |      |
|   |  - IPv4/IPv6 parse                                                                            |      |
|   |  - routeForDestination + gateway rewrite                                                      |      |
|   |  - ARP/NDP resolve                                                                            |      |
|   |  - processVirtualNetworkFrame / onVirtualNetworkFrame                                         |      |
|   +-----------------------------------------------------------------------------------------------+         |
|                                         |                                                               |
|                                         v                                                               |
|   +-----------------------------------------------------------------------------------------------+         |
|   | ZeroTier Core JNI 1.14.2                                                                      |      |
|   |  - join / virtual network config / multicast / frame transport                                |      |
|   +-----------------------------------------------------------------------------------------------+         |
|                                                                                                             |
|  [Observability Layer]                                                                                      |
|   +-----------------------------------------------------------------------------------------------+         |
|   | CONNECT_TRACE: RoutePolicyEngine / updateTunnelConfig / TunTap.forward                         |      |
|   +-----------------------------------------------------------------------------------------------+         |
+=============================================================================================================+
```

## 关键时序图

### 1) 手动连接时序

```text
User/UI                 Service                    Policy                 Runtime+Core                 TunTap/VPN
  |                       |                         |                          |                           |
  |--- switch ON -------->|                         |                          |                           |
  |                       | onStartCommand          |                          |                           |
  |                       |--- evaluate(service_start) -----------------------> |                           |
  |                       |<-- KEEP_RUNNING ----------------------------------- |                           |
  |                       |--- startRuntime ----------------------------------->| node/udp/vpn alive       |
  |                       |--- joinNetwork(nwid) ------------------------------>|                           |
  |                       |<-- CONFIG_UPDATE(status=OK) -----------------------|                           |
  |                       |--- updateTunnelConfig ----------------------------->|                           |
  |                       |   |___ addAddress(192.168.100.236/24)              |                           |
  |                       |   |___ addManagedRoute(192.168.10.0/24 -> gw 192.168.100.1)                  |
  |                       |   |___ addManagedRoute(0.0.0.0/0 -> gw 192.168.100.1, routeViaZeroTier=true) |
  |                       |   |___ establish(vpnSocket) ---------------------->| TUN ready                 |
  |<-- NodeStatus/Peer ---|                         |                          |                           |
```

### 2) 网络切换时序（自动策略）

```text
Android NetCb                Service                         Policy                    Runtime                  UI
   |                           |                               |                          |                       |
   |-- network_lost ---------->|                               |                          |                       |
   |-- network_available ----->| triggerAutoRoutePolicyCheck  |                          |                       |
   |                           |--- evaluate(reason=...) ----->|                          |                       |
   |                           |<-- action/detail -------------|                          |                       |
   |                           |                               |                          |                       |
   |   if action=ENTER_MONITOR_ONLY                            |                          |                       |
   |                           |--- stopRuntime(keep=true) ----------------------------->| runtime stopped      |
   |                           |--- post NodeDestroyed(keep=true) ---------------------------------------> |
   |                           |--- post IntranetCheckState(true) --------------------------------------> |
   |                           |                                                                       (开关意图保留)
   |                           |                               |                          |                       |
   |   if action=KEEP_RUNNING and monitorOnly=true            |                          |                       |
   |                           |--- startRuntime --------------------------------------->| runtime ready        |
   |                           |--- joinNetwork(lastNwid) ------------------------------>|                       |
   |                           |<-- config update ---------------------------------------|                       |
   |                           |--- updateTunnelConfig -> establish -------------------->|                       |
   |                           |--- post NodeStatus/NetworkList -----------------------------------------> |
```

### 3) 数据转发时序（默认路由经 ZeroTier）

```text
App Socket      Kernel Route             TUN fd              TunTapAdapter                             ZeroTier / GW
   |               |                       |                     |                                           |
   | dst=192.168.8.8                       |                     |                                           |
   |-------------->| match 0.0.0.0/0       |                     |                                           |
   |               |---------------------->| read(packet)        |                                           |
   |               |                       |-------------------->| routeForDestination -> 0.0.0.0/0         |
   |               |                       |                     | gateway=192.168.100.1                     |
   |               |                       |                     | rewrite dst: 192.168.8.8 -> 192.168.100.1|
   |               |                       |                     | ARP/NDP resolve                           |
   |               |                       |                     | processVirtualNetworkFrame() ------------>|
   |               |                       |                     |<------------------- RESULT_OK ------------|
   |               |                       |                     | log: TunTap.forward src/dst/forwardedDst  |
```

## 协议与数据面说明

### 控制面（Control Plane）

```text
+=============================================================================================================+
|                                  控制面协议（Control Plane Protocol）                                      |
+=============================================================================================================+
| 状态机:                                                                                                     |
|                                                                                                             |
|   [IDLE]                                                                                                    |
|      |                                                                                                      |
|      | onStartCommand(networkId)                                                                            |
|      v                                                                                                      |
|   [RUNTIME_READY] --- joinNetwork ---> [JOINING] --- config OK ---> [CONNECTED]                            |
|      ^                                    |                             |                                   |
|      |                                    | leave/stop                  | network callbacks                 |
|      |                                    v                             v                                   |
|      |<------ stopZeroTierRuntime(false)-[STOPPED]             [POLICY_CHECK]                              |
|                                                           /                           \                     |
|                                          action=ENTER_MONITOR_ONLY               action=KEEP_RUNNING        |
|                                                   |                                   |                     |
|                                                   v                                   v                     |
|                                         [MONITOR_ONLY]  ----- resume ----->  [RUNTIME_READY]               |
|                                                                                                             |
| Service 启停协议:                                                                                            |
|   - onStartCommand: 注册监听 -> 策略评估 -> 启动运行时 -> join                                              |
|   - startOrResumeZeroTierAndJoin: 统一连接主链路                                                            |
|   - stopZeroTierRuntime(false): 完整停服（注销监听 + 回收运行时）                                           |
|   - stopZeroTierRuntime(true): 监听态停内核（Service 保活）                                                 |
|                                                                                                             |
| EventBus 协议:                                                                                              |
|   - NodeStatusEvent                                                                                         |
|   - NetworkReconfigureEvent                                                                                 |
|   - IntranetCheckStateEvent                                                                                 |
|   - NodeDestroyedEvent(keepServiceAlive)                                                                    |
+=============================================================================================================+
```

### 数据面（Data Plane）

```text
+=============================================================================================================+
|                                  数据面协议（Data Plane Protocol）                                          |
+=============================================================================================================+
| 转发路径:                                                                                                   |
|                                                                                                             |
|  App Traffic -> Android Route Table -> VpnService TUN fd -> TunTapAdapter                                  |
|      |                                                                                                      |
|      +-- IPv4 path: routeForDestination -> gateway rewrite -> ARP -> processVirtualNetworkFrame            |
|      +-- IPv6 path: routeForDestination -> gateway rewrite -> NDP(NS/NA) -> processVirtualNetworkFrame     |
|                                                                                                             |
|  TunTapAdapter -> ZeroTier Virtual Network -> Remote Managed Route Gateway (e.g. 192.168.100.1)           |
|                                                                                                             |
| 路由行为:                                                                                                   |
|   - Assigned Route: 本地网络地址段直连                                                                      |
|   - Managed Route: 控制器下发网段                                                                           |
|   - Default Route(0.0.0.0/0): routeViaZeroTier=true 时下发                                                  |
|   - Gateway Rewrite: 跨网段目的地址改写到 managed gateway                                                   |
|                                                                                                             |
| 邻居协议:                                                                                                   |
|   - IPv4: ARP cache/request/reply                                                                           |
|   - IPv6: NDP(NS/NA)                                                                                        |
+=============================================================================================================+
```

### ZeroTier 数据协议（本项目视角，字节级）

> 说明：本项目通过 `node.processVirtualNetworkFrame(...)` 发送“二层帧负载”，并在
> `onVirtualNetworkFrame(...)` 接收。应用层主要处理 `etherType + frameData`，以下为代码实际使用到的字节位。

```text
+=============================================================================================================+
|                          ZeroTier 虚拟帧协议（应用侧可见字段，字节级）                                    |
+=============================================================================================================+
| A) Virtual Frame 元信息（SDK 参数）                                                                       |
|                                                                                                             |
|   processVirtualNetworkFrame(nowMs, networkId, srcMac, dstMac, etherType, vlanId, frameData, nextDeadline)|
|                                                                                                             |
|   etherType:                                                                                               |
|     0x0800 (2048)  -> IPv4                                                                                 |
|     0x0806 (2054)  -> ARP                                                                                  |
|     0x86DD (34525) -> IPv6                                                                                 |
|                                                                                                             |
| B) IPv4 frameData 关键偏移                                                                                 |
|   byte[0]      : Version(高4位) + IHL(低4位)                                                               |
|   byte[12..15] : Source IPv4 (IPPacketUtils.getSourceIP)                                                   |
|   byte[16..19] : Dest IPv4   (IPPacketUtils.getDestIP)                                                     |
|                                                                                                             |
| C) IPv6 frameData 关键偏移                                                                                 |
|   byte[0]       : Version(高4位)                                                                           |
|   byte[6]       : Next Header                                                                              |
|   byte[8..23]   : Source IPv6 (IPPacketUtils.getSourceIP)                                                  |
|   byte[24..39]  : Dest IPv6   (IPPacketUtils.getDestIP)                                                    |
|   byte[40]      : ICMPv6 Type (当 byte[6] == 58)                                                           |
|                    135 (0x87 / Java signed -121) -> Neighbor Solicitation                                 |
|                    136 (0x88 / Java signed -120) -> Neighbor Advertisement                                |
|                                                                                                             |
| D) ARP frameData 标准布局                                                                                  |
|   byte[0..1]   : Hardware Type                                                                             |
|   byte[2..3]   : Protocol Type                                                                             |
|   byte[4]      : Hardware Size                                                                             |
|   byte[5]      : Protocol Size                                                                             |
|   byte[6..7]   : Opcode                                                                                    |
|   byte[8..13]  : Sender MAC                                                                                |
|   byte[14..17] : Sender IPv4                                                                               |
|   byte[18..23] : Target MAC                                                                                |
|   byte[24..27] : Target IPv4                                                                               |
|                                                                                                             |
| E) 路由匹配与目的改写                                                                                      |
|   1) route = routeForDestination(destIP)                                                                   |
|   2) gateway = route.gateway                                                                               |
|   3) if gateway != null and sourceRoute != destRoute: forwardedDest = gateway                             |
|      else: forwardedDest = destIP                                                                          |
|   4) 发送: processVirtualNetworkFrame(..., etherType, frameData, ...)                                     |
|                                                                                                             |
| F) 典型日志（192 网段示例）                                                                                |
|   TunTap.forward ipv4 src=192.168.100.236 dst=192.168.8.8 forwardedDst=192.168.100.1                     |
|     route=0.0.0.0/0 gateway=192.168.100.1 result=RESULT_OK                                                 |
+=============================================================================================================+
```

### DNS 模式

```text
+=============================================================================================================+
|                                        DNS 协议策略                                                        |
+=============================================================================================================+
|  [NO_DNS]       -> 不下发 DNS，解析走系统默认链路                                                          |
|  [NETWORK_DNS]  -> 使用 ZeroTier 网络下发 DNS + search domain                                              |
|  [CUSTOM_DNS]   -> 使用用户配置 DNS 列表                                                                    |
+=============================================================================================================+
```

> 建议：全局代理场景优先 `NETWORK_DNS` 或 `CUSTOM_DNS`，避免域名请求仍走本地 DNS 导致策略偏差。

## 日志诊断关键字

以下关键字可用于快速定位链路：

- `CONNECT_TRACE`
- `RoutePolicyEngine.evaluate`
- `Service.updateTunnelConfig`
- `TunTap.forward`
- `Service.triggerAutoRoutePolicyCheck`

示例：

```bash
adb logcat -v time | findstr "CONNECT_TRACE RoutePolicyEngine Service.updateTunnelConfig TunTap.forward"
```

## Download

Check [Releases page](https://github.com/kaaass/ZerotierFix/releases) for latest version.

If you want to try the nightly build, you can download it from [GitHub Actions](https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml?query=branch%3Amaster).
But please note that the nightly build may be **BUGGY** and **UNSTABLE**.

## Copyright

The code for this repository is based on the reverse engineering of the official Android client.
The original author is Grant Limberg (glimberg@gmail.com).
See [AUTHORS.md](https://github.com/zerotier/ZeroTierOne/blob/master/AUTHORS.md#primary-authors) for more details.

- ZeroTier JNI SDK is located in `externals/core` / `externals/core-upstream`
- Original Android client code is located in `net.kaaass.zerotierfix` (renamed from `com.zerotier.one`)
- App logo is a trademark of `ZeroTier, Inc.`

## Acknowledgements

Special thanks to the original and upstream projects:

- [ZeroTierOne](https://github.com/zerotier/ZeroTierOne)
- [kaaass/ZerotierFix](https://github.com/kaaass/ZerotierFix)
- Engineering assistant: **OpenAI Codex**

This project is built on top of their work and would not be possible without their contributions.
