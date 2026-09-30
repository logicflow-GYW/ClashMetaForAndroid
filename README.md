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
6. 也可以打开**劣化自动补货**：当多数节点失联或变慢时（信号来自核心本来就在跑的 url-test
   健康检查），自动重跑一轮补齐名单。默认关闭，距上次优选最短 6 小时（参数页可调，设 **0 = 不节流**）。

运行时顶部通知栏会实时显示阶段与进度；设置页中"立即运行优选"这一行的副标题会显示当前阶段
或上次成功时间。

### 它是怎么工作的

```
① sources   从导航站发现镜像源（失败则回退本地缓存 → 内置备用源），抽样拉取候选 IP
①.5 memory  从跨轮记忆库取置信度最高的 100 个节点优先复测（稳定节点不丢），
            连续失败的地址在冷却期内不再取样（把探测配额留给新 IP）
①.7 tcp     只做 connect（900ms 超时、500 并发）淘汰死 IP，存活集才进 ② ——
            这一步是"能扫几千个"的关键：死 IP 的确认成本从 3 次完整 TLS+HTTP 超时（最坏 15s）
            降到 1 次 connect，2400 条原始池约 10 秒筛完
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
| 每轮源数 | 12 | 每轮从镜像源池抽取多少个源。实测单源产出极不均（最富的 `zip.cm.edu.kg` 15490 行，导航站多数 `.txt` 源只有 2–50 行），多抽几个买的是"抽到富源"的概率 |
| 单源抽样 | 1000 | 每个源在去重前取多少行（原版 `sample_limit` 就是 1000–3000；抽 250 等于把富源丢掉 98%）。这是**便宜层**，昂贵层由"候选上限"单独封顶 |
| 候选上限 | 1200 | **进入 TTFB 探测**的数量上限（昂贵层）。原始池 = 本值 × 2，即默认 1200 → 2400 条，只付 TCP connect 的价钱。最终名单 ≈ 有效探测量的 3–6%（抖动门槛刷掉绝大多数），所以这个数直接决定能选出几个节点 |
| 上传门槛 | 6 | 至少要有多少个达标节点才覆盖共享列表（防侥幸覆盖）|
| 每地区上限 | 4 | 同一个 Cloudflare 地区最多贡献几个节点（多样性；原版 `SMART_PUSH_IPS_PER_CC` = 4）|
| 下载测速 | 开 | 对最快的候选实测吞吐（占评分 40%）。关掉省流量，但排序会退化成纯延迟排序 |
| 记忆库 | 开 | 跨轮记住每个 IP 的历史表现（成功率 / 新鲜度 / 当前时段加成），下轮优先复测稳定节点。关掉 = 每轮从零开始（对照实验用）|
| 自动补货节流 | 6 | 劣化自动补货距上次优选的最小间隔（小时）。设 **0 = 不节流** —— 劣化又修不好时失去兜底，质量门会每 30 分钟空跑一轮 |

**两层配额（这是"能扫几千个"的结构）**：候选分两层，各自封顶 ——

| 层 | 规模 | 单价 | 谁封顶 |
|---|---|---|---|
| 便宜层（原始池） | 默认 2400 | 1 次 TCP connect（900ms 超时、500 并发） | `候选上限 × 2`，硬上限 20000 |
| 昂贵层（TTFB 段） | 默认 1200 | **先** 1 次 trace 定地区（100 并发）→ 过滤黑名单 → 3 次 TLS+HTTP TTFB（探测 50 并发）→ 下载测速 | `候选上限` |

**"几千个"是怎么来的**（原版真机日志实测，2026-09-15；日志原文在 minis 的
`/var/minis/skills/cf-memory-optimizer/scripts/Logs/cf_entrance_optimizer.log`，这里只摘四轮数据）：

| 轮次 | 入口候选 | TCP 存活 | 按延迟保留 | 整轮耗时 |
|---|---|---|---|---|
| A | 5000 | 3705（74%） | 前 2500 | 182s |
| B | 4000 | 3143（79%） | 前 800 | 182s |
| C | 1121 | 516（46%） | 全部 516 | 38s |
| D | 600 | 293（49%） | 全部 293 | 60s |

TCP 预筛这一段**确实不慢**（A 轮 5000 条 `15:42:48 → :53`，约 5 秒；B 轮 4000 条约 29 秒），
但那是**一段**，不是整轮：
之后还有 trace（B 轮 3143 存活 → 53 有效）、TTFB 多轮采样（53 → 保留 48）、下载测速（取前 19）、
记忆库更新。**整轮是几十秒到三分钟**——原稿写"3000 条约 6 秒"，相当于拿最快的一小段
去代表全程，并且把候选数也换成了另一处的 `sample_limit`（实测那几轮是 600–5000 条）。

原版比我们走得宽，靠的不是某一步的巧劲，是三件事叠起来：**并发数按轮实测反馈自动调**
（日志里 `并发度反馈调节为: 178 (原: 200)`、`TTFB_POOL_LIMIT 19 (原: 20)`——默认值只是起点，
实际值每轮都在动）、**阈值与池大小自适应放宽**（TTFB 阈值 1.5→1.47s、`PID 建议下一轮
TCP_ALIVE_LIMIT 调整为: 323`）、以及**记忆库**（把上轮实测喂回本轮，`记忆库: 0 条记录` 起，
同一次会话内涨到 5000 条）。

我们的取舍写死在两层配额里：便宜层替昂贵层挡掉死 IP（500 并发 / 900ms 超时，默认值见上表），
候选上限 1200 / 原始池 2400 封顶。这一步我们最初漏掉过——当时每个死 IP 都要付 3 次完整超时，
池子只能压到 80 条，探测预算全花在"确认某些 IP 是死的"上；补上预筛后才有现在这个漏斗。
但**改参数 ≠ 补自适应**：原版和我们真正的差，是"数字自己会动"，不是"参数不够多"。

**倍数按实测存活率定，不拍脑袋**：原版真机日志里存活率是 **46%–79%**（上表四轮依次 74% / 79% / 46% / 49%，
源是精选列表 bestcf / zip.cm，不是随机 CIDR）；另一次真机样本 6397 个候选存活 4566 个（**71.4%**，
2026-09-29，用户提供的原始日志）。存活率这么高时，原始池只需略大于昂贵层（×2）：
把预算花在"多测几个**活**节点"，而不是"多确认几个死 IP"。

`runs.jsonl` 每轮都记了 `candidates_raw` / `tcp_alive` / `stage_s`（分阶段耗时），
可以直接核对"时间花在哪一段"。下载测速只测延迟最优的 60 个候选，单次最多 6MB（早停 80 Mbps）。

**为什么地区解析要排在 TTFB 之前**：黑名单地区（默认 `RU/KP/CN/HK`）的候选应当**在花采样钱之前**被丢掉。
顺序反了的代价是实测出来的 —— 一轮 445 次 TTFB 探测里 **229 次（51%）** 打在随后就被丢弃的地区上，
而 trace 只要一次几百字节的 GET。所以现在先 trace、再过滤、再采样。

### 数据与记忆库

**跨轮记忆库**（默认开）：应用在 `files/cfoptimizer/memory.json` 里记住每个 IP 的历史表现 ——
成功率、最近得分、连续失败次数，以及**分时段**的历史得分。置信度公式与原版 `cf_memory.py` 一致
（`avg_score × 衰减系数 × 成功率 × 新鲜度`，当前时段有历史得分时最多再 +20%），字段名逐一对齐
（snake_case）—— 原版脚本养出来的记忆库可以直接导入，反过来也成立，两个方向都不需要转换。

**运行历史与导出**：每轮优选都会落盘（app 私有目录 `files/cfoptimizer/`）：

| 文件 | 内容 |
|---|---|
| `candidates.csv` | 最近一轮全部候选的实测指标（中文表头，`analyze_csv.py` 可直接读）|
| `runs.jsonl` | 每轮一行摘要：原始池 / TCP 存活 / 探测 / 入选数、分阶段耗时 `stage_s`、是否上传、记忆库规模（保留最近 200 轮）|
| `entry_ip_<日期>.json` | 本轮入选结果（原版 `Output/entry_ip_*.json` 同名式）。推送行格式 `IP:port#国家`（如 `141.164.35.4:443#KR`）——2026-09-29 起去掉 ISP 标签后缀：四级回退任何一级失败都会污染输出（实测出过 `#TW-__DOCTYPE_html_`），且单机场景国家已够 |
| `memory.json` | 跨轮记忆库 |

设置页 **CF 优选 → 导出运行数据** 会把它们复制到**下载目录**下的 `Download/CF优选/`：
不需要任何存储权限，用系统文件管理器就能打开（Android 10 回落 `MediaStore`；
更老的系统回落应用专属目录 `Android/data/<applicationId>/files/cfoptimizer/`）。
「分享运行数据」则直接把这几个文件交给系统分享面板 —— 用来核对「第 1 轮 vs 第 N 轮」是不是真的越跑越准。

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
