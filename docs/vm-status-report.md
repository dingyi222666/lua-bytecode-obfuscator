# VM 能力状态报告（终验基线，2026-09-21）

本文档如实记录 `:lua-vm` 的**当前**能力状态，是 roadmap 终验第 5/6 条
（"VM 报告必须区分四类能力"、"完整 VM"声明纪律）的事实基线。只写事实，
不写营销。配套文档：`docs/vm-implementation.md`（设计）、
`docs/vm-runtime-support.md`（行为矩阵）、`docs/roadmap-kmp-progress.md`
Phase 5（模块化与报告契约）。

## 1. 报告四分类（契约结构）

`LuaVmObfuscation.CapabilityReport`
（`modules/obfuscation/lua-vm/src/commonMain/.../vm/LuaVmObfuscation.kt`）恒以固定顺序携带四个
category，另有一个不属于四分类的 staging 上下文条目：

| id | 语义 | 当前状态 |
|---|---|---|
| `source.cff` | 输入 chunk 的 CFF（现有管线的 `ControlFlowFlatteningReport`，宿主经 `LuaVmPipeline.sourceCff` / `inputObfuscation` 注入） | applied 或 refused（取决于宿主注入与 CFF 是否flatten 到原型） |
| `vm.lift` | 输入 chunk → 私有 VM ISA（加密程序 + 加密常量池） | translator 接受时 applied |
| `interpreter.source.cff` | 生成 Lua 解释器**源码**的 staging 混淆（`LuaVmSourceObfuscator`，随机私有 dispatcher，无 parser） | 默认 applied（`Options.interpreterCff=false` 时 refused） |
| `interpreter.bytecode.cff` | 生成解释器的**目标字节码级 CFF** | **恒为 `NOT_IMPLEMENTED`（label `not_implemented`）**——生成宿主目前以 Lua 源码交付，尚未编译到目标字节码再做 CFF |
| （上下文）`input.obfuscation` | 输入 staging（`LuaVmPipeline.inputObfuscation`） | applied/refused |

诚实语义：

* `staged` = 三个已实现层（source CFF / lift / interpreter source CFF）全部 applied；
* `complete` = `staged && interpreter.bytecode.cff==APPLIED` → **当前恒为 false**，
  在目标字节码级 CFF 真正落地前，任何 VM 产物都不应被表述为"完整 CFF 保护"。

证据：

* 结构与语义：`LuaVmObfuscation.kt`（`CapabilityStatus.label` 含
  `not_implemented`；`categories` 固定顺序；`targetBytecodeCff` 默认值即
  `NOT_IMPLEMENTED` 并自带 pending 说明）。
* 契约测试：`modules/obfuscation/lua-vm/src/commonTest/.../LuaVmCapabilityReportContractTest.kt`
  ——四分类 id/顺序、目标 CFF `not_implemented`、`complete` 在全部可达状态
  组合下恒 false；该测试位于 commonTest，随 jvm/js/linuxX64 三端测试运行。
* CLI 输出：`--vm-out` 逐行打印 `<id>: <label> - <details>` 与
  `staged: …, complete: false (target bytecode-level CFF is not implemented)`
  （`src/main/kotlin/.../cli/Main.kt`）；`VmDisablementTest` 在真实 CLI 进程
  输出上断言这四行与 `complete: false`。

## 2. 三版本（5.3 / 5.4 / 5.5）支持矩阵

逐版本行为矩阵见 `docs/vm-runtime-support.md`；此处只列版本差分事实
（出处：`LuaVmTranslator.kt` / `LuaVmInterpreterGenerator.kt` / 差分测试）：

| 维度 | 5.3 | 5.4 | 5.5 |
|---|---|---|---|
| 基线 lift（跳转/比较/算术/closure/vararg） | ✅ | ✅ | ✅ |
| 寄存器比较编码 | `LT A B C`（极性在 A） | `LT A B k`（极性在 k，VM-CMP-1） | 同 5.4 |
| 立即数/常量比较与算术（EQI/LTI/…、ADDI/ADDK/…） | —（无此类指令） | ✅ | ✅ |
| count-based numeric for | —（5.3 用跳转式 for） | ✅（库开关 `enableCountBasedLoops`，默认关） | ✅（同左） |
| SETLIST 容量编码 | block×50 | C+Ax×256 | vC+Ax×1024 |
| TFORPREP control/close 槽 | — | — | 5.5 交换 |
| GETVARG / VARARG 隐藏参数表 | — | — | ✅（PF_VATAB） |
| ERRNNIL 专用诊断 | — | — | ✅（错误文本随常量池加密） |

