# PackFailover — Stage 0 协议 PoC 报告

> **状态：Stage 0 已通过真实 Java 客户端测试。Stage 1 核心 PLAY 路径已完成真实 RPEngine + 真实客户端验证（原 UUID 失败 -> 自动 fallback -> RECOVERED）；CONFIG 阶段、required 包和上游状态同步仍待验证。**
> **基线：Paper 26.2 / Java 25 / Gradle 9.8.0 / PacketEvents 2.14.0**
> **日期：2026-10-19**
> **范围：Stage 0 PoC 记录 + Stage 1 MVP 实施记录。**

> **隐私说明：** 本文档中的测试服 IP、绝对路径、玩家名和玩家 UUID 已替换为占位符；实际测试记录保留在测试服本地，不进入公开仓库。

---

## 1. 结论摘要

Stage 0 已完成“可编译、可测试、可观测、可手动触发”的最小 PoC：

1. 通过 PacketEvents 被动捕获 **CONFIGURATION** 与 **PLAY** 两个阶段的服务端资源包推送，记录
   `player UUID / pack UUID / URL / SHA-1 / required / prompt / phase / 时间戳`。
2. 通过 Paper `PlayerResourcePackStatusEvent` 关联 PLAY 阶段状态；通过 PacketEvents
   `WrapperConfigClientResourcePackStatus` 补上 CONFIGURATION 阶段状态。
3. 提供 `/pf artifact <zip>` 异步校验完整 SHA-1（并检查 ZIP 结构与 `pack.mcmeta`）。
4. 提供 `/pf poc simulate` / `/pf poc send` / `/pf poc resend`：在同一 `pack UUID + SHA-1`
   下只替换 URL，安全地构造 `FAILED_DOWNLOAD` 并验证一次备用重推。
5. 默认配置为 **只观察**（`fallback.enabled: false`, `dry-run: true`），不会自动改变线上玩家行为。
6. 备用重推使用 `Player#addResourcePack`，**不使用** `Player#setResourcePack`；后者在 Paper 26.2
   会先发送清空全部资源包的 `ClientboundResourcePackPopPacket(Optional.empty())`，会破坏 RPEngine 堆叠。

**Stage 0 已通过：** 用户已用真实 Java 客户端验证“相同 pack UUID、相同 SHA-1、不同 URL”的
重推路径可行。Stage 1 在此基础上实现了自动捕获、SHA-1 索引、不可变缓存、HTTP 备用服务，
以及 PLAY/CONFIGURATION 的自动一次重试。Stage 1 的真实 RPEngine 端到端验证仍需在安装
RPEngine 的测试服上完成。

---

## 2. 依赖与 API 核验结果

### 2.1 实际解析到的版本

| 组件 | 版本 / 坐标 | 核验方式 |
|---|---|---|
| Paper API | `io.papermc.paper:paper-api:26.2.build.134-stable` | `repo.papermc.io` Maven metadata；`paper-api` 内 `apiVersioning.json` 为 `{"version":"26.2.build.134-stable","currentApiVersion":"26.2"}` |
| Paper 服务端 | `paper-26.2-134.jar` | Paper Fill API v3，`STABLE`，构建时间 2026-10-14 |
| Java | JDK 25.0.4.1 | `java -version`；`paper-api` class major version = 69（Java 25） |
| Gradle | 9.8.0（wrapper） | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin DSL | Gradle 9.8.0 自带 | `build.gradle.kts` |
| PacketEvents | `com.github.retrooper:packetevents-spigot:2.14.0` | CodeMC `maven-releases`；GitHub release v2.14.0（2026-09-23） |
| PacketEvents API | `com.github.retrooper:packetevents-api:2.14.0` | 由 spigot 构件传递依赖 |
| JUnit | `org.junit:junit-bom:6.1.3` | Maven Central |

PacketEvents 2.14.0 的发布说明明确包含 **Minecraft 26.3 支持**；其 API jar 中存在
`ServerVersion.V_26_2` 与 `ClientVersion.V_26_2`，因此对 Paper 26.2 的协议层支持是成立的。

> 备注：Paper 26.2 运行时自带 Adventure 5.2.0；PacketEvents 2.14.0 的 POM 编译依赖为
> Adventure 4.26.1。本插件不直接绑定 Adventure 版本，只在 PacketEvents wrapper 的
> `Component prompt` 与 `Component.text(...)` 之间传递对象。该组合将在本地 Paper 启动
> 烟雾测试中验证（见第 9 节）；仍需在真实测试服复核。

### 2.2 已验证的 API 表面（来自实际 jar / Paper 26.2 源码）

**PacketEvents 捕获（服务端 -> 客户端）**

```java
PacketType.Configuration.Server.RESOURCE_PACK_SEND
PacketType.Play.Server.RESOURCE_PACK_SEND

new WrapperConfigServerResourcePackSend(PacketSendEvent)
new WrapperPlayServerResourcePackSend(PacketSendEvent)

UUID    getPackId()
String  getUrl()
String  getHash()       // 40-hex 字符串；MAX_HASH_LENGTH = 40
boolean isRequired()
Component getPrompt()
```

**PacketEvents 状态（客户端 -> 服务端，CONFIGURATION 阶段）**

```java
PacketType.Configuration.Client.RESOURCE_PACK_STATUS
new WrapperConfigClientResourcePackStatus(PacketReceiveEvent)
UUID getPackId()
Result getResult()      // ACCEPTED/DOWNLOADED/SUCCESSFULLY_LOADED/FAILED_DOWNLOAD/
                        // DECLINED/FAILED_RELOAD/INVALID_URL/DISCARDED
```

**PacketEvents 注册与用户上下文**

```java
PacketEvents.getAPI()                              // 可能为 null / 未初始化，必须判空
PacketEvents.getAPI().getEventManager().registerListener(listener)
new PacketListenerAbstract(PacketListenerPriority.MONITOR)
onPacketSend(PacketSendEvent), onPacketReceive(PacketReceiveEvent)
event.getUser() -> User; user.getUUID(); user.sendPacket(PacketWrapper)
```

**Paper Player API（26.2）**

```java
// 非破坏性追加推送（本插件使用）：
void addResourcePack(UUID id, String url, byte[] hash /*20 bytes*/, String prompt, boolean force)

// 破坏性设置：会先清空客户端所有资源包，禁止用于 fallback：
void setResourcePack(UUID id, String url, byte[] hash, String prompt, boolean force)
void setResourcePack(UUID uuid, String url, byte[] hash, Component prompt, boolean force)

void removeResourcePack(UUID id)
void removeResourcePacks()
```

**Paper 状态事件（仅 PLAY 阶段）**

```java
PlayerResourcePackStatusEvent.getID()      // pack UUID
PlayerResourcePackStatusEvent.getStatus()  // 8 个枚举值
PlayerResourcePackStatusEvent.getHash()    // @Deprecated(forRemoval=true), 恒为 null，禁止使用
```

**plugin.yml**

`paper-api` 的 `apiVersioning.json` 声明 `currentApiVersion = 26.2`，因此本插件使用：

```yaml
api-version: '26.2'
```

### 2.3 关键发现 A：CONFIGURATION 阶段不会触发 Paper 状态事件

对 Paper 26.2 源码的核对（tag `26.2`）：

- `paper-server/patches/sources/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch`
  的 `handleResourcePackResponse` 只：
  - 调用 `super`（Adventure `ResourcePackCallback`），
  - 更新 `connection.resourcePackStatus`，
  - 不调用 Bukkit `PlayerResourcePackStatusEvent`。
- `paper-server/patches/sources/net/minecraft/server/network/ServerCommonPacketListenerImpl.java.patch`
  的 `handleResourcePackResponse` 只执行 Adventure callback，不调用 Bukkit 事件。
- 只有在
  `paper-server/patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch`
  （PLAY 阶段）才执行：
  ```java
  cserver.getPluginManager().callEvent(
      new PlayerResourcePackStatusEvent(this.getCraftPlayer(), packet.id(), packStatus));
  ```

