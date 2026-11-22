# Worker 指标与任务分配

本文档描述任务派发阶段「按资源要求筛选 Worker、排序、截断、选择主节点」的最终行为边界，以及
由此引入的日志、配置与实例结果。实现位于：

- `powerjob-common`：`SystemMetrics`（资源判定与评分）、`SystemInstanceResult`（结果文案）、
  `SaveJobInfoRequest#valid`（保存入口校验）
- `powerjob-server-remote`：`WorkerClusterQueryService`（过滤、排序、截断、汇总日志）、
  `SystemMetricsWorkerFilter`、`DisconnectedWorkerFilter`、`DesignatedWorkerFilter`
- `powerjob-server-core`：`DispatchService`（空候选原因归类）

---

## 1. 资源要求的语义

任务通过 `minCpuCores`、`minMemorySpace`、`minDiskSpace` 声明资源要求，`0` 表示不限，允许正的小数
（例如 `0.5` 核、`0.5 GB`）。负值在任务保存入口 `SaveJobInfoRequest#valid()` 直接拒绝
（`minCpuCores`/`minMemorySpace`/`minDiskSpace`/`maxWorkerCount`），不会进入过滤器。

### 1.1 统一边界：剩余量 >= 要求即满足

三种资源使用完全相同的规则：

| 资源 | 剩余量 | 通过条件 |
| --- | --- | --- |
| CPU | `cpuProcessors - cpuLoad` | 剩余核数 >= `minCpuCores` |
| 内存 | `jvmMaxMemory - jvmUsedMemory` | 剩余 JVM 堆 >= `minMemorySpace` |
| 磁盘 | `diskTotal - diskUsed` | 剩余磁盘 >= `minDiskSpace` |

恰等于要求时通过；任一资源略小于要求即拒绝（多个资源同时不满足时按位汇总原因）。

### 1.2 CPU 的特殊情况

- `minCpuCores <= 0`：视为不限，不参与判定。
- `cpuLoad <= 0`：CPU 负载不可用或为空闲，CPU 要求无法用于拒绝该 Worker，直接通过。
  - `cpuLoad == 0`：负载为空闲。
  - `cpuLoad < 0`：无法取得负载。Windows 上 `OperatingSystemMXBean#getSystemLoadAverage()`
    返回 `-1`，此路径即为该场景。可通过在该平台的 Worker 上执行
    `SystemInfoUtils#getSystemMetrics()` 打印 `cpuLoad` 核实实际值。

> 兼容性说明（**行为变化**）：旧实现 CPU 使用 `minCPUCores < (cpuProcessors - cpuLoad)`，恰等于要求
> 时被**拒绝**，与内存/磁盘的「小于才拒绝」不一致。本次统一为 `>=` 即通过。
>
> - 影响：8 核、`cpuLoad=2.5`（剩余 5.5 核）、要求 5.5 核的 Worker，由「拒绝」变为「通过」；要求
>   5.6 核仍拒绝。仅影响「恰等于」这一测量点，不会放宽到明显不满足的机器。
> - 回归测试：`SystemMetricsTest#cpuBoundaryEqualsRequirementPasses`、
>   `SystemMetricsWorkerFilterTest#exactlyMeetingEveryRequirementPasses`。

### 1.3 内存是 JVM 剩余堆，不是机器可用内存

`minMemorySpace` 对比的是 `jvmMaxMemory - jvmUsedMemory`（Worker 侧 `-Xmx` 下的剩余堆，单位 GB），
**不是**物理机剩余内存。该语义已写入 `SystemMetrics`、`SaveJobInfoRequest`、`JobInfoDO`、
`JobInfoDTO`、`JobInfoVO` 的字段注释。评分同样使用该值。

---

## 2. 评分与排序

### 2.1 评分只使用 CPU 与 JVM 内存

`SystemMetrics#calculateScore()` = `剩余JVM堆(GB) × 2 + 剩余核数`，向上取整为 `int`（原逻辑保留）。

