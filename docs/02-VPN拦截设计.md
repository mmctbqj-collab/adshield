# 02 · 网络侧设计：本地 VPN + DNS 域名拦截

## 1. 实现档位与选型

| 档位 | 做法 | 能拦什么 | 成本/风险 | 结论 |
|---|---|---|---|---|
| **A. 仅 DNS 层**（选中） | TUN 只捕获发往 DNS 服务器的 53 端口，命中规则直接回空应答 | 域名可识别的广告 SDK（绝大多数） | 低耗电、实现简单、不破坏 HTTPS | **MVP 采用** |
| B. 全流量用户态协议栈 | 完整解析 TCP/UDP，按 SNI/HTTP Host 过滤 | A 之外还能按 SNI 拦截 | 需自研或引入 tun2socks，耗电与稳定性风险高 | 后续可选 |
| C. HTTPS MITM 解密 | 安装自签 CA，解密后按 URL/内容过滤 | 理论上全部 | 安全风险极大、多数 App 有证书固定 | **不做** |

**决策：先做 A，保持接口可扩展到 B。** A 档已能覆盖主流广告 SDK（穿山甲、优量汇、AdMob、Mintegral、TopOn 等的素材与请求域名均为独立域名）。

---

## 2. TUN 建立参数（`TunBuilderFactory`）

```kotlin
builder
  .setSession("AdShield")
  .addAddress("10.111.222.1", 32)          // 本端虚拟地址
  .addDnsServer("10.111.222.1")            // 系统 DNS 指向本端
  .addRoute("10.111.222.1", 32)            // 必须：让发往本端的查询进入 TUN
  .mtu(1500)
  .addDisallowedApplication(context.packageName)  // 自身流量不过 VPN，避免回环
  .setBlocking(true)
  .setConfigureIntent(settingsPendingIntent)       // 通知点开进入设置页
```

### 2.1 路由策略：只让 DNS 进 TUN

关键点：**不要 `addRoute("0.0.0.0", 0)`**。全路由会让所有流量进入 TUN，而 A 档没有 TCP/IP 转发能力，结果是**整机断网**。

正确做法是"白名单式路由"：把每个**可能被用来做 DNS 查询的目标地址**单独加一条 /32 路由。

```kotlin
val targets = linkedSetOf<InetAddress>()
// 1) 系统当前各网络的 DNS 服务器（必须，覆盖 Wi-Fi / 蜂窝 / 私有 DNS 关闭场景）
connectivityManager.allNetworks.forEach { n ->
    connectivityManager.getLinkProperties(n)?.dnsServers?.let(targets::addAll)
}
// 2) 常见公共 DNS（App 硬编码时使用）
listOf("8.8.8.8","8.8.4.4","1.1.1.1","1.0.0.1","9.9.9.9","149.112.112.112",
       "114.114.114.114","114.114.115.115","223.5.5.5","223.6.6.6",
       "119.29.29.29","182.254.116.116","180.76.76.76","119.28.28.28")
   .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
   .forEach(targets::add)
// 3) IPv6 DNS（若设备 IPv6 可用）：同法加 ::1/128 之外的公网 DNS，如 2001:4860:4860::8888
targets.forEach { builder.addRoute(it, 32) }   // IPv6 用 128
```

路由需要在 **VPN 启动后随网络切换更新**（`ConnectivityManager.NetworkCallback` 监听 `onLinkPropertiesChanged`），否则切换 Wi-Fi/蜂窝后新 DNS 服务器不在路由表里，拦截会"部分失效"。更新方式是**重建 TUN**（改 routes 只能重建），因此要做"防抖 + 保留统计上下文"的平滑重建（先别断流超过 200ms）。

### 2.2 系统 DNS 一定要被改写

`addDnsServer("10.111.222.1")` 让系统解析器把查询发给我们。但要注意：