**结论：** 如果上游插件在配置阶段推送资源包（例如 `server.properties` 的 server pack，
或某些登录流程插件），其 `FAILED_DOWNLOAD` **不会**通过 `PlayerResourcePackStatusEvent`
到达插件。Stage 0 因此额外监听 PacketEvents 的 CONFIGURATION 客户端状态包；
配置阶段的备用重推只能通过 PacketEvents `User#sendPacket` 完成，且默认关闭
（`fallback.experimental-config-phase: false`），因为该路径尚未经过真实客户端验证。

### 2.4 关键发现 B：`setResourcePack` 会清空整个资源包堆叠

Paper 26.2 `CraftPlayer` 实现：

```java
public void setResourcePack(UUID id, String url, byte[] hash, String prompt, boolean force) {
    ...
    this.handlePushResourcePack(new ClientboundResourcePackPushPacket(...), true);
}

public void addResourcePack(UUID id, String url, byte[] hash, String prompt, boolean force) {
    ...
    this.handlePushResourcePack(new ClientboundResourcePackPushPacket(...), false);
}

private void handlePushResourcePack(ClientboundResourcePackPushPacket packet, boolean resetBeforePush) {
    if (resetBeforePush) {
        this.removeResourcePacks();   // ClientboundResourcePackPopPacket(Optional.empty())
    }
    ...
}
```

Adventure 重载 `setResourcePack(UUID, url, hash, Component, boolean)` 同样显式先发送
`ClientboundResourcePackPopPacket(Optional.empty())`。

**结论：** 故障转移绝不能调用 `setResourcePack` / `clearResourcePacks` / `removeResourcePacks()`；
Stage 0 统一使用 `addResourcePack`，仅追加推送一个相同 UUID 的包。是否会在客户端形成
“同 UUID 替换”而非“重复叠加”，仍需真实客户端验证。

### 2.5 已知协议限制：状态包没有“代次”

客户端返回的状态包只包含 pack UUID 和状态，不包含 attempt generation。因此当同一
`player + pack UUID` 在短时间内被连续重推时，旧推送的迟到状态可能被归到新推送。
Stage 0 的处理：

- 每个 `(player, pack)` 只保留一个 current attempt；
- 每次推送递增 `generation`，旧 attempt 留在历史中用于诊断；
- 状态只作用于 current attempt；
- 无法在协议层彻底区分迟到状态，**列入剩余未知项**。

---

## 3. Stage 0 架构与模块

```text
RPEngine / other plugin
        │  ResourcePackPush (CONFIGURATION or PLAY)
        ▼
Paper / Netty
        │  PacketEvents PacketSendEvent (read-only, MONITOR)
        ▼
PackRequestTracker ──────────────► PlayerPackSessionStore
        │  (UUID, URL, SHA-1, required, prompt, phase)     (epoch + generation)
        │
        │  status: PLAY  -> PlayerResourcePackStatusEvent
        │          CONFIG-> WrapperConfigClientResourcePackStatus
        ▼
FailoverCoordinator ◄──────────── VerifiedArtifactRegistry (full SHA-1 only)
        │
        ├─ eligibility: allowlisted source, complete SHA-1, verified artifact,
        │              fallback URL valid, attempts < max, not Bedrock, online
        │
        ├─ PLAY  -> BukkitPlayPackSender -> Player#addResourcePack (main thread)
        └─ CONFIG-> PacketEvents User#sendPacket (experimental, default off)

/pf artifact <zip> -> ArtifactVerifier (bounded I/O executor, SHA-1 + ZIP check)
```

### 3.1 状态机（Stage 0 实现）

```text
PRIMARY_PENDING
  ├─ ACCEPTED -------------> PRIMARY_PENDING
  ├─ DOWNLOADED -----------> PRIMARY_DOWNLOADED
  ├─ SUCCESSFULLY_LOADED --> PRIMARY_LOADED      [终态]
  ├─ FAILED_DOWNLOAD ------> PRIMARY_FAILED_DOWNLOAD
  │     └─ eligible -------> FALLBACK_PENDING
  ├─ DECLINED -------------> DECLINED            [终态，不重试]
  ├─ FAILED_RELOAD --------> FAILED_RELOAD       [终态，不切源]
  ├─ INVALID_URL ----------> INVALID_URL         [终态]
  └─ DISCARDED ------------> DISCARDED           [终态]

FALLBACK_PENDING
  ├─ ACCEPTED/DOWNLOADED --> FALLBACK_PENDING / FALLBACK_DOWNLOADED
  ├─ SUCCESSFULLY_LOADED --> RECOVERED           [终态]
  ├─ FAILED_DOWNLOAD ------> EXHAUSTED           [终态，不再重试]
  └─ DECLINED -------------> DECLINED            [终态]
```

`DOWNLOADED` 不视为最终成功；仍等待 `SUCCESSFULLY_LOADED` 或 `FAILED_RELOAD`。

### 3.2 关联键

```text
(player UUID, pack UUID, connectionEpoch, generation)
```

- `connectionEpoch`：玩家每次连接递增；CONFIGURATION 阶段捕获的 attempt 在 `PlayerJoinEvent`
  被 adopt 到新 epoch，保证 PLAY 阶段状态能命中。
- `generation`：同一 `(player, pack)` 每次新推送递增；旧 attempt 标记 `SUPERSEDED` 并保留历史。
- 不依赖玩家名、不依赖 URL、不使用 `PlayerResourcePackStatusEvent.getHash()`。

### 3.3 线程模型

- PacketEvents 回调：只读取 packet 字段、写内存 store、打日志；**不调用 Bukkit API、不做文件/HTTP I/O**。
- `ArtifactVerifier`：固定 2 线程 daemon I/O 池；SHA-1 与 ZIP 校验均在异步线程。
- PLAY 阶段发送：`BukkitPlayPackSender` 检查 `server.isPrimaryThread()`，否则调度到主线程；
  使用 `Player#addResourcePack`。
- CONFIGURATION 阶段发送：在 PacketEvents 回调线程直接 `User#sendPacket`，不触碰 Bukkit。
- 会话存储使用 `ConcurrentHashMap` + per-session `synchronized`，状态转移具备原子性。

### 3.4 安全/一致性硬约束（Stage 0 已实现）

- 只处理**完整 40-hex SHA-1**；8 位截断哈希只记录为不可用，绝不推断。
- 只有 `/pf artifact <zip>` 成功校验过的 SHA-1 才允许 fallback。
- 主来源按**精确 hostname** 白名单匹配，拒绝后缀/子串伪装。
- 只对 `FAILED_DOWNLOAD` 自动重试；`DECLINED`、`FAILED_RELOAD`、`INVALID_URL` 不切源。
- 每包最多一次自动 fallback（`max-attempts-per-pack: 1`）。
- 不调用 `setResourcePack`、`clearResourcePacks`、`removeResourcePacks`。
- 不向 Bedrock/Floodgate 玩家发送 Java 资源包（若 Floodgate API 可用）。
- 日志中的 URL 去掉 query/fragment/user-info。
- 默认 `fallback.enabled: false` + `dry-run: true`，可在不改变服务器行为的情况下观察。

---

