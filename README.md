## ClashPilot

ClashPilot 是 [Clash Meta for Android (CMFA)](https://github.com/MetaCubeX/ClashMetaForAndroid) 的 fork，
在上游全部功能之上内置了一个**自动优选 Cloudflare 入口 IP 并自动维护订阅**的模块。

不用再手动跑优选脚本、不用再把结果贴来贴去：应用在真实底层网络上探测候选 IP、按延迟与吞吐打分、
把胜出的节点写进你的 Worker 共享列表，然后刷新绑定的订阅。

> **与上游的关系**：主体功能与 mihomo 内核跟随上游，差异只有两块 —— ① CF 优选模块
> （`service/src/main/java/com/github/kr328/clash/service/cfoptimizer/`）；② 品牌与包名。
> 内核仍来自 [MetaCubeX/Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)，整体以 GPL-3.0 发布，
> 见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

### 与上游的差异

| | 上游 CMFA | ClashPilot |
|---|---|---|
| CF 优选 | 无 | 内置（拉源 → 探测 → 测速 → 评分 → 上传 → 刷新订阅）|
| applicationId | `com.github.metacubex.clash[.alpha\|.meta]` | `cc.logicflash.clashpilot[.alpha\|.meta]` |
| 应用名 | Clash Meta for Android | ClashPilot |

> ⚠️ **包名与签名都和上游不同**：无法覆盖安装上游版本；反过来也一样。
> 两者可以共存（包名不同），但**配置、订阅、密钥都不共享** —— 这是全新安装，不能从上游版本"升级"过来。

### 安装

从 [Releases](../../releases) 下载对应架构的 APK：

- `*-arm64-v8a-*.apk` — 绝大多数现代手机
- `*-armeabi-v7a-*.apk` — 老设备
- `*-universal-*.apk` — 不确定就选它（体积最大）

Alpha 变体（`cc.logicflash.clashpilot.alpha`）与稳定变体可同时安装。

### CF 优选怎么用

1. **准备 Worker**：部署一个提供 `POST /login`（表单字段 `password`，返回 JSON `{"success":true}` 并下发会话
   Cookie）与 `POST /admin/ADD.txt`（同一 origin，`text/plain` 正文即节点列表）的共享列表服务。
2. **设置 → CF 优选 → Worker 地址**：填 Worker 的 HTTPS 源（仅接受规范 HTTPS origin，带路径或查询串会被拒绝）。
3. **上传密码**：本地以 Android Keystore（AES-256/GCM）加密保存，**永不回显**；界面只显示"已设置/未设置"。
4. **绑定的订阅**：选一个 **URL 类型**的订阅（它的内容由上面的 Worker 提供）。优选成功后应用会自动刷新它。
5. **立即运行优选**：跑一轮。第一次会提示"共享列表是全体设备共用的，本轮结果会覆盖它"。
6. 也可以打开**网络切换时自动扫描**，在网络变化时自动重跑。

运行时顶部通知栏会实时显示阶段与进度；设置页中"立即运行优选"这一行的副标题会显示当前阶段
或上次成功时间。

### 它是怎么工作的

```
① sources   从导航站发现镜像源（失败则回退本地缓存 → 内置备用源），抽样拉取候选 IP
①.5 memory  从跨轮记忆库取置信度最高的 20 个节点优先复测（稳定节点不丢），
            连续失败的地址在冷却期内不再取样（把探测配额留给新 IP）
② probe     对每个候选测 3 次 TTFB（取中位数 + 全距抖动），并查 /cdn-cgi/trace 拿归属地区
③ download  对最快的若干候选实测吞吐（下载测速，可关）
④ rank      打分 = (0.6 × TTFB 项 + 0.4 × 带宽项) × 100，按分数排序 → /24 网段去重 → 每地区配额
④.5 recall  把本轮实测写回记忆库：入选记分数、可达未入选记 TCP 命中、连不上记失败
⑤ upload    质量门通过后整体覆写 Worker 共享列表（失败/空列表一律不覆盖）
⑥ refresh   刷新你绑定的订阅（失败会重试 2 次，列表已上传的结果不会被回滚）
⑦ log       落盘本轮运行历史（候选明细 / 每轮摘要 / 入选结果），设置页可一键导出
```

**网络行为（重要）**：优选的**所有**网络 I/O —— 拉源、探测、测速、上传 —— 都绑定到
**真实物理网络**（Wi-Fi 优先，否则蜂窝），并显式排除 VPN 接口。
换句话说，**开着代理也能正常优选**：这些请求不会回流进本应用自己的 TUN，也就不会被自己的规则
送进代理节点绕远路（这正是早期版本"关代理顺利、开代理上传失败"的原因）。
订阅刷新由 mihomo 内核执行，这一环不受本模块控制。

**失败语义**：全程 fail-safe —— 空候选、未过质量门、上传失败都**不会**覆盖 Worker 上已有的列表；
订阅刷新失败时上传结果仍然保留，通知里会如实区分「已上传 / 订阅刷新失败」。

### 运行参数

「运行参数」是高级项，**不确定就保持默认**（留空或填非法值会自动回落到默认）。

| 参数 | 默认 | 说明 |
|---|---|---|
| 排除国家 | `RU,KP,CN,HK` | 两位 ISO 地区码，按 IP 的 Cloudflare trace 归属地判定。trace 失败的候选**保留**（未知 ≠ 被封） |
| 只选国家 | 空 | 空 = 不启用白名单；填了则只保留命中地区的候选 |
| 每轮源数 | 8 | 每轮从镜像源池抽取多少个源 |
| 单源抽样 | 40 | 每个源在去重前取多少行 |
| 候选上限 | 80 | 进入探测的 IP 数量（耗时主要在这一步）|
| 上传门槛 | 5 | 至少要有多少个达标节点才覆盖共享列表（防侥幸覆盖）|
| 每地区上限 | 2 | 同一个 Cloudflare 地区最多贡献几个节点（多样性）|
| 下载测速 | 开 | 对最快的候选实测吞吐（占评分 40%）。关掉省流量，但排序会退化成纯延迟排序 |
| 记忆库 | 开 | 跨轮记住每个 IP 的历史表现（成功率 / 新鲜度 / 当前时段加成），下轮优先复测稳定节点。关掉 = 每轮从零开始（对照实验用）|

一轮典型耗时约 1~2 分钟（80 个候选、并发 12），其中探测占大头。
下载测速只测延迟最优的 20 个候选，单次最多 3MB。

### 数据与记忆库

**跨轮记忆库**（默认开）：应用在 `files/cfoptimizer/memory.json` 里记住每个 IP 的历史表现 ——
成功率、最近得分、连续失败次数，以及**分时段**的历史得分。置信度公式与原版 `cf_memory.py` 一致
（`avg_score × 衰减系数 × 成功率 × 新鲜度`，当前时段有历史得分时最多再 +20%），字段名逐一对齐
（snake_case）—— 原版脚本养出来的记忆库可以直接导入，反过来也成立，两个方向都不需要转换。

**运行历史与导出**：每轮优选都会落盘（app 私有目录 `files/cfoptimizer/`）：

| 文件 | 内容 |
|---|---|
| `candidates.csv` | 最近一轮全部候选的实测指标（中文表头，`analyze_csv.py` 可直接读）|
| `runs.jsonl` | 每轮一行摘要：候选 / 探测 / 入选数、耗时、是否上传、记忆库规模（保留最近 200 轮）|
| `entry_ip_<网络标签>.json` | 本轮入选结果，结构与原版 `Output/entry_ip_*.json` 一致 |
| `memory.json` | 跨轮记忆库 |

设置页 **CF 优选 → 导出运行数据** 会把它们复制到
`Android/data/<applicationId>/files/cfoptimizer/`：不需要任何存储权限、卸载即清理，
Termux / `adb pull` 都能直接取走 —— 用来核对「第 1 轮 vs 第 N 轮」是不是真的越跑越准。

**公式口径的可复核性**：记忆库的每个字段与公式，用同一份夹具（43 次操作，覆盖 EMA、时段桶、
新鲜度衰减、过期与冷却、TCP 阈值边界、淘汰三条规则）分别喂给本实现与原版 `cf_memory.py`，
两侧输出**逐字节相同**。

### 自动化 / 外部控制

应用的 applicationId 由构建变体决定（`cc.logicflash.clashpilot` + `.alpha` / `.meta`），
下文以 Alpha 变体（`cc.logicflash.clashpilot.alpha`）为例：

- 切换服务状态：向 `com.github.kr328.clash.ExternalControlActivity` 发 action `cc.logicflash.clashpilot.alpha.action.TOGGLE_CLASH`
- 启动服务：action `cc.logicflash.clashpilot.alpha.action.START_CLASH`
- 停止服务：action `cc.logicflash.clashpilot.alpha.action.STOP_CLASH`
- 导入订阅：URL Scheme `clash://install-config?url=<encoded URI>` 或 `clashmeta://install-config?url=<encoded URI>`

### 构建

1. 拉取子模块

   ```bash
   git submodule update --init --recursive
   ```

2. 安装 **OpenJDK 21**、**Android SDK**、**CMake** 与 **Golang**

3. 在项目根目录创建 `local.properties`

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. （可选）自定义包名与后缀

   ```properties
   # 自定义 applicationId，默认为 cc.logicflash.clashpilot
   custom.application.id=cc.my.compile.clashpilot
   # 去掉 .alpha / .meta 后缀
   remove.suffix=true
   ```

5. 在项目根目录创建 `signing.properties`

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

6. 构建

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### 许可与致谢

- 本项目以 **GPL-3.0** 发布，见 [LICENSE](LICENSE)。
- 上游：[MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)（Android 外壳）与
  [MetaCubeX/Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)（mihomo 内核，`android-real` 分支）。
- 本 fork 保留上游全部版权声明与许可条款；改名与新增模块不影响上游署名。
  本项目与 MetaCubeX 官方**无关联**，请勿把问题反馈到上游仓库。
- CF 优选的算法口径对齐自社区优选脚本（TTFB 中位数 + 全距抖动、`/cdn-cgi/trace` 地区、评分
  `0.6 × (1 − ttfb/800ms) + 0.4 × min(1, mbps/150)`、`/24` 去重、每地区配额）。
