# LMili ver/26.2 第二轮危险错误修复报告

> 审计基线：最新 `ver/26.2`。目标：交给 AI 继续修复。
>
> 核心审计链：
> `RegionTickDispatcher → FoliaRegionNodeScheduler → SameRegionNodeScheduler → CompositeNodeScheduler → MiliScheduler → WorkStealingCoordinator → SchedulerWorker → Region Ownership → MiliTickThread → Folia TickThread`

## 1. 当前结论

上一轮 `ClassLoadUtil` 的 RISK-06 / RISK-07 已基本正确修复：

```java
Class.forName(className, false, loader);
```

并已正确拆分 `LinkageError` 相关异常处理。

当前重点仍是：

- **P0：Region Ownership 是否真正绑定 Worker**
- **P0：Work Stealing 是否可能跨 Region 抢夺执行权**
- **P0：submit/shutdown TOCTOU**
- **P1：Folia Scheduler 与 Mili Scheduler 是否存在双重 ownership**
- **P1：Region generation / 旧任务复活**
- **P1：delayed scheduler 生命周期**
- **P1：Batch 一致性**
- **P1：Region handle registry / DAG 生命周期**

---

# RISK-08：RegionScheduleHandle 不等于 Region Ownership

## 等级：P0 / Critical

`RegionScheduleHandle` 是调度入口还是实际 ownership 必须明确。

禁止：

```text
regionId → RegionScheduleHandle → 任意 Worker → Region Task
```

必须存在明确的：

```text
Region
 ├─ regionId
 ├─ owner
 ├─ executionToken
 ├─ running
 └─ generation
```

执行前必须验证：

```text
task.regionId == token.regionId
token.owner == currentWorker
token 有效
Region 没有其他执行者
```

推荐流程：

```text
Task
 ↓
claim Region
 ↓
获得唯一 ExecutionToken
 ↓
验证 Worker
 ↓
execute
 ↓
release
```

---

# RISK-09：Work Stealing 不得偷走 Region Ownership

## 等级：P0 / Critical

Work Stealing 可以偷：

```text
Ready Region
```

不能偷：

```text
正在执行的 Region 的执行权
```

错误：

```text
Global Task Queue
 ↓
Worker 1 → Region A
Worker 2 → Region A
```

正确：

```text
Region A → owner Worker 1
Region B → owner Worker 2
```

必须审计：

```text
WorkStealingCoordinator
SchedulerWorker
TaskHandle
RegionTask
RegionState
ExecutionToken
```

所有 `steal/poll/take/execute/run/submit/reschedule` 路径都必须重新确认 ownership。

---

# RISK-10：MiliTickThread 不能单独代表 Region 所有权

## 等级：P0 / Critical

禁止：

```java
if (Thread.currentThread() instanceof MiliTickThread) {
    execute(regionTask);
}
```

`MiliTickThread` 只能证明：

```text
当前线程是合法 Tick Worker
```

不能证明：

```text
当前线程拥有 Region A
```

正确条件必须同时包括：

```text
TickThread identity
+
Region ownership
+
ExecutionToken
+
generation
```

---

# RISK-11：Folia 与 Mili 必须只有一个最终 Ownership 真相源

## 等级：P1 / High

禁止出现：

```text
Folia：Region A → Worker 1
Mili： Region A → Worker 2
```

必须建立单一 ownership authority。

推荐：

```text
Region Ownership Authority
        ↓
ExecutionToken
        ↓
Mili Worker
        ↓
Folia TickThread validation
```

所有执行路径都必须查询同一 ownership 状态。

---

# RISK-12：Region generation 防止旧任务复活

## 等级：P1 / High

Region 状态变化后，旧任务不能继续执行。

例如：

```text
Region A generation = 10
旧 Task generation = 9
```

必须：

```text
reject / cancel / reschedule
```

推荐：

```text
RegionState.regionId
RegionState.generation

Task.regionId
Task.generation
```

执行前必须：

```text
task.generation == currentRegion.generation
```

---

# RISK-13：submit() 与 shutdown() 必须原子化

## 等级：P0 / Critical

禁止：

```text
check RUNNING
    ↓
之后再 submit
```

因为：

```text
Thread A             Thread B
check RUNNING
                     shutdown()
                     CLOSED
submit()
```

推荐状态机：

```text
RUNNING
   ↓
QUIESCING
   ↓
CLOSED
```

只有 `RUNNING` 可以接受新任务。

submit admission 与 shutdown transition 必须形成同步协议。

验收：

```text
100 submit threads
1 shutdown thread
10000 rounds
```

要求：

```text
no lost task
no stuck handle
no task accepted after CLOSED
```

---

# RISK-14：shutdown 顺序必须统一

## 等级：P0 / Critical

推荐：

```text
1. stop accepting new tasks
2. stop new Region claims
3. cancel/reject delayed tasks
4. drain running tasks
5. release Region ownership
6. stop workers
7. shutdown coordinator
8. mark scheduler CLOSED
9. clear holder/reference
```

禁止先停止 worker、后禁止 submit。

---

# RISK-15：TaskHandle 不得永久 PENDING

## 等级：P1 / High

所有路径必须保证：

```text
PENDING → RUNNING → COMPLETED / FAILED
```

或：

```text
PENDING → CANCELLED
```

禁止 worker shutdown 后 task 丢失导致：

```text
PENDING forever
```

增加 bounded-time watchdog 测试。

---

# RISK-16：delayed scheduler shutdown race

## 等级：P1 / High

并发：

```text
scheduleDelayed
cancel
shutdown
```

最终 handle 必须进入：

```text
CANCELLED
```

或：

```text
FAILED
```

不能：