## 4. 文件变更

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew, gradlew.bat, gradle/wrapper/*
.gitignore

src/main/resources/plugin.yml
src/main/resources/config.yml

src/main/java/dev/cody/packfailover/
  PackFailoverPlugin.java
  artifact/{ArtifactVerifier,VerifiedArtifact,VerifiedArtifactRegistry}.java
  bedrock/BedrockDetector.java
  command/PfCommand.java
  config/{ConfigManager,PoConfig}.java
  failover/{BukkitPlayPackSender,FailoverCoordinator,FallbackPlan,FallbackRequest,PlayPackSender}.java
  session/PlayerPackSessionStore.java
  tracking/{CapturedSend,OutgoingSendRegistry,PackAttempt,PackRequestTracker,PackState,
            PackStatus,PackStatusListener,ProtocolPhase,SendKind}.java
  util/{PfLogger,Sha1Utils,UrlPolicy}.java

src/test/java/dev/cody/packfailover/
  artifact/ArtifactVerifierTest.java
  failover/FailoverCoordinatorTest.java
  tracking/{OutgoingSendRegistryTest,PlayerPackSessionStoreTest}.java
  util/{Sha1UtilsTest,UrlPolicyTest}.java

docs/PROTOCOL-POC.md
```

---

## 5. 构建与测试结果

### 5.1 构建

```text
$ ./gradlew clean build
BUILD SUCCESSFUL
```

产物：

```text
build/libs/PackFailover-0.0.1-SNAPSHOT.jar   (~80 KB)
```

`plugin.yml` 已确认展开为 `version: '0.0.1-SNAPSHOT'`、`api-version: '26.2'`。
主类字节码目标为 Java 25（class major 69）。

> 环境说明：本沙箱的 DSH 文件策略禁止写入 `~/.gradle/native`，因此本次构建使用
> 工作区内的 `GRADLE_USER_HOME`：
> `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew build`。
> 这不是项目缺陷；普通开发机直接 `./gradlew build` 即可。

### 5.2 自动测试

52 个 JUnit 6 测试全部通过，覆盖：

- 完整 SHA-1 校验、截断/非 hex 拒绝、文件哈希；
- hostname 精确匹配、子串/后缀攻击拒绝、模板解析、loopback 检测、日志脱敏；
- CONFIGURATION 推送在 join 时 adopt、代次递增、fallback 只一次、断线清理、重连重置重试预算、历史有界；
- `FAILED_DOWNLOAD` -> 同 UUID/SHA-1/新 URL 单次重推；
- `DECLINED` / `FAILED_RELOAD` / 终态后不再重试；
- 未验证 artifact、非白名单来源、dry-run、fallback 关闭时跳过；
- fallback URL 与原始 URL 完全相同的时候拒绝无效重推；
- required 包策略、PoC 绕过自动开关、CONFIGURATION 实验路径返回计划；
- 手动 `resend` 与未知包拒绝；
- outgoing 自发送标记只消费一次；
- ZIP 结构/`pack.mcmeta`/超限/缺失文件校验。

未覆盖：PacketEvents 真实事件对象、Paper 玩家 API、真实客户端网络行为（需要真实服务器/客户端）。

---

## 6. 如何在测试环境安全模拟 `FAILED_DOWNLOAD`

**不要**改生产 DNS/防火墙，**不要**改 RPEngine 配置，**不要**让全体玩家切源。
推荐使用插件自带的 per-player PoC 入口：

### 方式 A：重放已捕获的 RPEngine 推送（最真实）

```text
/pf poc simulate <player>
```

- 需要该玩家已有一条捕获到的 primary 推送，且 SHA-1 为完整 40-hex；
- 插件用**原始 pack UUID、原始 SHA-1、原始 required/prompt**，仅把 URL 换成
  `poc.bad-url-template`（默认 `http://127.0.0.1:1/{sha1}.zip`，该地址在**玩家机器**上
  解析，预期连接被拒绝）；
- 客户端应返回 `FAILED_DOWNLOAD`，随后触发一次同 UUID/SHA-1 的备用 URL 重推；
- 只影响该测试玩家，不动其他玩家和上游插件状态。

### 方式 B：无上游推送时构造合成包

```text
/pf artifact /path/to/identical.zip
/pf poc send <player> <sha1> [required]
```

- 使用 `UUID.nameUUIDFromBytes("packfailover-poc:" + sha1)` 作为稳定测试 UUID；
- 同样先失败、再用同一 UUID/SHA-1 推送 fallback；
- 用于先验证协议，再接入 RPEngine 实际推送。

### 推荐顺序

1. 先用 **方式 B**（合成 UUID）验证“失败 -> 同 UUID/SHA-1 换 URL -> 成功”的纯协议行为；
   合成 UUID 没有历史缓存，最容易观察到真实的 HTTP GET。
2. 再用 **方式 A** 验证 RPEngine 实际推送的 UUID/URL/SHA-1。若该玩家此前已成功加载同一包，
   客户端可能走 SHA-1 缓存而不重新下载，建议在首次进入/清缓存后、或真实 `FAILED_DOWNLOAD`
   发生后立即测试。

### 注意事项

- `required=true` 的包在客户端失败/拒绝时可能导致客户端断开；先测 `required=false`。
- 如果客户端此前已成功缓存同一 SHA-1 的包，再次推送同 UUID/SHA-1 **可能直接使用缓存而不下载**，
  从而看不到 fallback HTTP 请求。要验证真实下载失败路径，请在 `FAILED_DOWNLOAD` 发生后立即重推，
  或在新客户端/清缓存环境测试。
- `/pf poc abort <player>` 可清理测试状态。

---

## 7. 如何验证“相同 UUID + 相同 ZIP/SHA-1 + 不同 URL”

### 7.1 前置条件

1. 在测试服保留 RPEngine `host.mode: upload`，不要改生产配置。
2. 准备与 RPEngine 官方 URL 完全相同的 ZIP 文件；用完整 SHA-1 校验：
   ```bash
   sha1sum /path/to/pack.zip
   # 必须与 /pf debug 捕获到的 sha1 完全一致（40 位）
   ```
3. 将该 ZIP 原样发布到运营者备用 HTTP 入口，例如：
   ```bash
   # 仅测试示例；生产应使用受控静态服务器，不要用临时目录暴露敏感文件
   python3 -m http.server 8181 --directory /srv/packfailover-test
   ```
   确认客户端所在网络可访问：
   ```bash
   curl -I http://<PUBLIC_HOST>:8181/packs/<sha1>.zip
   ```
4. 编辑 `plugins/PackFailover/config.yml`：
   ```yaml
   fallback:
     url-template: "http://<PUBLIC_HOST>:8181/packs/{sha1}.zip"
   ```
5. `/pf reload`。

### 7.2 执行

```text
/pf artifact /path/to/pack.zip       # 异步校验完整 SHA-1
/pf status                           # 确认 verifiedArtifacts=1
# 让测试玩家进入服务器，等待 RPEngine 推送
/pf debug <player>                   # 记录 packId / sha1 / phase / source
/pf poc simulate <player>            # 同 UUID + 同 SHA-1 + 坏 URL
```

然后观察控制台日志顺序（示例）：

```text
[PackFailover] captured player=... packId=... phase=PLAY source=packs.resourcepack.ai sha1=... required=false ...
[PackFailover] status phase=PLAY player=... packId=... kind=PRIMARY status=FAILED_DOWNLOAD state=PRIMARY_PENDING->PRIMARY_FAILED_DOWNLOAD
[PackFailover] fallback_planned phase=PLAY player=... packId=... sha1=... url=http://<host>:8181/packs/<sha1>.zip
[PackFailover] fallback_push_sent player=... packId=... sha1=... required=false url=...
[PackFailover] status phase=PLAY player=... packId=... kind=FALLBACK status=ACCEPTED state=FALLBACK_PENDING->FALLBACK_PENDING
[PackFailover] status phase=PLAY player=... packId=... kind=FALLBACK status=SUCCESSFULLY_LOADED state=FALLBACK_PENDING->RECOVERED
[PackFailover] recovered player=... packId=... sha1=...
```

### 7.3 通过标准（人工确认）

- [ ] 客户端确实先出现下载失败（`FAILED_DOWNLOAD`），而不是 `DECLINED`。
- [ ] 备用 HTTP 服务器日志出现对 `/<sha1>.zip` 的 `GET`（证明客户端真的重新下载，而非只用缓存）。
- [ ] 客户端最终显示资源包加载成功（`SUCCESSFULLY_LOADED` / `RECOVERED`）。
- [ ] 资源包内容正确（不是旧版/错误 ZIP）。
- [ ] 没有出现重复叠加的同一资源包，也没有清空其他资源包。
- [ ] RPEngine 的列表/状态/踢出逻辑没有异常；`/rp` 相关命令仍显示正确状态。
- [ ] 另一名正常玩家全程不受影响，没有额外提示或重推。
- [ ] 断线重连后，旧会话状态不会覆盖新会话。
- [ ] `required=true` 场景单独记录实测行为（不假设一定能救回）。
- [ ] Bedrock/Floodgate 玩家不会被推送 Java 包。

### 7.4 自动 fallback 路径

PoC 通过后，才把测试服配置改为：

```yaml
fallback:
  enabled: true
  dry-run: false
```

这样真实 RPEngine 的 `FAILED_DOWNLOAD` 才会自动触发一次 fallback。
生产环境默认保持关闭，直到完成审核。

---

## 8. 与 RPEngine 状态和资源包堆叠的关系

**设计上：**

- 本插件不修改 RPEngine 发出的任何 packet；PacketEvents 监听为只读（MONITOR），不 cancel。
- 本插件不调用 `removeResourcePack` / `removeResourcePacks` / `clearResourcePacks`。
- fallback 使用 `addResourcePack` 追加推送，不触碰其他 pack。
- 成功玩家完全不受影响；只有收到 `FAILED_DOWNLOAD` 的玩家才可能收到一次同 UUID 重推。
- RPEngine 后续再次推送同一 UUID 时，会作为新 `generation` 处理，旧 attempt 不会覆盖新 attempt。

**尚待真实验证：**

- 客户端对“同 UUID、不同 URL、同 SHA-1”的第二次 push 是替换、忽略还是重新下载；
- 是否会在客户端资源包列表中留下重复条目；
- RPEngine 自己的内存状态/UI 是否感知到这次“非它发起”的重推；
- 若 RPEngine 在 fallback 后再次推送原 URL，客户端行为如何。

在这些问题得到客户端证据前，不能宣称“通用透明故障转移已实现”。

---

## 9. 本地 Paper 26.2 启动烟雾测试

为验证插件元数据、加载顺序和 PacketEvents 钩子，本机启动了一个临时 Paper 26.2 测试服
（非生产、非 Docker、无 FRP）：

```text
Paper 26.2 build 134 (paper-26.2-134.jar, STABLE)
Java 25.0.4.1
PacketEvents 2.14.0 (GitHub release, Spigot bundle)
PackFailover 0.0.1-SNAPSHOT
server.properties: online-mode=false, flat, view-distance=4
```

实际日志（节选，第二次启动；世界已生成，19.566s 完成启动）：

```text
[packetevents] Loading server plugin packetevents v2.14.0
[PackFailover] Loading server plugin PackFailover v0.0.1-SNAPSHOT
[packetevents] Enabling packetevents v2.14.0
[PackFailover] Enabling PackFailover v0.0.1-SNAPSHOT
[PackFailover] packetevents_hooked version=2.14.0
[PackFailover] stage0_enabled paper=26.2 java=25 packetevents=hooked fallbackEnabled=false dryRun=true allowlist=[packs.resourcepack.ai]
[PackFailover] stage0_scope: no HTTP server, no automatic pack providers; use /pf artifact and /pf poc for verification.
[PackFailover] floodgate_not_present: Bedrock detection is inactive; Java packs are still gated by tracking/allowlist.
Done (19.566s)!
```

控制台命令实测：

```text
> pf status
[PackFailover] PackFailover Stage 0 PoC (Paper 26.2 / Java 25)
[PackFailover] enabled=true packetevents=ready http=not-implemented-in-stage0
[PackFailover] tracking config=true play=true allowlist=[packs.resourcepack.ai] trackedPlayers=0 activeAttempts=0
[PackFailover] fallback enabled=false dryRun=true maxAttempts=1 experimentalConfigPhase=false url=(not configured)
[PackFailover] verifiedArtifacts=0 fallbackPlanned=0 recovered=0 skipped=0

> pf debug NoSuchPlayer
[PackFailover] Player is not online: NoSuchPlayer

> pf reload
[PackFailover] Reloaded. enabled=true fallbackEnabled=false dryRun=true

> pf help
[PackFailover] PackFailover Stage 0 PoC commands: ...
```

`/pf artifact` 也做了真实 Paper 运行时验证。测试 ZIP 的 `sha1sum` 为
`53b11a070a17f3c8c6dd7b1962e2d1561caa6b0b`：

```text
> pf artifact /tmp/pf-paper-test/testpack.zip
[PackFailover] Verifying /tmp/pf-paper-test/testpack.zip asynchronously...
[PackFailover] artifact_verified sha1=53b11a070a17f3c8c6dd7b1962e2d1561caa6b0b size=323 file=/tmp/pf-paper-test/testpack.zip
[PackFailover] Verified sha1=53b11a070a17f3c8c6dd7b1962e2d1561caa6b0b size=323 file=/tmp/pf-paper-test/testpack.zip

> pf artifact list
[PackFailover]   sha1=53b11a070a17f3c8c6dd7b1962e2d1561caa6b0b size=323 file=/tmp/pf-paper-test/testpack.zip

> pf status
[PackFailover] verifiedArtifacts=1 fallbackPlanned=0 recovered=0 skipped=0
```

停服时正常执行 `stage0_disabled`，无插件异常。

**结论：**

- `api-version: '26.2'`、`plugin.yml`、Java 25 字节码、softdepend 加载顺序均通过 Paper 26.2 实际加载验证。
- PacketEvents 2.14.0 在 Paper 26.2 上成功初始化，`PacketEvents.getAPI()` 可用，监听器注册成功。
- `/pf status` / `/pf debug` / `/pf reload` / `/pf help` 在真实 Paper 命令总线上工作。
- `/pf artifact` 的异步 SHA-1 + ZIP/`pack.mcmeta` 校验在真实 Paper 上工作，结果与系统
  `sha1sum` 一致，registry 计数正确更新。
- 由于没有真实 Java 客户端连接，**没有**验证资源包捕获、状态关联、`FAILED_DOWNLOAD`、
  同 UUID 重推、HTTP 下载或 RPEngine 交互。

**环境杂音（与本插件无关）：** 沙箱限制了 JNA 解压临时 native 库，Paper 的 oshi 系统信息
采集打印了 `Permission denied` 的 `UnsatisfiedLinkError`。服务器仍正常启动，PackFailover 与
PacketEvents 均正常加载；这不是插件缺陷或 Paper 26.2 兼容性问题。

---

## 10. 剩余未知 / BLOCKED 项

| 项目 | 状态 | 说明 |
|---|---|---|
| 同 UUID + 同 SHA-1 + 不同 URL 是否重新下载 | **UNKNOWN** | 必须真实 Java 客户端验证 |
| 客户端是否因 SHA-1 缓存而跳过下载 | **UNKNOWN** | 影响 fallback 是否真正走 HTTP |
| 同 UUID 重推是否造成重复堆叠/替换 | **UNKNOWN** | 需观察客户端资源包列表 |
| RPEngine 推送发生在 CONFIG 还是 PLAY | **UNKNOWN** | 用 `/pf debug` 捕获确认 |
| CONFIGURATION 阶段状态桥接 | **源码已确认事件缺口；运行时未验证** | PacketEvents 路径已实现 |
| CONFIGURATION 阶段 fallback | **实验性，默认关闭** | `User#sendPacket` 路径未验证 |
| required=true 失败后是否来得及重推 | **UNKNOWN** | 客户端可能直接断开 |
| RPEngine `/rp reload` 改变 SHA-1 | **UNKNOWN** | 需验证新哈希与旧缓存共存 |
| 多包堆叠 | **UNKNOWN** | Stage 0 不主动管理多个包 |
| 服务端资源包 remove/pop 包 | **未跟踪** | Stage 0 只跟踪 push；上游若移除某包，fallback 可能重新加入 |
| fallback prompt 格式 | **已知降级** | 从 PacketEvents `Component` 取纯文本，格式/点击事件不保留 |
| Floodgate 检测 | **未在真实 Bedrock 连接验证** | 反射检测，缺失时按 Java 处理 |
| Paper 26.2 + Adventure 5.2.0 + PacketEvents 2.14.0 | **烟雾测试中确认加载；协议行为未验证** | 见第 9 节 |
| FRP 8181/TCP 公网可达性 | **未测试** | 不在本项目内改 FRP |
| 生产部署/回滚 | **未执行** | Stage 0 不触碰生产 |

---

## 11. 精确人工验证步骤（按顺序）

1. 在**测试服**安装：
   - Paper 26.2 build 134（Java 25）
   - PacketEvents 2.14.0
   - 本 jar：`build/libs/PackFailover-0.0.1-SNAPSHOT.jar`
   - RPEngine（保持 `host.mode: upload`）
2. 启动服务器，确认控制台出现：
   ```text
   [PackFailover] packetevents_hooked version=...
   [PackFailover] stage0_enabled ...
   ```
   若出现 `packetevents_unavailable`，先解决 PacketEvents 加载/版本问题。
3. 用测试 Java 客户端进入，等待 RPEngine 推送主包。
4. 执行 `/pf debug <player>`，记录：
   - `phase=CONFIGURATION` 还是 `PLAY`
   - `packId`
   - `sha1` 是否完整 40 位
   - `source=packs.resourcepack.ai`
   - `required`
5. 准备完全相同的 ZIP，执行 `/pf artifact <zip>`；核对输出的 sha1 与第 4 步一致。
6. 配置 `fallback.url-template` 为客户端可达的备用 URL；用外部浏览器/`curl` 确认该 URL 返回
   完全相同字节（`curl -s URL | sha1sum` 一致）。
7. `/pf poc simulate <player>`，观察控制台与客户端。
8. 重复以下场景并记录：
   - 正常玩家（不触发）
   - 主动 `DECLINED`
   - `required=true`
   - 断线重连
   - 同一玩家多个包堆叠
   - RPEngine `/rp reload` 改变 SHA-1
   - 两名玩家并发（一人失败、一人正常）
9. 把客户端截图/日志、备用 HTTP 访问日志、`/pf debug` 输出追加到本文档。
10. 只有全部通过标准满足后，才把 Stage 0 标记为 PASS；否则记录 blocker 并调整设计。

---

## 12. 下一步（Stage 1 前不要跳过）

- 如果 Stage 0 证明同 UUID 方案可行：进入 Stage 1，补齐 Config/Provider/ImmutableCache/HTTP server，
  并保留当前状态机与安全门槛。
- 如果同 UUID 方案失败：先在同一 PoC 代码上评估“不同 UUID + 映射清理”方案，
  记录客户端行为后再决定，不得直接假定可行。
- 在 Stage 1 中把 `ArtifactVerifier` 扩展为 `ArtifactIndexer` + 不可变缓存，
  并保持“完整 SHA-1 才能发布”的约束。
- 为 CONFIGURATION 阶段 fallback 单独做真实验证后再决定是否开启。

---

## 13. 参考

- Paper 26.2 API: <https://jd.papermc.io/paper/26.2/org/bukkit/entity/Player.html>
- Paper 26.2 源码（tag `26.2`）:
  - <https://github.com/PaperMC/Paper/blob/26.2/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java>
  - <https://github.com/PaperMC/Paper/blob/26.2/paper-server/patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch>
  - <https://github.com/PaperMC/Paper/blob/26.2/paper-server/patches/sources/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch>
  - <https://github.com/PaperMC/Paper/blob/26.2/paper-server/patches/sources/net/minecraft/server/network/ServerCommonPacketListenerImpl.java.patch>
- Paper plugin.yml: <https://docs.papermc.io/paper/dev/plugin-yml/>
- PacketEvents 2.14.0: <https://github.com/retrooper/packetevents/releases/tag/v2.14.0>
- PacketEvents Javadocs: <https://javadocs.packetevents.com/>
- RPEngine 配置/命令: <https://resourcepack.ai/docs/rp-engine/config>, <https://resourcepack.ai/docs/rp-engine/commands>

---

## 14. 测试服部署记录（2026-10-19）

> 本节记录实际测试服环境，不是生产服。生产 `/etc/frp/frpc.toml` 未被修改。

### 14.1 实际环境

- 测试服由 **tmux 直接运行**，不是 Docker：
  - 路径：`<TEST_SERVER_DIR>`
  - Paper：`paper-26.2-134.jar`，JDK 25
  - 世界：`skyborough-test`
  - tmux session：`test-server`
- 本机 Docker socket 当前无权限，未发现可访问的测试服容器；未修改任何 Docker/Compose 文件。
- 测试服原有插件：uSkyBlock、WorldEdit、WorldGuard、spark；**没有 RPEngine**。
- `server.properties` 中 `resource-pack=` 为空，因此测试服本身不会在 join 时推送资源包。

### 14.2 已安装内容

```text
<TEST_SERVER_DIR>/server/plugins/packetevents-2.14.0.jar
<TEST_SERVER_DIR>/server/plugins/PackFailover-0.0.1-SNAPSHOT.jar
<TEST_SERVER_DIR>/server/plugins/PackFailover/config.yml
```

启动日志确认：

```text
[PackFailover] packetevents_hooked version=2.14.0
[PackFailover] stage0_enabled paper=26.2 java=25 packetevents=hooked ...
[PackFailover] verifiedArtifacts=1 ...
```

### 14.3 测试资源包与 HTTP 入口

```text
SHA-1:      ac92cc20f9c3daf02e4ff532b4a902de54ce4a36
本地 ZIP:   <TEST_SERVER_DIR>/http/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip
本地 URL:   http://127.0.0.1:8181/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip
公网 URL:   http://<TEST_SERVER_IP>:8181/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip
```

测试包内容：

- `pack.mcmeta`，`pack_format: 88`（Minecraft 26.2）
- `pack.png`
- `assets/minecraft/lang/en_us.json`：把泥土/钻石/石头改名为
  `PackFailover Test ...`，用于客户端肉眼确认包已加载。

HTTP 服务：

- 脚本：`<TEST_SERVER_DIR>/http_server.py`
- 绑定：`127.0.0.1:8181`
- 仅接受 `GET/HEAD /packs/<40 位小写 hex>.zip`
- 无目录列表、无路径穿越、无上传、无代理
- 已有 `curl` 验证：`200` + SHA-1 一致；未知哈希 `404`
- tmux session：`packfailover-http`
- 已写入 `start.sh` / `stop.sh`，测试服重启后自动启动/停止

### 14.4 测试 FRP（仅测试实例）

修改文件：`<TEST_SERVER_DIR>/frpc.toml`（备份在
`<TEST_SERVER_DIR>/backups/packfailover-20261019-083908/frpc.toml`）。

追加：

```toml
[[proxies]]
name = "packfailover-http-8181"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8181
remotePort = 8181
```

实际 frpc 日志：

```text
proxy added: [packfailover-http-8181 skyborough-test-game-25566]
[packfailover-http-8181] start proxy success
[skyborough-test-game-25566] start proxy success
```

原有游戏路由 `<TEST_SERVER_IP>:25566 -> 127.0.0.1:25575` 保持不变，且公网 25566
实测可连接。生产 `/etc/frp/frpc.toml` 未修改。

### 14.5 当前阻塞：8181 防火墙

本机 `curl` 公网 8181 仍然超时（`000` / connection timed out），符合“8181 尚未放行”。
需要在 **<TEST_SERVER_IP> 的 FRPS 安全组/防火墙** 放行 `8181/TCP`；不需要改本机
iptables（frpc 访问的是本机 127.0.0.1:8181）。

放行后建议从外部网络验证：

```bash
curl -I http://<TEST_SERVER_IP>:8181/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip
# 期望 200, Content-Type: application/zip, Content-Length: 946
curl -s http://<TEST_SERVER_IP>:8181/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip | sha1sum
# 期望 ac92cc20f9c3daf02e4ff532b4a902de54ce4a36
```

### 14.6 人工测试命令

连接：`<TEST_SERVER_IP>:25566`（`online-mode=false`）。

在测试服控制台（`tmux attach -t test-server`，Ctrl+B 然后 D 退出）执行：

```text
pf artifact <TEST_SERVER_DIR>/http/packs/ac92cc20f9c3daf02e4ff532b4a902de54ce4a36.zip
pf poc send <player> ac92cc20f9c3daf02e4ff532b4a902de54ce4a36
```

> `/pf artifact` 的已验证哈希是**内存态**；测试服重启后需要重新执行一次。

观察：

```bash
tail -f <TEST_SERVER_DIR>/server/logs/latest.log
tail -f <TEST_SERVER_DIR>/server/logs/packfailover-http.log
tail -f <TEST_SERVER_DIR>/server/logs/frpc.log
```

期望：

```text
captured ... -> FAILED_DOWNLOAD -> fallback_planned -> fallback_push_sent
-> ACCEPTED/DOWNLOADED/SUCCESSFULLY_LOADED -> recovered
```

客户端侧：资源包列表出现 `PackFailover Test Pack (Paper 26.2)`；如果语言为英文，
泥土/钻石/石头名称变为 `PackFailover Test ...`。

### 14.7 尚未完成 / 注意事项

- 8181 公网防火墙尚未放行（等待用户操作）。
- 真实 Java 客户端尚未连接测试；上述命令需要在玩家在线后执行。
- 测试服没有 RPEngine，因此当前验证的是 Stage 0 的“合成同 UUID 包”路径；
  真实 RPEngine 路径需要另外安装/配置 RPEngine 后再做 `/pf poc simulate`。
- 回滚备份：`<TEST_SERVER_DIR>/backups/packfailover-20261019-083908/`
  （`frpc.toml`、`start.sh`、`stop.sh`）。

---

## 15. Stage 1 实施记录（2026-10-19）

> Stage 0 已由用户确认真实客户端通过。Stage 1 的目标是把 PoC 变成可自动运行的 MVP：
> 自动捕获 RPEngine 推送、按完整 SHA-1 索引、不可变缓存、HTTP 备用服务、真实失败自动切换，
> 同时保持上游分发不变，支持 PLAY 与 CONFIGURATION，每包最多一次重试。

### 15.1 Stage 1 模块

| 模块 | 实现 |
|---|---|
| `PackRequestTracker` | 被动捕获 CONFIG/PLAY 推送；不修改、不取消 |
| `PackStatusListener` | PLAY 状态事件；CONFIG 状态由 PacketEvents 补桥接 |
| `PlayerPackSessionStore` | player UUID + pack UUID + connectionEpoch + generation；断线/重连清理 |
| `DirectoryPackProvider` / `RPEnginePackProvider` | 有界扫描配置目录，拒绝符号链接越界 |
| `ArtifactIndexer` | 异步 SHA-1 校验、size+mtime 指纹复用、定期重扫、原子 registry 快照 |
| `ImmutablePackCache` | `<sha1>.zip` 临时文件 → 再校验 → 原子重命名；旧版本保留 |
| `HttpPackServer` | 仅 `GET/HEAD /packs/<40hex>.zip`；Content-Length/ETag/Cache-Control；404/405 |
| `FailoverCoordinator` | 仅 `FAILED_DOWNLOAD`、白名单来源、完整 SHA-1、已缓存文件、HTTP ready、每包一次 |
| `BukkitPlayPackSender` | PLAY 阶段 `Player#addResourcePack`，主线程 |
| PacketEvents CONFIG 发送 | `User#sendPacket(WrapperConfigServerResourcePackSend)` |
| `ConfigManager` | 校验 providers/http/fallback/cache；旧 Stage 0 `fallback.url-template` 兼容迁移 |
| `PfCommand` | `/pf status`、`/pf index`、`/pf cache`、`/pf debug`、`/pf artifact`、`/pf poc` |

### 15.2 自动切换流程

```text
RPEngine push
  │  PacketEvents capture: player UUID, pack UUID, URL, full SHA-1, required, prompt, phase
  ▼
PlayerPackSessionStore (epoch + generation)
  │
  ├─ PLAY status  -> PlayerResourcePackStatusEvent
  └─ CONFIG status-> WrapperConfigClientResourcePackStatus
  ▼
FAILED_DOWNLOAD
  ├─ kind 必须是 PRIMARY / POC_SIMULATED（FALLBACK 不再重试）
  ├─ sha1 必须为完整 40-hex
  ├─ VerifiedArtifactRegistry 必须命中（索引/缓存）
  ├─ HttpPackServer ready + public URL 已配置 + cache 文件存在
  ├─ 来源 host 在 allowlist
  ├─ fallbackCount < max-attempts-per-pack（默认 1）
  ├─ 玩家在线、非 Floodgate/Bedrock
  └─ 发送：同 pack UUID + 同 SHA-1 + fallback URL
       PLAY  -> addResourcePack（不清空其他包）
       CONFIG-> PacketEvents config push
```

### 15.3 关键安全约束

- 只对 `FAILED_DOWNLOAD` 自动重试；`DECLINED`、`FAILED_RELOAD`、`INVALID_URL` 不切源。
- 每包最多一次自动 fallback；第二次失败进入 `EXHAUSTED`。
- 只有完整 SHA-1 且本地已校验/缓存的 ZIP 才会被发布；短哈希直接跳过。
- HTTP 只服务缓存目录中的 `<sha1>.zip`，不接受路径、目录列表、上传或代理。
- `http.public-url` 拒绝 loopback/unspecified。
- 不使用 `setResourcePack` / `clearResourcePacks` / `removeResourcePacks`。
- RPEngine 原包不被修改、取消或删除；成功玩家不收到额外请求。
- 旧缓存版本继续可服务，避免 RPEngine `/rp reload` 时正在重试的玩家 404。

### 15.4 配置

完整配置见 [INSTALLATION.md](INSTALLATION.md) 和 `src/main/resources/config.yml`。关键项：

```yaml
providers:
  directories:
    - id: rpengine
      path: "plugins/RPEngine/output"
      glob: "**/*.zip"
      max-depth: 3
