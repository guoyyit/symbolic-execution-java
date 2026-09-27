# 本地符号执行与路径约束求解服务

一个基于 Spring Boot 的本地服务，对由**整数变量、赋值、条件分支、有界循环和断言**构成的小型程序做符号执行，
枚举可到达断言的路径、给出路径触发条件与一组满足赋值，并支持多个分析任务并发提交、超时/取消以及检查点中断恢复。

## 1. 支持的语言子集与语义

### 词法与程序结构

程序是一串语句，支持 `//` 行注释与 `/* ... */` 块注释。

```ebnf
program     := statement*
statement   := decl | assign | block | ifStmt | forStmt | assertStmt
decl        := 'int' IDENT ( '=' expr )? ';'
assign      := IDENT '=' expr ';'
block       := '{' statement* '}'
ifStmt      := 'if' '(' cond ')' block ( 'else' block )?
forStmt     := 'for' forInit ';' cond ';' forUpdate block
forInit     := ('int' IDENT '=' expr) | (IDENT '=' expr)
forUpdate   := IDENT '=' expr
assertStmt  := 'assert' '(' cond ')' ';'
expr        := term (('+' | '-') term)*
term        := unary ('*' unary)*
unary       := '-' unary | '+' unary | primary
primary     := INT_LIT | IDENT | '(' expr ')'
cond        := orExpr
orExpr      := andExpr ('||' andExpr)*
andExpr     := notExpr ('&&' notExpr)*
notExpr     := '!' atomCond | atomCond
atomCond    := '(' cond ')' | expr relOp expr
relOp       := '<' | '<=' | '>' | '>=' | '==' | '!='
```

### 语义要点

- **整数**：64 位有符号整数；仅支持**整数线性算术**。乘法只允许至少一个操作数为常量
  （例如 `3*x`、`x*2` 合法，`x*y` 被静态拒绝）。一元负号 `-x` 归一为 `0 - x`。
- **变量**：`int x;` 未初始化即引入一个**自由符号输入**；`int x = expr;` 用线性表达式初始化。
  变量为词法块作用域，重复声明、块外引用、给未定义变量赋值都会被拒绝。
- **赋值**：内部采用 SSA 版本化，重新赋值不改变历史路径条件的含义；最终变量值按满足模型计算。
- **分支**：`if` 的正、反两侧都会被探索，反侧约束为条件的否定。复合条件（`&&`、`||`、`!`）
  自动展开为析取范式（DNF），每个可行最小项产生一条路径。
- **断言**：`assert(c)` 在条件**不成立**的路径上报告 `ASSERTION_FAILED`（终端路径），
  在条件成立的路径上继续。两条侧都不可满足时该路径被剪枝。
- **循环**：只接受**有界 for 循环**，且静态要求：
  - 初始化一个计数器变量（`int i = 0` 或已声明变量 `i = 0`）；
  - 循环条件是**单个**比较（`<`、`<=`、`>`、`>=`），一侧为计数器；
  - 更新形如 `i = i + k` / `i = i - k`，`k` 为正的常量，且方向与比较一致；
  - 常量上下界若使循环体永不执行，直接拒绝。

  循环在运行时按次迭代展开，进入/退出两侧都做可满足性判断；除声明的界外，还有全局
  展开上限 `maxUnroll`，符号上界在超过上限时报告 `LIMIT_REACHED`，保证不会无限执行。
- **否定与位置**：语法错误、未定义变量、类型/线性不一致、循环边界缺失等都会被明确拒绝，
  错误信息带 `line:column` 源码位置。

### 一个示例

```
int n;
int sum = 0;
for (int i = 0; i < n; i = i + 1) {
    assert(i <= 2);
    sum = sum + i;
}
```

- 当 `n <= 3` 时所有断言通过，产生成功路径；
- 当 `n >= 3` 时在第 3 次迭代（`i == 2` 后再次进入，断言要求 `i <= 2`，`i == 3` 违反）产生失败路径。

## 2. 路径枚举与求解

- 探索为**深度优先工作清单**算法：每个挂起路径携带独立的符号存储、路径条件和语句栈；
  分支/断言/循环每次产生 0..n 个独立子路径。
- 路径条件是线性整数约束的合取；每次加入新约束前调用求解器，**不可满足立即剪枝**，
  可满足时返回一个具体的满足输入模型。
- 求解器是自研的**区间传播 + 有限域回溯**判定器，在配置域 `[solverLowerBound, solverUpperBound]`
  内对线性整数约束可靠且完备；支持 `< <= > >= == !=`、负系数、等式（含整数可除性）和常量约束。
- 每条报告路径包含：
  - `pathId`：在确定性枚举中的固定编号；
  - `outcome`：`SUCCESS` / `ASSERTION_FAILED` / `LIMIT_REACHED`；
  - `constraints`：人类可读的路径条件；
  - `inputModel`：满足该路径的一组输入赋值；
  - `finalValues`：在该模型下程序结束时所有变量的具体值；
  - `target`：命中的断言或终止说明。