- 磁盘**不进入评分**，仅作为过滤硬条件。理由：磁盘剩余在心跳间隔内几乎不变，纳入评分只会增加噪声，
  对「把任务摊到不同 Worker」帮助很小。
- `cpuLoad < 0`（无法取得）时 `cpuProcessors - cpuLoad > cpuProcessors`，评分中把该项钳制为 `1`，
  避免未知 CPU 状态被给予虚高分或 0 分。
- `score > 0` 时直接复用缓存值（Worker 侧已计算并随心跳上报），Server 不重复计算。

### 2.2 确定性排序（同一候选集合可复现）

`WorkerClusterQueryService` 在过滤之后、`maxWorkerCount` 截断之前，按下述优先级排序：

1. **指定机器优先**：地址或 tag 命中 `designatedWorkers` 的 Worker 排在最前；
2. **快照新鲜优先**：指标快照年龄超过阈值的 Worker **后置**（见 §3）；
3. **评分降序**；
4. **地址字典序升序**打破平局（地址为 `null` 时排最后）。

因此同一候选集合的输出与 `ClusterStatusHolder` 中哈希表的遍历顺序无关，可复现。`getAllWorkers(appId)`
也改为「评分降序 + 地址字典序」的稳定排序。

> 截断在排序之后进行，故指定地址不会因评分较低而先被 `maxWorkerCount` 截掉。

---

## 3. 指标快照年龄

`DisconnectedWorkerFilter` 只按心跳超时（`WORKER_TIMEOUT_MS = 60000`）剔除 Worker，因此一份 55 秒前的
快照虽然未超时，但可能已过时。

- 配置项：`oms.worker.system-metrics.max-age-ms`，单位毫秒。
- **默认不启用**：未配置或 `<= 0` 时年龄不影响排序（`application.properties` 中给注释示例）。
- 建议值：心跳超时的一半，即 `30000`。
- 生效方式：快照年龄（`now - lastActiveTime`）超过阈值的 Worker 在排序时**稳定后置到队尾**，
  **不剔除、不改写评分**。这样既不会让过时快照抢占新快照的名额，也避免短暂心跳抖动直接清空候选。
  `lastActiveTime <= 0`（未知年龄）按过旧处理。

---

## 4. 派发汇总日志

过滤日志由「每台 Worker 一条 info（`SystemMetricsWorkerFilter`）」改为「每次派发一条 info 汇总」，
被滤地址样本改为 debug：

```
[WorkerClusterQueryService] job[{jobId}] app[{appId}] dispatch candidate summary:
online={总数}, filtered={被滤数}, available={可用数}, returned={截断后返回数},
filteredByFilter={ClassName=count, ...}, cpuRejected={n}, memoryRejected={n},
diskRejected={n}, maxWorkerCount={n}
```

- `filteredByFilter` 按**过滤器类名**计数，保留 `WorkerFilter` 接口与已有实现不变，便于扩展（例如
  新增 GPU 过滤器时自动出现在汇总中）。
- **归因按过滤器执行顺序归属**：一台 Worker 只计入第一个返回 `true` 的过滤器；CPU/内存/磁盘原因
  仅对归因到 `SystemMetricsWorkerFilter` 的 Worker 统计，且可同时计入多个资源。
- 被滤地址样本（最多 5 个）：
  `[WorkerClusterQueryService] job[{jobId}] first {n} filtered workers: [addr, ...]`（debug）。
- `SystemMetricsWorkerFilter` 的逐台明细降为 debug，格式：
  `[Job-{id}] filter worker[{addr}] because reasons={bitmask} do not meet the requirements, metrics={...}`
  （`reasons` 为 `UNAVAILABLE_CPU=1` / `UNAVAILABLE_MEMORY=2` / `UNAVAILABLE_DISK=4` 的按位或）。
- `DisconnectedWorkerFilter` 的超时逐台明细同样降为 debug（汇总里计入 `DisconnectedWorkerFilter=n`），
  按类名计数因此可以覆盖所有内置/自定义过滤器。