fallback:
  enabled: true
  max-attempts-per-pack: 1
  config-phase: true
http:
  bind-address: "0.0.0.0"
  port: 8181
  public-url: "http://<PUBLIC_HOST>:8181"
```

### 15.5 构建与测试

```text
./gradlew clean build
BUILD SUCCESSFUL
```

- 72 个 JUnit 6 测试全部通过（2026-10-19）。
- 覆盖：SHA-1、host 精确匹配、缓存原子写、索引复用/旧版本保留、Provider 深度/glob/符号链接、
  HTTP GET/HEAD/404/405/ETag、Coordinator 自动路径、CONFIG 计划、dry-run、required、HTTP 未就绪等。
- 真实 Paper 26.2 启动烟雾测试（本地）：插件加载、PacketEvents 2.14.0 钩子、`/pf status`、
  `/pf artifact` 均通过。

### 15.6 真实 RPEngine 验证状态

**BLOCKED（环境）**：当前可访问的测试服 `<TEST_SERVER_DIR>` 没有安装 RPEngine；
`plugins/RPEngine/output` 不存在。Stage 1 已用 `providers.directories` 指向本地测试包目录，
验证了索引、缓存、HTTP 和 Coordinator 自动路径（单元测试 + 服务器运行时），但**尚未**在真实
RPEngine 输出上验证：

1. RPEngine 实际输出目录；
2. RPEngine 发出的 hash 是否与输出 ZIP 的完整 SHA-1 一致；
3. `/rp reload` 后新 ZIP 是否被自动重扫；
4. RPEngine 自身 UI/状态是否与 fallback 共存。

需要：在测试服安装/配置 RPEngine（保持 `host.mode: upload`），或提供 RPEngine 输出目录/测试服
访问方式，然后执行 `/pf index`、`/pf debug <player>`、真实失败注入。

### 15.7 Stage 1 通过标准（尚未满足）

- [x] 自动捕获 PLAY/CONFIG 推送
- [x] 完整 SHA-1 索引与缓存
- [x] HTTP 备用服务
- [x] 自动一次 fallback 的代码与单元测试
- [x] 70 个自动测试通过
- [ ] 真实 RPEngine 输出的 SHA-1 匹配
- [ ] 真实 Java 客户端自动 fallback 成功（PLAY）
- [ ] 真实 Java 客户端 CONFIG 阶段 fallback 成功
- [ ] RPEngine UI/状态与多包堆叠正常
- [ ] `required=true`、重连、`/rp reload`、并发玩家实测

### 15.8 测试服 Stage 1 部署结果（2026-10-19 09:01）

- 已用 `PackFailover-0.1.0-SNAPSHOT.jar` 替换 Stage 0 jar。
- 测试服插件 HTTP 服务接管 `127.0.0.1:8181`；Stage 0 Python 辅助服务已从
  `start.sh` / `stop.sh` 移除。
- `test-frp` 仍在 `packfailover-http-8181` 上 `start proxy success`，游戏路由 25566 未变。
- 索引/缓存实测：

```text
provider_configured id=rpengine root=.../plugins/RPEngine/output ...
provider_configured id=test-packs root=.../plugins/PackFailover/test-packs ...
http_started bind=127.0.0.1:8181 publicUrl=http://<TEST_SERVER_IP>:8181 workers=4
artifact_verified sha1=ac92cc20... size=946
index_done providers=2 candidates=1 indexed=1 reused=0 rejected=0 errors=0 indexedSha1=1
cache_stored sha1=ac92cc20... size=946
```

- `/pf status`：`http=ready`、`indexed=1`、`cacheFiles=1`、`fallback enabled=true dryRun=false configPhase=true`。
- `/pf cache`：`ac92cc20...zip (946 bytes)`。
- 公网 HTTP 实测（8181 已放行）：

```text
HTTP/1.1 200 OK
Content-type: application/zip
Etag: "ac92cc20f9c3daf02e4ff532b4a902de54ce4a36"
Content-length: 946

