# 统一 CFF：实现、配置与验证范围

设计依据为 [obfuscation-ccf.md](obfuscation-ccf.md)。所有配置共用一个 emitter；`full` 和 `stress` 表示请求的功能集合，逐原型报告才表示实际应用的功能。默认 `baseline` 保持开启，新特性默认关闭，遵循设计 §9.6。

## 使用

构建需要 JDK 17；当前 Gradle 8.4 不支持用 JDK 26 启动。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test installDist

build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator input.luac \
  --cff full --seed 42 --cff-report cff-report.json --dump output.luac

# 单项覆盖；明确关闭依赖时，依赖它的特性会降级并记录原因。
build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator input.luac \
  --cff full --cff-features=-dispatch.multiple,-layout \
  --seed 42 --dump output.luac

# 请求包括私有表和图候选的全部特性。
# 仅当宿主契约允许忽略新增分配带来的 GC/终结器/内存失败差异时使用该选项。
build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator input.luac \
  --cff stress --cff-allow-allocation-effects --seed 42 \
  --cff-report cff-report.json --dump output.luac
```

API：`ControlFlowFlatteningConfig.BASELINE`、`ControlFlowFlatteningConfig.FULL`；表模式和谓词族分别用 `stateTable`、`predicateFamilies` 配置。`allocationEffectsUnobservable` 是明确的宿主契约，不会由工具自动假定。

## 功能对应

| 设计 | CLI 独立特性 | 当前实现 |
| --- | --- | --- |
| §1 唯一内核 | `--cff baseline` | 原型副本构建、完整候选验证、原型级提交；父子接口不变 |
| §2 运行期 state | `state.runtime` | 从已初始化参数的 Lua 真值投影得到整数键；不读 `_ENV`，不调用随机数、转换或用户元方法 |
| §2 命名空间 | `state.namespaces` | 源块命名空间、局部出口编号、两个不同奇数仿射编码；比较检查完整上下文 |
| §3 分段 | `dispatch.segmented` | 有限比较段；大小由 `segmentSize` 配置 |
| §3 多实例 | `dispatch.multiple` | 运行期键选择两个独立排布的 dispatcher；依赖 `state.runtime` |
| §3 嵌套 | `dispatch.nested` | 第一层识别命名空间，第二层识别局部 token；依赖 `state.namespaces`，不递归调用 Lua 函数 |
| §3 有效更新 | `dispatch.effects` | state 和游标同步更新；后继 probe 实际消费游标，移除更新可使验证失败 |
| §4 入度 | `indegree` | 两条可行网关路线分别变换和规范化状态，校验后进入同一语义块；附加数值守卫只计噪声 |
| §4 布局 | `layout` | 受循环方向、原生取指目标和指令组约束的随机拓扑排布；显式补齐 fall-through |
| §4 切分 | `split` | 在原子指令组之间新增边界并重建分析；保守排除调用和循环区域 |
| §5 表中转 | `state.table` | 私有表的运行期整数键、两条别名读路线；写入后清除规范 state，分派依赖读回结果；不跨原 CALL 存活 |
| §6 简单同余 | 已移除 | 简单奇偶／布尔多项式易折叠，不作为生产谓词 |
| §6 非平方 | `predicate.non_square` | `7y²−1` 的模 8 剩余与平方剩余分离；整数运算 |
| §6 简单程序关系 | 已移除 | 不再生成 `x(x−1)=0` 这类有限域恒等式伪谓词 |
| §6 私有结构 | `predicate.private_structure` | 私有别名的写读关系；需要分配契约与冷路径预算 |
| §6 图候选 | `predicate.graph` | 带三角形见证的有限图，运行期顶点交换保持见证边；实验候选，未声明抗图化简收益 |
| §6 参与计算 | `junk.participating` | 整数计算经 state 进入后续比较；可整体化简的抵消只计计算成本 |
| §7 末尾常量 | 随常量加密 pass | CFF 原型只允许无 CALL 的 LOADK/整数 XOR 局部展开；统一重定位并重验控制包；失败保留已验证候选 |
| §8 降级 | 自动 | 特性依赖过滤 → 同一内核 baseline 重建 → `UNCHANGED_CFF`；非法结构为 `INPUT_REJECTED` |

所有谓词要求运行期参数输入，缺少时记录 R20 并关闭，禁止常量回退。分段／入度不再附加旧的常量汇聚守卫；入度由实际消费 state 的网关承担。表和私有结构/图不会在保守识别到回边的原型中分配。没有足够寄存器、开放 top 跨越了不能保护的边界，或验证/体积预算超限时，会按原因降级。既有解密器及解密调用区域按对象身份保护，避免可变原型的哈希查找失效。

## 已修复的语义问题

- Lua 5.3 `TESTSET` 同样会跳过下一指令，不能把它和 JMP 拆开。
- Lua 5.3 `JMP A≠0` 必须在原路径执行关闭 upvalue，之后才能进入控制包。
- Lua 5.3 调用 C 函数的 `TAILCALL` 会回到下一条 `RETURN B=0`；这对指令保留为一个区域。
- FOR/TFOR 指令只发射一次；FORPREP 的进入路径不再依赖物理邻接。以零基原指令 PC 表示时，5.4/5.5 FORPREP 的空循环后继是 `PC+2+Bx`，因为取指已自增且 VM 再加 `Bx+1`。
- TFORCALL/TFORLOOP 保持连续，TFORPREP 仍直接指向 TFORCALL 本体。
- VARARGPREP 在入口执行一次；开放多返回值生产/消费之间不插入管理指令。
- 删除 CFF 对 `_VERSION` 和运行期 secret 安装器的依赖，避免额外环境读取与跨原型副作用。
- 失败候选的常量池、代码、栈大小和调试信息整体丢弃；不再只恢复 maxStackSize。

## 证据与边界

`CffTranslationValidator` 从最终编码的指令字独立执行管理层，枚举 nil/false/true 三类输入（两个整数键），检查未初始化读、原寄存器写入、MM 配对、唯一目标与有限终止。它不知道 emitter 的算术表达式。`proof` 留在构建报告对象里，不写入 Lua 常量或调试信息。常量局部展开后用重定位后的证书再验一次。

这是一份**管理层有限域证书**。原始指令的语义依靠原子区域保持、结构检查和真实 VM 差分；它不是任意 Lua 堆、元方法、协程程序的完整无界等价证明。变异测试覆盖 dispatcher 极性破坏；回滚、独立开关和依赖组合测试防止用静默降级伪造覆盖。

`state.runtime` 表示实际读取运行值。只有原型入口参数目前具有两个可行输入的变化性见证；分支后的参数可能已被条件固定。报告中的 `variableSeedSites` 与管理入口数量分开，不能把所有运行读取都称为已证明动态熵。

图、别名和网关的存在也不代表抗分析收益。这里没有完成 D810/CaDeCFF 等工具适配、隐藏语料攻击、预登记 δ 和 p95 性能晋级；因此 `full/stress` 不宣称通过 GATE-8，也不命名为论文安全等级。源码自检、逐指令 hook、精确 GC/时序、物理 PC 和栈布局观察不属于普通 CFF 的语义保证。

## 重现验证

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test installDist
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  bash tools/verify-cff.sh --combinations --report /tmp/cff-matrix.json
```

矩阵固定使用 Lua 5.3.6、5.4.6、5.5.1，校验 `tools/cff-vm-manifest.json` 中 VM/opcode 源码的 SHA-256。缺解释器、缺 fixture、进程超时、工具非零退出均失败。比较完整输出和退出状态；JSON fixture 只忽略对象成员顺序，比较全部键、值和输出行。

组合轴包括 baseline/full/stress、单独 CFF、JMP 跳板、字符串+花指令、aggressive 前置组合。JVM 测试另覆盖单特性、必要依赖、nil 空洞、尾调用 C、闭包捕获、协程和 C 被调函数扩栈并写入 300 个临时槽。