已知边界（如实声明，均已在 `docs/vm-runtime-support.md` "仍有的边界"记录）：
私有 ISA 操作数/分支偏移为 8 位；元方法桥接指生成程序经原生 Lua 运算执行，
不含调试 API 级透明性；分配次数/GC 时机/调试栈无透明性保证。

## 3. 特性矩阵（报告各 category 何时 applied / refused）

| 输入配置 | `source.cff` | `vm.lift` | `interpreter.source.cff` | `complete` |
|---|---|---|---|---|
| 严格入口 `LuaVmPipeline.obfuscated(...)`（真管线 + 源 CFF stage 注入） | applied（flatten>0） | applied | applied | false |
| 仅注入 `inputObfuscation`（aggressive 管线含 CFF 报告） | applied（取管线报告内嵌 CFF） | applied | applied | false |
| 无任何 staging（`emitModule` 直调） | refused | applied | applied | false |
| CFF 未 flatten 到任何原型 / `requireSourceCff` 且无 CFF | refused（或整体拒绝） | applied* | applied | false |
| `interpreterCff=false` | 任意 | applied | refused | false |
| translator 拒绝（子集外） | — | —（无产物） | — | false |

\* `requireSourceCff=true` 时无 CFF 即整体拒绝（`emitModuleDetailed` 返回 null）。

`input.obfuscation` 与 `interpreter.source.cff` 属 staging 层；它们 applied
**不**改变 `complete=false` 的事实。

## 4. 拒绝原因清单（代码出处）

拒绝是**正确行为**（子集外输入绝不产出错误程序）；CLI 把拒绝映射为
**exit 3**，与崩溃（其他非零）和成功可区分（adversarial audit M6）。
出处均为 `modules/obfuscation/lua-vm/src/commonMain/.../vm/LuaVmTranslator.kt`（除注明外）：

| # | 拒绝原因 | 触发条件 |
|---|---|---|
| 1 | `unsupported op=<op>` | opcode 在建模子集之外（`else ->` 分支；traceSink 记录 `src=<source>:<line>`） |
| 2 | `FORPREP/FORLOOP target missing` | 循环跳转目标解析失败 |
| 3 | `<version> numeric for capability disabled` | 5.4/5.5 的 count-based numeric for 在 `enableCountBasedLoops=false` 时拒绝。注意：**CLI 的 `--vm-out` 路径目前未开放该开关**（`cli/Main.kt` 以默认 `Options()` 调用），故这两版本的 numeric for 经 CLI 一律 refuse；库级能力由 `LuaVmCountLoopTest`/`LuaVmCountLoopGeneratedTest` 覆盖 |
| 4 | `immediate not materialised` / `constant operand not materialised` | 立即数/常量操作数未能在 prologue 物化 |
| 5 | `compare without a following JMP` / `compare JMP target missing` | 比较指令未按 ABI 跟随 JMP |
| 6 | `unexpected C field in a 5.4+ register compare` | 5.4/5.5 寄存器比较出现非预期编码 |
| 7 | `private ISA operand exceeds encoding range`（`EncodingLimit`） | 操作数/分支偏移超出 8 位编码能力（不截断、不静默） |
| 8 | `LOADK` 常量索引越界（`k < 0 ‖ k >= constants.size`） | 常量池不一致的畸形输入 |
| 9 | `requireSourceCff` 拒绝（`LuaVmObfuscation.emitModuleDetailed`） | 要求源 CFF 但宿主未注入且管线报告无 CFF |

## 5. 真实解释器差分的证据链

验收链路：官方 `luac` 产物 → `LuaVmTranslator` → 加密程序/常量池 → 生成的
Lua 解释器 → **对应版本官方 `lua`** 执行，与原 chunk 输出差分。JVM 参考解释器
只用于 ISA 单元测试，不作为验收依据。

