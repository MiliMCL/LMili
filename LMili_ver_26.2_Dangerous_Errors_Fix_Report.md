# LMili ver/26.2 危险错误修复报告

> 审计目标：`MiliMCL/LMili` `ver/26.2`
>
> 目标：交给 AI 逐项修复。
>
> 重要原则：**不要为了让测试通过而绕过 Folia 的线程检查；必须修复真正的 Region ownership / scheduler 生命周期问题。**

---

## 0. 修复优先级

| ID | 风险 | 模块 | 优先级 |
|---|---|---|---|
| RISK-01 | TickThread 身份与 Region ownership 可能脱钩 | `MiliTickThread` / Scheduler / Region | P0 |
| RISK-02 | `submit()` 与 `shutdown()` 存在 TOCTOU 竞态 | Scheduler | P0 |
| RISK-03 | Work stealing 可能破坏 Region 串行执行保证 | Coordinator / Worker / Region | P0 |
| RISK-04 | Batch submit 非原子 | Scheduler | P1 |
| RISK-05 | delayed scheduler 与 shutdown 存在竞态 | Scheduler | P1 |
| RISK-06 | `Class.forName()` 扫描可能执行非预期静态初始化 | ClassLoadUtil / Plugin | P1 |
| RISK-07 | Class scanning 异常可能中断整个初始化流程 | ClassLoadUtil | P2 |

---

# RISK-01：MiliTickThread 身份与 Region ownership 脱钩

## 风险等级

**P0 / Critical**

## 涉及文件

重点检查：

```text
lmili-server/src/main/java/fun/bm/mili/lmili/thread/scheduler/MiliSchedulerBuilder.java
lmili-server/src/main/java/fun/bm/mili/lmili/thread/scheduler/MiliSchedulerImpl.java
```

以及：

```text
MiliTickThread
SchedulerWorker
WorkStealingCoordinator
RegionState
ExecutionToken
RegionTickDispatcher
RegionTask
```

## 当前问题

当前 Builder 支持：

```java
boolean tickThreads = false;
```

并允许：

```java
if (config.tickThreads) {
    workerThreads[i] = new MiliTickThread(...);
}
```

设计目标是让 Folia 的：

```java
TickThread.isTickThreadFor(...)
```

正确识别 worker。

但是：

> **“当前线程是 TickThread”不能等价于“当前线程拥有该 Region”。**

如果 Worker A 获取 Region A 的任务后，WorkStealing 又允许 Worker B 获取 Region A 的后续任务，那么只要 Worker B 是 `MiliTickThread`，部分线程检查可能仍然通过。

最终可能出现：

```text
Region A
  ↓
Task A
  ↓
Worker 1

随后：

Region A
  ↓
Task B
  ↓
Worker 2
```

如果 Region ownership 没有转移/验证，就可能发生：

- 同一个 Region 同时 Tick
- Entity 并发修改
- Chunk 并发修改
- World 状态竞争
- Bukkit/Folia API 线程安全假设失效
- 偶发崩服
- 数据状态不一致
- 极难复现的 race condition

## 必须实现的修复

建立明确的：

```text
Region -> Owner / ExecutionToken -> Worker
```

关系。

每次 Region task 执行前必须验证：

```text
当前 Worker
    ↓
持有 Region ExecutionToken
    ↓
token.regionId == task.regionId
    ↓
token 未失效
    ↓
Region 当前没有其他执行者
```

只有全部成立才能执行任务。

## 推荐模型

```text
RegionState
 ├─ regionId
 ├─ ownerWorker
 ├─ executionToken
 ├─ running
 └─ generation
```

Worker 执行：

```text
claim(region)
   ↓
CAS / lock
   ↓
获得 ExecutionToken
   ↓
执行 Region Task
   ↓
release(region)
```

严禁：

```text
Worker 1 正在执行 Region A
        ↓
Worker 2 又直接执行 Region A
```

## Work stealing 修复要求

Work stealing 可以偷：

```text
Region B
```

但不能在：

```text
Region A 正在运行
```

时偷：

```text
Region A 的可并发执行权
```

因此建议采用：

```text
Ready Region Queue
       ↓
claim Region
       ↓
Region-local task queue
       ↓
执行该 Region 的任务
       ↓
Region release
       ↓
重新进入 ready queue
```