sha1sum = ac92cc20f9c3daf02e4ff532b4a902de54ce4a36
```

- 未知哈希返回 `404`；HTTP 只服务缓存目录中的 `<sha1>.zip`。

**Stage 1 当前限制：** 测试服尚未安装 RPEngine，因此 `rpengine` provider 目录不存在，
当前只有 `test-packs` provider。真实 RPEngine 自动捕获/SHA-1 匹配/`/rp reload` 仍需
安装 RPEngine 后验证。

**Stage 0 真实客户端证据（2026-10-19 08:45，测试服日志）：**

```text
[08:45:29] fallback_push_sent ... url=http://127.0.0.1:1/ac92cc20...zip
[08:45:32] status ... kind=POC_SIMULATED status=FAILED_DOWNLOAD
[08:45:32] fallback_planned ... url=http://<TEST_SERVER_IP>:8181/packs/ac92cc20...zip
[08:45:32] fallback_push_sent ... url=http://<TEST_SERVER_IP>:8181/packs/ac92cc20...zip
[08:45:33] status ... kind=FALLBACK status=ACCEPTED
[08:45:33] status ... kind=FALLBACK status=DOWNLOADED
[08:45:34] recovered ...
[08:45:34] status ... kind=FALLBACK status=SUCCESSFULLY_LOADED
```

HTTP 访问日志同时出现 `GET /packs/ac92cc20...zip 200`，证明客户端确实通过 fallback URL
重新下载并加载。该次使用的是合成同 UUID 测试包路径；真实 RPEngine 路径仍需安装 RPEngine 后补测。

### 15.9 真实 RPEngine 输出验证（2026-10-19 09:05）

测试服已安装 **RPEngine 0.2.20**，配置保持：

```yaml
host:
  mode: upload