## 3. 确定性

- 分支顺序固定（条件成立侧优先），与哈希表遍历无关；
- DNF 最小项、变量与取值枚举顺序固定；
- 求解器按种子决定取值方向。
- 因此在**相同程序 + 相同种子 + 相同求解域**下，路径编号、约束和求解模型逐次一致。

## 4. 并发、资源上限、取消与超时

- 固定大小线程池（`symbolicexecution.worker-threads`，默认 CPU 核数），每个任务独立的
  解析/求解/执行状态，**慢任务不会阻塞其他任务**。
- 每个任务可单独设置：
  - `timeoutMillis`：超时（基于截止时间协作式检查）；
  - `maxPaths`：最多报告路径数；
  - `maxUnroll`：单循环最大展开次数；
  - `maxSteps`：最大符号执行步数；
  - `maxBacktracks`：最大回溯次数。
- **取消**：标记取消并中断工作线程，任务状态变为 `CANCELLED`，不暴露部分结果，并删除检查点。
- **超时**：状态变为 `TIMEOUT`，不暴露部分结果，但**保留检查点**，可随后调用 resume 续跑
  （仅当超时前已有路径完成提交时检查点才非空）。resume 请求体可携带更宽松的 `limits`。
- **路径上限**：当报告路径数达到 `maxPaths` 时状态为 `LIMITED`，已提交路径落盘为非完成
  检查点，可 resume 继续枚举剩余路径。

## 5. 检查点与中断恢复

- 每发现一条路径就先写入检查点（临时文件 + fsync + 原子 rename），再对外可见；崩溃最多丢失
  正在写入的最后一条，已提交路径不会重复或遗漏。
- 检查点按 `jobId` 存为 JSON，包含程序哈希、已报告路径快照和是否完成。
- `resume` 会**重放同一确定性枚举**，跳过已提交前缀，只上报后缀；合并后与一次完整运行
  的路径集合、编号、约束和模型完全一致。对已完成任务重复 resume 是幂等的，不会重复报告。
  被显式 cancel 的任务不可 resume。

## 6. REST API

基础路径：`/api/analyses`。

### 提交分析

`POST /api/analyses`

```json
{
  "program": "int x; assert(x == 0);",
  "seed": 0,
  "limits": {
    "timeoutMillis": 5000,
    "maxPaths": 1000,
    "maxUnroll": 128,
    "maxSteps": 1000000,
    "maxBacktracks": 100000
  }
}
```

`seed`、`limits` 均可省略，省略时使用服务端默认值。响应：

```json
{
  "jobId": "...",
  "status": "RUNNING",
  "totalPaths": 0,
  "successCount": 0,
  "failureCount": 0,
  "paths": []
}
```

### 查询结果

`GET /api/analyses/{jobId}` → 返回当前状态与已报告路径（`QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/TIMEOUT/LIMITED`）。

### 取消 / 恢复

- `POST /api/analyses/{jobId}/cancel`
- `POST /api/analyses/{jobId}/resume`

### 错误响应

解析/校验失败返回 HTTP 400：

```json
{ "status": "REJECTED", "error": "undefined variable 'z'", "position": { "line": 1, "column": 9 } }
```

未知任务返回 HTTP 404。

## 7. 配置（application.properties）

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `symbolicexecution.default-seed` | `0` | 默认随机种子 |
| `symbolicexecution.solver-lower-bound` | `-1024` | 输入域下界 |
| `symbolicexecution.solver-upper-bound` | `1024` | 输入域上界 |
| `symbolicexecution.default-timeout-millis` | `5000` | 默认任务超时 |
| `symbolicexecution.default-max-paths` | `1000` | 默认最大路径数 |
| `symbolicexecution.default-max-unroll` | `128` | 默认循环展开上限 |
| `symbolicexecution.default-max-steps` | `1000000` | 默认最大步数 |
| `symbolicexecution.default-max-backtracks` | `100000` | 默认最大回溯数 |
| `symbolicexecution.worker-threads` | CPU 核数 | 并发工作线程数 |
| `symbolicexecution.checkpoint-dir` | `checkpoints` | 检查点目录 |

## 8. 构建与测试

```bash
mvn spring-boot:run          # 启动服务（默认 8080 端口）
mvn test                     # 运行全部测试
```

测试覆盖：词法/语法解析与定位错误、静态校验（未定义变量、作用域、非线性、循环边界）、
CFG、求解器（可满足/不可满足剪枝、等式、NE、负系数、确定性）、符号执行端到端（分支、循环、
断言成功/失败、不可行剪枝、确定性）、任务并发隔离、取消、超时、检查点恢复以及 REST 接口。

## 9. 已知边界

- 求解器在**有限声明域**内完备；域内无解即报告不可满足（域外模型不考虑）。
- 分支条件通过 DNF 处理布尔组合；深层嵌套的 `||` 会按最小项数量指数展开。
- 仅支持整数线性算术与有界 for 循环；不支持浮点、数组、函数、无界 while。