而不是简单地：

```text
global task queue
       ↓
任意 worker
       ↓
任意 Region task
```

## 验收标准

必须增加测试：

### Test 1：同 Region 串行

提交 10000 个相同 Region 的 task：

```text
regionId = 1
```

要求：

```text
maxConcurrent(region=1) == 1
```

### Test 2：不同 Region 并行

提交：

```text
Region 1
Region 2
```

要求可以同时执行。

### Test 3：Work stealing

让 Worker 1 / Worker 2 高频 steal。

要求：

```text
同一 Region 永远不会同时由两个 Worker 执行。
```

### Test 4：Ownership violation

人为构造错误 token：

```text
task.regionId = 1
token.regionId = 2
```

必须拒绝执行。

---

# RISK-02：submit() 与 shutdown() TOCTOU 竞态

## 风险等级

**P0 / Critical**

## 涉及文件

```text
MiliSchedulerImpl.java
SchedulerLifecycle.java
WorkStealingCoordinator.java
```

## 当前模式

当前逻辑类似：

```java
if (lifecycle.isShuttingDown()) {
    return cancelled;
}

...

workStealingCoordinator.submit(task);
```

问题是：

```text
Thread A                         Thread B

submit()
  |
  | isShuttingDown == false
  |
                                 shutdown()
                                   |
                                   ↓
                              beginShutdown()
                                   |
                                   ↓
                              stop workers
  |
  ↓
coordinator.submit(task)
```

检查和提交不是原子的。

## 可能结果

任务进入：

```text
已关闭/正在关闭的 Coordinator
```

最终：

- Task 永远不执行
- TaskHandle 永远不完成
- pending task 不归零
- Region barrier 卡住
- shutdown 状态异常
- tick 卡死

## 修复要求

必须让：

```text
accept task
+
shutdown transition
```

具有严格的同步关系。

推荐：

```text
RUNNING
   ↓
QUIESCING
   ↓
CLOSED
```

只有：

```text
RUNNING
```

允许提交。

提交必须与状态检查形成原子协议。

推荐方案：

```java
synchronized (lifecycle) {
    if (!lifecycle.acceptingTasks()) {
        cancel(handle);
        return;
    }

    coordinator.submit(task);
}
```

或者使用：

```text
Atomic state + synchronized submit gate
```

但不能只做两个独立的 volatile/atomic 操作。

## 验收测试

持续并发：

```text
100 个 submit threads
1 个 shutdown thread
```

要求：

```text
任何 task：
    要么成功进入 scheduler
    要么立即 cancelled
```

禁止：

```text
task submitted after scheduler closed
```

禁止：

```text
TaskHandle 永久 pending
```

---

# RISK-03：Work stealing 破坏 Region 串行执行

## 风险等级

**P0 / Critical**

这是 RISK-01 的调度层版本，必须和 RISK-01 一起修。

## 核心要求

WorkStealingCoordinator 必须理解：

```text
Region ownership
```

不能只理解：

```text
Runnable / Task
```

## 错误架构

```text
GlobalQueue
   ↓
Worker 1 → Region A
Worker 2 → Region A
```

## 推荐架构

```text
              Region Scheduler
                    │
        ┌───────────┼───────────┐
        ↓           ↓           ↓
    Region A    Region B    Region C
        │           │           │
     owner=1     owner=2     owner=1
```

Worker 只能 claim 一个未被其他 Worker 执行的 Region。

## 必须检查

每个 Region task 执行前：

```java
assert ownership.isOwnedBy(currentWorker, task.regionId());
```

失败时：

```java
throw IllegalStateException(...)
```

不要：

```java
// 错误
if (!owner) {
    // 忽略
}
```

也不要通过伪造 Thread 类型绕过检查。

---

# RISK-04：submitBatch() 非原子

## 风险等级

**P1 / High**

当前 Batch 类似：

```java
for (RegionTask task : tasks) {
    handles.add(submit(task));
}
```

因此可能：

```text
Task 1 → submitted
Task 2 → submitted
Task 3 → submitted
shutdown()
Task 4 → cancelled
Task 5 → cancelled
```