- App 通过 `getaddrinfo` 走的其实是系统解析器（可能被 `netd` 缓存），因此**进程内的 DNS 缓存会让"已经解析过的域名"继续可用**。这是 A 档最大盲区之一，需在文档与 UI 中说明（用户首次开启拦截后建议重启 App 或切换飞行模式刷新缓存）。
- 有的 App 直接向固定 DNS 发起 UDP 查询 → 由第 2.1 节的公共 DNS 路由兜住。

### 2.3 排除自身与冲突检测

- `addDisallowedApplication(self)` 防回环（自身规则订阅下载不能被自己拦）。
- **VPN 冲突检测**：`ConnectivityManager` 里若已有 `TRANSPORT_VPN` 网络且不是自己，`establish()` 会失败或抢占对方。策略：启动前检查 `NetworkCapabilities.TRANSPORT_VPN`，若存在其它 VPN → 弹窗说明"同时只能运行一个 VPN"，引导用户关闭代理类 App（Clash、其他加速器）或选择"仅无障碍模式"继续使用。

---

## 3. DNS 代理主循环（`DnsProxy`）

```
线程模型：
  ├─ Reader 线程：FileInputStream(tun).read(buf) 阻塞读，循环
  ├─ 每请求一个协程/线程池任务：解析 → 判定 → 应答
  └─ Writer 线程：FileOutputStream(tun).write(pkt)（单线程串行写，避免交错）

处理流程（单包）：
  1. 解析 IPv4/IPv6 头 → 必须 UDP 且 dstPort==53 或 TCP 且 dstPort==53，否则丢弃
     （路由已保证只会有 DNS 包进来；防御性判断仍保留）
  2. UDP：载荷即 DNS 报文
     TCP：2 字节长度前缀 + DNS 报文（需维护每连接的流状态，见 §3.3）
  3. DnsMessageCodec 解析：QNAME、QTYPE(A/AAAA/HTTPS/CNAME/其它)、TXID、EDNS0
  4. DomainMatcher.match(qname) → BLOCK / ALLOW / PASSTHROUGH
  5. BLOCK → 构造应答（§4），直接写回 TUN，记录事件
     ALLOW/PASSTHROUGH → 转发上游（§3.2），把应答改写回 TUN，按需记录事件
  6. 记录未命中域名（可开关，默认关闭，仅保留最近 N 条用于"误拦截诊断"）
```

### 3.1 性能约束

- 单包处理全部走**栈上字节数组 + 零拷贝切片**，不做 String 拼接，域名比对用 `ByteArray` 小写化后的后缀哈希。
- 域名 → 规则判定结果做 **LRU 缓存**（容量 4096，key=小写域名），TTL 跟随规则版本号，规则更新时清空。
- 结果聚合：内存计数（`ConcurrentHashMap<Pair<domain,uid>, AtomicInteger>`）每 5 秒批量落库，避免一包一次写 Room。

### 3.2 上游转发（`UpstreamResolver`）

```kotlin
// 必须 protect，否则上游 socket 又走 TUN → 无限递归
val socket = DatagramSocket().also { protect(it) }
socket.send(DatagramPacket(query, query.size, upstreamAddr, 53))
// 超时 3s；失败按 上游列表 轮询（最多 2 个上游 + 1 次重试）
```

- 上游选择：优先使用"系统 DNS 原始地址"（`LinkProperties.dnsServers` 中本端之外的第一个），失败则回退到 `1.1.1.1 / 8.8.8.8`（用户可在设置中选择"上游 DNS"）。
- 上游也有隐私含义：设置页提供"上游 DNS"选项并说明。
- 超时/异常时：返回 SERVFAIL（`RCODE=2`），不要静默丢包（丢包会让 App 卡 5 秒，体验更差）。

### 3.3 TCP DNS（M1.1）

部分解析器在收到截断（TC=1）或大响应时会改用 TCP 53。MVP 可以先**直接回 RST**（构造 TCP RST 包并写回 TUN，代价低），并把 TCP 53 加入"可拦截目标"；体验上表现为个别域名解析失败而不是卡死。完整实现（每连接读写缓冲）放到 M1.1，不作为首版阻塞项。