```

RPEngine 启动日志：

```text
[RPEngine] Packs are uploaded to https://packs.resourcepack.ai and players download them from there (host.mode: upload).
[RPEngine] 0 packs, 0 definitions, 0 bundles built, 0 recipes, 1 emote rig (a pack of their own, stacked on top).
```

实际输出：

```text
plugins/RPEngine/output/rigs/rpengine-rigs.zip   25199 bytes
sha1sum = 3a2bd9815782abc08c969c8ae2557374d901efdd
```

PackFailover 的 `rpengine` provider 已在运行时索引该 ZIP：

```text
artifact_verified sha1=3a2bd9815782abc08c969c8ae2557374d901efdd size=25199
file=.../plugins/RPEngine/output/rigs/rpengine-rigs.zip
cache_stored sha1=3a2bd9815782abc08c969c8ae2557374d901efdd size=25199
index_done providers=2 candidates=2 indexed=1 reused=1 rejected=0 errors=0 indexedSha1=2
```

`/pf status` 显示：

```text
http=ready
index indexed=2 cacheFiles=2
provider rpengine (.../plugins/RPEngine/output, glob=**/*.zip, max-depth=3)
provider test-packs (.../plugins/PackFailover/test-packs, glob=*.zip, max-depth=1)
```

**已证明：** RPEngine 的实际输出路径 `plugins/RPEngine/output` 可被 provider 发现，
输出 ZIP 的完整 SHA-1 与缓存索引一致。

**尚待客户端验证：** 目前没有玩家在线，因此还没有捕获 RPEngine 实际推送的
`pack UUID / URL / SHA-1`，也没有验证真实 RPEngine 推送的自动 fallback。测试步骤：

1. 连接 `<TEST_SERVER_IP>:25566`。
2. 控制台：
   ```text
   pf debug <player>
   ```
   应看到 RPEngine 推送的 pack UUID、phase（CONFIG/PLAY）、URL、
   `sha1=3a2bd9815782abc08c969c8ae2557374d901efdd`。
3. 强制该玩家失败而不影响其他玩家：
   ```text
   pf poc simulate <player>
   ```
   它使用 RPEngine 原 pack UUID + 原 SHA-1，仅把 URL 换成必定失败的地址。
4. 期望 Stage 1 自动路径：
   ```text
   status ... FAILED_DOWNLOAD
   fallback_planned ... url=http://<TEST_SERVER_IP>:8181/packs/3a2bd981...zip
   fallback_push_sent ...
   status ... FALLBACK ACCEPTED/DOWNLOADED/SUCCESSFULLY_LOADED
   recovered ...
   ```
5. 真实 RPEngine 主 URL 失败路径：仅对测试玩家阻断
   `packs.resourcepack.ai`，不要改全局 DNS/防火墙，然后重新入服观察。

### 15.10 真实 RPEngine + 真实客户端自动 fallback（2026-10-19 09:10）

为让 RPEngine 实际构建并推送 bundle，测试服临时加入一个最小内容包：

```text
plugins/RPEngine/content/pf-test/
  pack.yml                 name: PackFailover RPEngine Test, bundles: [main]
  overrides/lang/en_us.json  block.minecraft.dirt -> PackFailover RPEngine Test Dirt
```

`/rp reload` 后 RPEngine 日志：

```text
1 pack, 0 definitions, 1 bundle built, 0 recipes, 1 emote rig
Bundles  1 bundle, 464 B
  main  2 files · 464 B · 24695914
     https://packs.resourcepack.ai/h/24695914759efcf86baee41c7704f77657b3a3ee.zip
```

PackFailover 捕获到 RPEngine 真实推送（PLAY）：

```text
captured player=<TEST_PLAYER> packId=ecd244a3-242a-3d5b-b01e-8b429fd115de
  phase=PLAY source=packs.resourcepack.ai
  sha1=24695914759efcf86baee41c7704f77657b3a3ee
  url=https://packs.resourcepack.ai/h/24695914759efcf86baee41c7704f77657b3a3ee.zip

captured player=<TEST_PLAYER> packId=4e6c8afb-f171-3ea0-b76d-59ea78732f43
  phase=PLAY source=packs.resourcepack.ai
  sha1=3a2bd9815782abc08c969c8ae2557374d901efdd
  url=https://packs.resourcepack.ai/h/3a2bd9815782abc08c969c8ae2557374d901efdd.zip
```

`/pf index` 将 RPEngine 实际输出全部索引/缓存：

```text
RPEngine/output/main.zip                    464 B   sha1=24695914759efcf86baee41c7704f77657b3a3ee
RPEngine/output/rigs/rpengine-rigs.zip    25199 B   sha1=3a2bd9815782abc08c969c8ae2557374d901efdd
index_done providers=2 candidates=3 indexed=2 reused=1 rejected=0 errors=0 indexedSha1=3
```

自动 fallback 实测（使用 RPEngine `main` bundle 的真实 ZIP 字节与 SHA-1；
因客户端已缓存 RPEngine 原 UUID，改用客户端未加载的测试 UUID 来强制失败）：

```text
poc send ... sha1=24695914759efcf86baee41c7704f77657b3a3ee
status ... kind=POC_SIMULATED status=FAILED_DOWNLOAD
fallback_planned ... sha1=24695914759efcf86baee41c7704f77657b3a3ee
  url=http://<TEST_SERVER_IP>:8181/packs/24695914759efcf86baee41c7704f77657b3a3ee.zip
fallback_push_sent ... same URL
status ... kind=FALLBACK status=ACCEPTED
status ... kind=FALLBACK status=DOWNLOADED
recovered ...
status ... kind=FALLBACK status=SUCCESSFULLY_LOADED
```

同时 RPEngine 的 `main` 与 `rigs` 两个真实包仍保持 `PRIMARY_LOADED`，没有被 fallback 清空或替换；
公网 HTTP 实测：

```text
GET http://<TEST_SERVER_IP>:8181/packs/24695914759efcf86baee41c7704f77657b3a3ee.zip
200, 464 bytes, sha1sum=24695914759efcf86baee41c7704f77657b3a3ee
```

**已证明：**

- 真实 RPEngine 推送可被捕获（PLAY、`packs.resourcepack.ai`、真实 UUID/URL/SHA-1）。
- RPEngine 实际输出 `main.zip` / `rigs/rpengine-rigs.zip` 的 SHA-1 与捕获值完全一致。
- Stage 1 自动 fallback 在真实客户端上完成 `FAILED_DOWNLOAD -> 自动 fallback -> DOWNLOADED -> RECOVERED`。
- fallback 使用真实 RPEngine 产出的 ZIP 字节/SHA-1；RPEngine 的两个包保持加载，堆叠未被破坏。
- 每包一次重试：fallback 成功后没有再次推送。

**严格限制/未完成：**

- 直接用 RPEngine **原 pack UUID** 强制失败时，客户端已缓存同 UUID/SHA-1，返回
  `DOWNLOADED/SUCCESSFULLY_LOADED` 而不访问坏 URL，因此没有产生 `FAILED_DOWNLOAD`。
  这验证了“同 SHA-1 缓存命中”的限制，不构成 fallback 失败。
- 要严格验证“RPEngine 原 UUID 失败 -> 自动 fallback”，需要：
  1. 清客户端该 SHA-1 的服务器资源包缓存并使用新 profile；或
  2. 临时把 RPEngine `host.mode` 改为 `own-port`，`public-address` 指向不可达地址，
     并新增内容产生一个客户端未缓存的新 SHA-1，让真实 RPEngine 推送自然失败。
- CONFIGURATION 阶段仍只有单元测试/代码路径，尚无真实客户端证据（RPEngine 当前为 PLAY）。

### 15.11 严格验证：真实 RPEngine 原 UUID 失败 -> 自动 fallback（2026-10-19 09:22）

为排除客户端缓存，临时把 RPEngine 切到 `own-port`，并让 `public-address` 使用**白名单 host + 不可达端口**：

```yaml
host:
  mode: own-port
  port: 8182
  public-address: "http://packs.resourcepack.ai:1"
```

同时修改测试内容，产生新的 main bundle SHA-1：

```text
plugins/RPEngine/output/main.zip   475 B
sha1 = 4aa5d60bcd21040c8d84a2f215b9c614efc0b016
```

PackFailover 在服务器重启时先完成索引（4 个 artifact），随后客户端重新连接。捕获到**RPEngine 原 UUID**：

```text
captured player=<TEST_PLAYER>
  packId=ecd244a3-242a-3d5b-b01e-8b429fd115de
  phase=PLAY source=packs.resourcepack.ai
  sha1=4aa5d60bcd21040c8d84a2f215b9c614efc0b016
  url=http://packs.resourcepack.ai:1/packs/main/4aa5d60b...zip
```

客户端在约 75 秒后报告真实失败，PackFailover 自动完成：

```text
status ... kind=PRIMARY status=ACCEPTED
status ... kind=PRIMARY status=FAILED_DOWNLOAD
fallback_planned ... same packId
  sha1=4aa5d60bcd21040c8d84a2f215b9c614efc0b016
  url=http://<TEST_SERVER_IP>:8181/packs/4aa5d60b...zip
fallback_push_sent ... same packId/sha1
status ... kind=FALLBACK status=ACCEPTED
status ... kind=FALLBACK status=DOWNLOADED
recovered ...
status ... kind=FALLBACK status=SUCCESSFULLY_LOADED
```

**这完成了 Stage 1 的核心验收：**

- 真实 RPEngine 原 pack UUID；
- 真实 RPEngine 输出的完整 SHA-1；
- 真实客户端 `FAILED_DOWNLOAD`；
- 自动、一次、同 UUID/SHA-1 的 fallback；
- 客户端实际下载并加载成功。

同一次严格测试中，RPEngine 的 rigs 包也被推送为不可达 URL，客户端返回 `DISCARDED`，
因此没有重试（策略只对 `FAILED_DOWNLOAD` 生效）。这是测试构造导致的“两个包都不可达”；
真实网络故障下 rigs 是否得到 `FAILED_DOWNLOAD` 取决于客户端，不能假定。

### 15.12 严格测试后的恢复

已恢复 RPEngine：

```text
host.mode = upload
host.port = 8181
host.public-address = http://127.0.0.1:8181
```

启动日志确认：

```text
[RPEngine] Packs are uploaded to https://packs.resourcepack.ai
  and players download them from there (host.mode: upload).
```

`main.zip` 恢复为 `24695914759efcf86baee41c7704f77657b3a3ee`；PackFailover 重新索引，
`indexed=4`、`cacheFiles=4`、HTTP 仍 `ready`。`pf-test` 最小内容包保留，便于后续复测；
不需要时删除 `plugins/RPEngine/content/pf-test` 并 `/rp reload`。

### 15.13 Stage 1 验收状态（更新）

- [x] 自动捕获 PLAY 推送（真实 RPEngine）
- [x] 完整 SHA-1 索引与本地缓存
- [x] 插件 HTTP 备用服务（公网 200）
- [x] 真实 RPEngine 原 UUID + 真实 SHA-1 的自动 fallback
- [x] 真实 Java 客户端 `FAILED_DOWNLOAD -> RECOVERED`
- [x] 每包最多一次重试
- [x] 多包场景：只处理失败包（单元测试）
- [x] 构建 72 tests / 0 failures
- [ ] CONFIGURATION 阶段真实客户端验证（RPEngine 当前为 PLAY；代码/单元测试已覆盖）
- [ ] `required=true` 真实客户端行为
- [ ] RPEngine 自身 UI/状态在 fallback 后是否显示成功（严格测试中 RPEngine 控制台仍记录
      `could not download`；客户端已由 PackFailover 恢复，但上游状态未同步）
- [ ] 仅主包失败、rigs 正常时的多包堆叠实测

**结论：Stage 1 的核心目标（自动捕获、SHA-1 匹配、本地缓存、真实下载失败自动切换、
PLAY/CONFIG 支持、每包一次）已实现并完成真实 RPEngine + 真实客户端验证；
CONFIG 阶段、required 包和上游 RPEngine 状态同步仍列为剩余验证项。**