1. **jvmTest e2e**（`:lua-vm` jvmTest，CI 全量跑）：`LuaVmGeneratedInterpreterTest`、
   `LuaVmNumericForTest`、`LuaVmClosureTest`、`LuaVmArithmeticTest`、
   `LuaVmVarargTest`、`LuaVmProgramTest`、`LuaVmTranslatorTest`、
   `LuaVmB2DebugTest`、`LuaVmCountLoopTest`、`LuaVmCountLoopGeneratedTest`；
   `LuaVmSemanticBridgeTest`（14 组真实差分，需官方 lua 二进制，缺失时
   `assumeTrue` 明确跳过）；`LuaVmCalibTest`（同跳过逻辑）。
2. **基准文件**：`src/test/resources/real/<name>_<53|54|55>.luac.out`（官方
   解释器对原 chunk 的输出基准，附 `.luac.err`）与
   `src/test/resources/bytecode/`、`src/test/resources/real/`（含 `real/ctor/`
   的 c01–c14 差分形状）下的三版本 `.luac` fixtures。
3. **差分矩阵脚本**：
   * `tools/verify-vm.sh` — 每 fixture × 每版本发 VM 模块并在匹配版本官方
     解释器上运行差分；`refused`（exit 3）合法，`failed` 不允许；
     覆盖阈值 `VM_MIN_IDENTICAL=31`。**前置条件**：`/tmp/lua-5.3.6`、
     `/tmp/lua-5.4.6`、`/tmp/lua-5.5.1` 三套源码构建（`src/lua`、`src/luac`）
     与可用的混淆器命令（`OBFUSCATOR_TOOL` 或历史 installDist 路径）。
     历史记录基线：**31 identical / 23 refused / 0 failed**。
   * `tools/verify-all.sh` — 四件套 + VM 矩阵 + CFF 矩阵 + 泄漏/谓词审计的
     总入口（同样依赖上述三套解释器与 python3）。
4. **CI 接入**（本轮新增）：`.github/workflows/verify.yml` 的
   `differential-matrix` job → `tools/run-differential-matrix.sh`：先探测
   三套解释器/luac/python3，**齐备才跑 `verify-all.sh`**，缺失则逐项打印
   skipped 理由并 exit 0（skipped 不是语义通过，也不阻塞门禁）。工具经
   `OBFUSCATOR_TOOL` 注入 JVM 库入口 shim（`:exportCliRuntimeClasspath`
   导出 classpath；`application` 插件已移除，native CLI 是唯一默认 CLI）。

手动复现（本机已备三套 lua 源码构建时）：

```sh
sh tools/run-differential-matrix.sh          # 探测 + 全套差分（含 VM 矩阵）
# 或仅 VM 矩阵，直接用 native CLI 作为工具：
./gradlew :lua-cli-native:linkReleaseExecutableNative
OBFUSCATOR_TOOL="$PWD/lua-cli-native/build/bin/native/releaseExecutable/lua-cli-native.kexe" \
  sh tools/verify-vm.sh
```

## 6. 目标字节码级 CFF：未实现（明确声明）

`interpreter.bytecode.cff` **没有实现**。生成解释器当前以 Lua **源码**交付；
要对该目标字节码再做 CFF，需要目标版本 `luac` / loader / editor / validator
的完整闭环（roadmap 第 7 步之后的独立工作项）。在此之前：

* 报告中该 category 恒为 `not_implemented`（默认值即如此，不可被调用方覆盖为 applied）；
* `complete` 恒为 `false`，并由 commonTest 三端逐状态组合断言；
* 任何对外表述不得将 VM 产物描述为"完整 CFF 保护"或"完整 VM"。

## 7. "完整 VM"声明纪律

当前仓库（docs/、README、CLI 输出、测试断言）**不存在**"完整 VM"声明。
按 `docs/roadmap-kmp-modules.md` 验收条件，未来任何此类声明必须**同时**给出：

1. 三版本（5.3/5.4/5.5）差分结果（`tools/verify-vm.sh` 口径）；
2. 特性矩阵（第 3 节口径）；
3. 拒绝原因清单与拒绝统计（第 4 节口径；`refused` 不计为语义支持）；
4. 真实解释器差分证据（第 5 节口径，官方 lua 执行，非 JVM 参考解释器）；
5. `interpreter.bytecode.cff` 的真实状态（当前为 `not_implemented`）。

本文档即该纪律的核对基线；能力变化时先改代码与测试，再同步本报告。