### 4.1 可扩展点

`SystemMetrics#extra` 与 `WorkerFilter` 仍可用于 GPU 等自定义指标。过滤器执行顺序影响上面的归因计数，
但排序/截断/选择器逻辑与过滤器实现无关。

---

## 5. 候选为空时的实例结果

`DispatchService` 对三种「没有可派发 Worker」的场景给出可区分的结果常量（`SystemInstanceResult`）：

| 场景 | 常量 | 文案 | 实例状态 | 是否失败告警 |
| --- | --- | --- | --- | --- |
| 应用下没有任何在线 Worker | `NO_ONLINE_WORKER` | `no online worker` | FAILED | 是 |
| 有在线 Worker 但全部不满足资源/指定要求 | `NO_WORKER_MEETS_REQUIREMENTS` | `no worker meets the job requirements` | FAILED | 是 |
| 有满足要求的 Worker 但全部超载 | `ALL_WORKERS_OVERLOAD` | `all workers are overloaded` | 保持 WAITING_DISPATCH（本轮跳过，稍后重试） | 否 |

- 与既有 `NO_WORKER_AVAILABLE`（`no worker available`，文案未改动，供外部/历史逻辑继续引用）保持兼容：
  新增两个 FAILED 常量替代原先该常量在「无候选」分支的写法，实例 `result` 字段的值因此发生变化，
  按 `result` 文本做匹配的告警/看板需相应调整。
- 「全部超载」沿用原语义：取消本次派发并通过 `overloadOptional` 通知调用方，不写 FAILED、不触发失败告警；
  `ALL_WORKERS_OVERLOAD` 作为日志中的稳定标记。
- 判定方法：`WorkerClusterQueryService.AvailableWorkers#isNoOnlineWorker()` /
  `isAllFilteredOut()`；`geAvailableWorkers` 旧签名保留，内部委托到 `geAvailableWorkersWithDetail`。

---

## 6. 选择器与派发策略

`DispatchStrategy` 的健康优先（`HealthFirstTaskTrackerSelector`）、随机（`RandomTaskTrackerSelector`）、
指定（`SpecifyTaskTrackerSelector`）均在**过滤 + 排序 + 截断之后**执行：

- `HEALTH_FIRST`：在已排序列表上再按评分取最优；对空候选抛出带有 jobId 的 `IllegalStateException`
  作为防御（上游已保证非空）。
- `RANDOM` / `SPECIFY`：语义不变，`dispatchStrategyConfig` 的解析仍由各自选择器负责，不被本次
  排序/截断规则改变。

---

## 7. 测试覆盖

均为单元测试，直接构造 `SystemMetrics`、`WorkerInfo`、`JobInfoDO`，不连接数据库：

| 测试 | 覆盖点 |
| --- | --- |
| `SystemMetricsTest` | 三资源恰等/略小/略大；`cpuLoad` 为 0/负值；`-x` 要求；评分只含 CPU+JVM 内存、缓存与钳制 |
| `SaveJobInfoRequestResourceValidationTest` | `0.5` 允许、`0` 不限、负值与负 `maxWorkerCount` 拒绝 |
| `SystemMetricsWorkerFilterTest` | 过滤边界、原因位掩码、缺失指标不因资源被拒 |
| `WorkerClusterQueryServiceTest` | 评分降序+地址平局、输入顺序无关、`maxWorkerCount` 小于/等于/大于候选数、指定地址不被截掉、过旧快照后置且不剔除、空候选三态 |
| `HealthFirstTaskTrackerSelectorTest` | 取最高分、空候选防御 |
| `DispatchServiceNoWorkerReasonTest` | 无在线 Worker / 全部不满足 / 全部超载三种结果的写入与告警差异 |
| `SystemInfoUtilsCpuLoadTest`（worker） | 上报的 `cpuLoad` 确为原始 load average（忙核数，非百分比）；未知平台仍为负值 |