导致一个 Batch：

```text
部分成功
+
部分取消
```

## 修复方案

至少提供明确语义。

### 推荐

Batch submit 必须先完成 admission：

```text
validate all tasks
      ↓
reserve batch
      ↓
atomically enter scheduler
      ↓
submit all
```

如果无法实现真正原子提交，则 API 必须明确：

```text
BatchHandle = partial completion allowed
```

但 Region Tick barrier 不应该依赖这种弱语义。

## Tick 场景要求

如果 Batch 被用于一个 Tick barrier：

```text
A + B + C
```

则：

```text
A 完成
B 完成
C 取消
```

不能让 barrier 被误判为：

```text
Batch completed
```

必须区分：

```text
SUCCESS
PARTIAL_FAILURE
CANCELLED
FAILED
```

---

# RISK-05：delayed scheduler 与 shutdown 竞态

## 风险等级

**P1 / High**

当前结构：

```text
scheduleDelayed()
   ↓
检查 lifecycle
   ↓
getDelayedScheduler()
   ↓
scheduler.schedule()
```

同时 shutdown：

```text
shutdown()
   ↓
delayedScheduler.shutdown()
```

存在：

```text
check
   ↓
shutdown
   ↓
schedule
```

竞态。

## 修复要求

`getDelayedScheduler()` 与 scheduler admission 必须受 shutdown gate 保护。

要求：

```text
shutdown 后：
scheduleDelayed() 必须立即返回 cancelled handle
```

而不是：

```text
RejectedExecutionException
```

## 额外要求

如果：

```java
scheduler.schedule(...)
```

抛出：

```java
RejectedExecutionException
```

必须：

```java
handle.cancel();
```

或：

```java
handle.completeExceptionally(...)
```

绝不能留下：

```text
PENDING
```

---

# RISK-06：ClassLoadUtil 的 Class.forName 风险

## 风险等级

**P1 / High**

涉及：

```text
ClassLoadUtil.java
```

当前逻辑会对扫描到的 class 调用：

```java
Class.forName(...)
```

## 风险

`Class.forName()` 可能触发类初始化。

也就是说扫描 class 的过程可能执行：

```java
static {
    ...
}
```

因此：

```text
扫描 class
    ↓
Class.forName
    ↓
static initializer
    ↓
任意初始化逻辑
```

如果扫描范围可以受到插件/配置影响，风险更高。

## 修复要求

如果目标只是获取 Class：

```java
Class.forName(name, false, classLoader);
```

第二参数必须是：

```text
initialize = false
```

这样避免扫描阶段执行 static initializer。

## 进一步要求

限制扫描范围：

```text
allowed package
allowed ClassLoader
allowed module
```

不要扫描：

```text
整个 classpath
```

## 插件隔离

插件扫描必须使用：

```text
plugin ClassLoader
```

而不是无条件：

```text
system/application ClassLoader
```

---

# RISK-07：Class scanning 异常不应直接击穿整个初始化

## 风险等级

**P2 / Medium**

如果单个 class 出现：

```text
ClassNotFoundException
LinkageError
NoClassDefFoundError
ExceptionInInitializerError
SecurityException
```

不应该让整个扫描过程毫无上下文地：

```java
throw new RuntimeException(e);
```

## 推荐

记录：

```text
class name
classloader
plugin id
exception
```

然后根据扫描用途决定：

```text
skip
```

或者：

```text
fail-fast
```

如果属于核心必需 class，可以 fail-fast。

如果属于插件扫描，应隔离到该插件。

---

# 8. Scheduler 生命周期必须统一

最终建议统一状态机：

```text
                 ┌──────────────┐
                 │   RUNNING    │
                 └──────┬───────┘
                        │
                   beginShutdown
                        │
                        ▼
                 ┌──────────────┐
                 │  QUIESCING   │
                 └──────┬───────┘
                        │
               drain / cancel
                        │
                        ▼
                 ┌──────────────┐
                 │    CLOSED    │
                 └──────────────┘
```

规则：

### RUNNING

允许：

```text
submit
submitBatch
scheduleDelayed
claimRegion
```

### QUIESCING

禁止：

```text
新任务提交
新 Region claim
新 delayed task
```

允许：