### 3.4 需要特别处理的记录类型

| 记录 | 处理 |
|---|---|
| A / AAAA | 命中：A → `0.0.0.0`，AAAA → `::`（可切换为 NXDOMAIN） |
| HTTPS（SVCB, type 65） | 命中：返回 `NOERROR` 且 ANCOUNT=0（空应答）。**必须处理**，否则 Chrome/部分 App 会走 SVCB 里的 ECH 元数据绕过 |
| CNAME | 命中：CNAME 链末端的 A 查询也会被独立命中（上游通常返回 AAAAA），只需确保 canonical name 也参与匹配 |
| PTR / TXT 等 | 直接转发，不判定 |

---

## 4. 命中应答构造

```
默认策略（可配置）：
  A    → 0.0.0.0        （连接被快速拒绝，App 通常 50ms 内失败回退）
  AAAA → :: 
  TTL  → 60（短 TTL 便于规则撤销后快速恢复）
  RCODE→ 0（NOERROR），RD=1，RA=1，保留原 TXID 与 Question 段

备选策略（针对"检测 0.0.0.0 后无限重试"的 App）：
  RCODE → 3（NXDOMAIN）
```

经验：`0.0.0.0` 能更快失败（TCP 连接立即被拒），对广告 SDK 更友好（SDK 走失败回调，不会卡界面）；`NXDOMAIN` 更"像正常失败"，但对某些 App 会触发长时间重试。**默认 0.0.0.0，规则级可覆盖。**

---

## 5. 逐 App 归因（这是"统计每个 App 拦截了多少次"的核心）

### 5.1 原理

`VpnService` 从 TUN 读到的是**原始 IP 包**，源地址/端口就是发起查询的 App 的真实 socket。Android 10（API 29）起提供：

```kotlin
val cm = context.getSystemService(ConnectivityManager::class.java)
val uid = cm.getConnectionOwnerUid(
    protocol,                                  // IPPROTO_UDP / IPPROTO_TCP
    InetSocketAddress(srcIp, srcPort),
    InetSocketAddress(dstIp, dstPort)
)
```

返回 `uid`（`Process.INVALID_UID = -1` 表示查不到）。再 `packageManager.getPackagesForUid(uid)` 得到包名。

### 5.2 可靠性分级（必须在 UI 如实呈现）

| 情况 | 结果 | 统计口径 |
|---|---|---|
| API ≥ 29 且返回有效 uid | 可归因 | 计入该 App |
| 返回 -1（常见于系统代理、NAT、tether、部分厂商 ROM） | 不可归因 | 计入"未知来源"，**不猜测** |
| API < 29 | 不可归因 | 全量计为"未知来源" |
| App 使用共享 uid（`sharedUserId`/`process` 复用） | 归因到共享包的宿主 | 备注说明 |

### 5.3 缓存与开销

- key = `(proto, srcIp:srcPort, dstIp:dstPort)`，但端口会被复用 → 缓存有效期 **10 秒**，并在收到 FIN/RST 或 RST 后失效（若实现 TCP）。
- 查询失败率高时（连续 20 次 -1）自动降级：关闭归因（省 CPU），UI 显示"当前设备不支持按 App 归因"。设置页保留手动重新探测。

### 5.4 为什么不能用 `/proc/net/*` 或 `NetworkStatsManager` 代替

- `/proc/net/udp` 在新系统上对非本进程不可读（SELinux）。
- `NetworkStatsManager` 只能拿到**流量字节数**（需 `PACKAGE_USAGE_STATS`），无法把某个域名挂到某个 App 上。
- 结论：`getConnectionOwnerUid` 是唯一免 root 的可行路径；不要在需求上做超出它的承诺。

---

## 6. 绕过与盲区清单（要写进 App 的"能力边界"页面）