```text
PENDING forever
```

正常 API 不应出现未处理的 `RejectedExecutionException`。

---

# RISK-17：Batch Submit 语义必须明确

## 等级：P1 / High

如果：

```text
A → success
B → success
C → cancelled
D → cancelled
```

Batch 不能简单认为 `COMPLETED`。

至少区分：

```text
SUCCESS
PARTIAL_FAILURE
CANCELLED
FAILED
```

Tick barrier 只有所有必要任务完成才允许通过。

---

# RISK-18：Region Handle Registry 生命周期

## 等级：P1 / High

重点检查：

```text
register()
unregister()
region unload
region recreate
scheduler shutdown
```

避免：

```text
旧 Region A handle
        ↓
残留 registry
        ↓
新 Region A
        ↓
新旧 handle 混淆
```

推荐：

```text
regionId + generation
```

作为逻辑身份。

Region 生命周期结束必须清理 registry。

---

# RISK-19：Region Tick DAG 不得绕过 ownership

## 等级：P0 / Critical

存在：

```text
FoliaRegionNodeScheduler
SameRegionNodeScheduler
CompositeNodeScheduler
DAG
```

时，DAG dependency 不能成为绕过 Region ownership 的路径。

例如：

```text
Node A → Region 1
Node B → Region 1
A → B
```

B 仍必须正常 claim Region。

禁止：

```text
dependency satisfied → worker.execute(B)
```

而跳过 ownership。

---

# RISK-20：跨 Region DAG Handle 必须严格匹配

当前 `regionId → RegionScheduleHandle` 用于 routing 时，必须验证：

```text
Node.regionId == Handle.regionId
```

禁止：

```text
Node A = Region 1
Handle = Region 2
```

生产环境不能只依赖 Java `assert`，必须使用显式运行时检查。

---

# 3. 必须重新增加的回归测试

## TEST-01：同 Region 串行

```text
workers = 8
tasks = 10000
regionId = 1
```

断言：

```text
maxConcurrent(region=1) == 1
```

## TEST-02：不同 Region 并行

多个 Region 在足够 Worker 下必须允许真正并行。

## TEST-03：Work Stealing

至少进行高频 steal/reschedule 压力测试，断言：

```text
same Region never concurrently executed
```

## TEST-04：Ownership Token mismatch

```text
task.regionId = 1
token.regionId = 2
```

必须拒绝。

## TEST-05：Generation mismatch

```text
task.generation = 1
region.generation = 2
```

必须拒绝、取消或重新调度。

## TEST-06：TickThread spoof

创建 `MiliTickThread`，但不给 Region ownership，尝试执行 Region task。

必须拒绝。

## TEST-07：shutdown/submit race

```text
100 submitters
1 shutdown
10000 rounds
```

不得出现 stuck handle 或 CLOSED 后仍接受任务。

## TEST-08：Delayed shutdown

并发 schedule/cancel/shutdown，所有 handle 必须进入 terminal state。

## TEST-09：Registry lifecycle

```text
register A
unregister A
register A again
```

旧 handle 不能执行新 Region 的任务。

---

# 4. 强制禁止的伪修复

AI 修复时禁止：

```text
catch (Exception) {}
catch (Throwable) {}
sleep()
Thread.yield()
无限 retry
忽略 ownership violation
仅使用 instanceof MiliTickThread
仅使用 Java assert 保护生产 ownership
```

不得删除测试来让 CI 通过。

不得通过绕过 Folia 线程检查解决并发问题。

---

# 5. 推荐最终模型

```text
                  Region Scheduler
                         │
                         ▼
                  Region Ownership
                         │
                    ExecutionToken
                         │
                         ▼
                  Ready Region Queue
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
           Worker 1   Worker 2   Worker 3
              │          │          │
              ▼          ▼          ▼
           Region A   Region B   Region C
              │          │          │
              ▼          ▼          ▼
        Region-local tasks
              │
              ▼
         Minecraft tick
```

核心原则：

> **Work stealing 提高 CPU 利用率；Region ownership 保证 Minecraft 状态安全。二者绝不能混为一谈。**

---

# 6. 最终验收标准

必须全部满足：

```text
[PASS] Same Region never executes concurrently
[PASS] Different Regions can execute concurrently
[PASS] Worker stealing cannot break Region ownership
[PASS] MiliTickThread cannot spoof Region ownership
[PASS] ExecutionToken mismatch is rejected
[PASS] Generation mismatch is rejected
[PASS] Folia and Mili ownership have one source of truth
[PASS] submit/shutdown race has no lost tasks
[PASS] no TaskHandle stuck in PENDING
[PASS] delayed scheduler shutdown is safe
[PASS] Batch semantics are deterministic
[PASS] Region handle registry cleans up correctly
[PASS] DAG execution cannot bypass Region ownership
[PASS] ClassLoadUtil keeps initialize=false
[PASS] ClassLoadUtil isolates scanning errors
```

---

# 7. AI 执行顺序

严格按照：

```text
RISK-08
RISK-09
RISK-10
RISK-11
RISK-12
RISK-13
RISK-14
RISK-15
RISK-16
RISK-17
RISK-18
RISK-19
RISK-20
```

执行。

每项：

```text
1. 找到真实源码调用链
2. 修改最小必要范围
3. 增加回归测试
4. 编译整个项目
5. 运行测试
6. 检查新增 race
7. 不得删除测试
8. 最后再进行性能优化
```

最终顺序必须是：

```text
Correctness
    ↓
Concurrency Safety
    ↓
Lifecycle Safety
    ↓
Regression Tests
    ↓
Benchmark
    ↓
Optimization
```

**性能优化不得以牺牲 Region ownership 为代价。**