```text
已有任务完成
```

### CLOSED

所有操作：

```text
立即拒绝/取消
```

---

# 9. TaskHandle 状态机

建议统一：

```text
PENDING
  ├── RUNNING
  │     ├── COMPLETED
  │     └── FAILED
  │
  └── CANCELLED
```

要求：

```text
PENDING → COMPLETED
PENDING → FAILED
PENDING → CANCELLED
PENDING → RUNNING
```

只能发生一次。

禁止：

```text
COMPLETED → CANCELLED
FAILED → COMPLETED
RUNNING → PENDING
```

使用：

```text
CAS
```

保证并发安全。

---

# 10. 必须增加的并发回归测试

## Test-01 同 Region 绝对串行

```text
10000 tasks
regionId = 1
workers >= 8
```

断言：

```text
maxConcurrent(region 1) == 1
```

## Test-02 不同 Region 并行

```text
Region 1
Region 2
Region 3
Region 4
```

允许：

```text
maxConcurrent > 1
```

## Test-03 Work stealing ownership

高频：

```text
submit
steal
reschedule
release
```

运行至少：

```text
1,000,000 operations
```

不得出现：

```text
same region concurrently executed
```

## Test-04 shutdown/submit race

并发：

```text
submit × 100
shutdown × 1
```

循环：

```text
10000 rounds
```

断言：

```text
no stuck handles
no task after CLOSED
no uncaught scheduler exception
```

## Test-05 delayed/shutdown race

并发：

```text
scheduleDelayed
shutdown
cancel
```

断言：

```text
no PENDING handle forever
```

## Test-06 ownership token mismatch

构造：

```text
task.regionId = 1
token.regionId = 2
```

必须：

```text
reject
```

## Test-07 TickThread spoof prevention

禁止仅因为：

```text
currentThread instanceof MiliTickThread
```

就认为：

```text
currentThread owns region
```

必须同时满足：

```text
TickThread identity
+
Region ownership
```

---

# 11. 修复顺序

AI 必须严格按照以下顺序执行：

```text
1. Region ownership / ExecutionToken
        ↓
2. WorkStealingCoordinator
        ↓
3. SchedulerWorker
        ↓
4. MiliTickThread
        ↓
5. SchedulerLifecycle
        ↓
6. submit/shutdown admission
        ↓
7. delayed scheduler
        ↓
8. Batch semantics
        ↓
9. ClassLoadUtil
        ↓
10. regression tests
```

不要先修改测试。

不要通过：

```text
sleep
retry
catch Exception
ignore ownership failure
```

掩盖问题。

---

# 12. 修复完成后的硬性验收标准

修复后必须满足：

```text
[PASS] Same Region never executes concurrently
[PASS] Different Regions can execute concurrently
[PASS] Worker stealing cannot steal Region ownership
[PASS] TickThread cannot spoof Region ownership
[PASS] submit/shutdown race has no lost task
[PASS] no TaskHandle stuck in PENDING
[PASS] delayed scheduler shutdown race safe
[PASS] Batch semantics explicit and tested
[PASS] Class scanning does not initialize arbitrary classes
[PASS] plugin class loading remains isolated
```

最终目标：

```text
                   LMili Scheduler
                         │
                ┌────────┴────────┐
                │                 │
          Region ownership    Task execution
                │                 │
             Token/CAS       Work stealing
                │                 │
                └────────┬────────┘
                         │
                  MiliTickThread
                         │
                   Folia checks
                         │
                    Minecraft
```

核心原则：

> **Work stealing 负责提高 CPU 利用率，Region ownership 负责保证 Minecraft 状态安全。两者绝不能混为一谈。**

---

# 13. 当前审计结论

目前没有发现明显的：

```text
Runtime.exec()
ProcessBuilder
```

类型的直接后门式代码。

当前最高风险并不是传统 RCE，而是：

```text
并行 Tick
+
Work stealing
+
TickThread
+
Region ownership
```

之间的安全边界。

**如果这条边界没有严格实现，LMili 在高并发玩家/高实体/高 Region 数量下可能出现低概率但严重的世界状态竞争。**

因此：

> **RISK-01、RISK-02、RISK-03 必须在任何性能优化之前修复。**

---