| 绕过方式 | 效果 | 对策 |
|---|---|---|
| 系统"私人 DNS"（DoT，853） | DNS 层完全绕过，拦截率骤降 | 检测 `LinkProperties.isPrivateDnsActive`，引导设为"自动/关闭"；可把 853 目标路由进 TUN 并丢弃（副作用大，默认关闭，高级选项） |
| App 内置 DoH（如 `dns.google`、`cloudflare-dns.com`） | 同上 | **把 DoH 服务器域名本身加入黑名单**（域名解析失败 → DoH 初始化失败 → 回退系统 DNS），这是成本最低的有效对策。**已实现为可开关的规则集**（`BuiltinRules.DOH`，首页"阻断加密 DNS"开关，默认关闭） |
| 域名前置/同一域名混用 | 拦广告同时可能拦正常内容 | 白名单 + 误拦诊断（记录最近未命中/命中域名，支持一键加白） |
| 广告与业务共用第一方域名 | 完全拦不到 | 由无障碍引擎兜底（点掉已展示广告） |
| 已缓存的 DNS 记录（netd 缓存） | 开启后短时间内仍可解析 | 提示用户重启目标 App 或切换飞行模式 |
| IPv6-only 网络 / 自定义 DNS | 若未加路由则绕过 | §2.1 的 networkCallback 动态补路由（含 IPv6） |
| 广告素材走 CDN（与正常图片同域） | 只能拦接口，拦不住素材 | 属能力边界，靠体积/路径规则无法安全区分 |

---

## 7. 生命周期与稳定性

```xml
<service
    android:name=".vpn.AdShieldVpnService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse">
    <intent-filter><action android:name="android.net.VpnService"/></intent-filter>
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="local_dns_filter" />
</service>
```

- 首次启动必须走 `VpnService.prepare(context)` 授权（系统弹窗），返回 `RESULT_OK` 后才能 `startService`。
- `onStartCommand` 返回 `START_STICKY`；`onRevoke()` 里清理线程池并广播状态给 UI。
- 前台通知（常驻）：显示"已拦截 X 次 · 点此暂停"，提供暂停/停止按钮（`PendingIntent` → Service action）。
- 电池优化：引导用户加入"不受限制"白名单；`BOOT_COMPLETED` 时**不自动开启 VPN**（系统需要用户授权弹窗，静默建立会被拒绝），只恢复无障碍开关状态并提示。
- 进程被杀：依赖系统重启 + `onStartCommand` 恢复；重启后 TUN 会重新建立（统计状态从 Room 恢复，不需要内存态）。

---

## 8. 可观测性

- **环形日志**（内存 500 条 + 可选落盘）：记录 `qname / verdict / ruleId / uid / 耗时ms / 上游`，默认脱敏（域名保留，IP 打码）。
- 设置页"诊断"入口：显示 TUN 建立参数、路由表、上游可用性、归因成功率、近 1 小时拦截曲线。
- 崩溃隔离：DNS 线程内任何异常只影响当前包（返回 SERVFAIL），不允许中断读循环；连续 10 次异常则自动关闭 VPN 并通知用户"拦截模块已停止，网络已恢复"。

---

## 9. 真机验证要点（网络侧）

1. 开启后访问 `ad.qq.com`/`googleads.g.doubleclick.net` 应连接失败，正常网站不受影响。
2. 关闭 Wi-Fi 切 4G / 开关飞行模式后，拦截仍然生效（验证动态路由）。
3. 打开系统"私人 DNS"后拦截失效（验证提示是否出现）。
4. 从统计页核对：某 App 的拦截次数随其启动增长；未归因比例在真机上记录基线（不同 ROM 差异大）。
5. 连续运行 8 小时：耗电增量 < 3%（待机），内存 < 60MB。
6. 与 Clash/其它 VPN 同时开启：应给出明确冲突提示而不是崩溃或静默失效。