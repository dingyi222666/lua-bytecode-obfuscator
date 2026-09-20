# HANDOFF — Lua 5.3/5.4/5.5 字节码混淆器（交给下一位）

> 这是一份**交接 prompt**。请当成任务书直接使用。里面每一条"已验证"都有可复现命令；
> 每一条"未验证"都明确标注，**不要把未验证的当成功**。

---

## 0. 任务与硬规则（不要违反）

目标：把 Lua 5.3 / 5.4 / 5.5 字节码混淆做到**论文级强度（"爆炸"级）**，依据
`docs/obfuscation-papers.md`（§23 落地映射 / §24 分层策略 / §25 反制清单）与它引用的论文原文。

**硬规则（不可协商）**：

1. **绝不产出错误字节码。** 任何能力在通过五件套闸门之前，**不得进入任何预设**
   （LIGHT / BALANCED / AGGRESSIVE）。
2. 宁**拒绝**（refuse）不**错译**（mistranslate）。子集边界是合法的，错误结果不是。
3. 每轮结束必须更新 `docs/obfuscation-algorithms.md`（进度 / 根因 / 复现命令）。
4. 只用本地真实工具验证，不看"自嗨"结论。
5. 报告风格：**诚实**（失败就说失败，并给出复现命令），不许假绿。

---

## 1. 环境与工具链（全部已就绪）

```
项目根:      /Users/dingyi/projects/kotlin_projects/lua-bytecode-obfuscator
解释器:      /tmp/lua-5.3.6/src/lua   /tmp/lua-5.4.6/src/lua   /tmp/lua-5.5.1/src/lua
编译器:      同目录 luac（脚本用 `luac -l` / `luac -o`）
反编译器:    java -jar /tmp/unluac.jar <chunk.luac>
JDK17:       JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home sh gradlew
CLI:         ./build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator
```

- macOS **没有 `timeout`**：用看门狗 `cmd & pid=$!; ( sleep N; kill -9 $pid ) & wait $pid`。
  ⚠️ 历史上因为缺看门狗，遗留了两个 **跑了几小时的死循环 lua 进程** 吃满 CPU，污染了多轮测量。
  **每个执行产物的脚本都必须带看门狗。**
- 校验脚本要**并发安全**：所有临时文件 `mktemp`。历史上固定路径 `/tmp/cff-verify.luac`
  造成过大量假失败。

验证脚本（权威判据）：

```sh
sh tools/verify-all.sh     # 五件套总闸门，必须打印 "FOUR PIECE VERIFICATION: PASS"
sh tools/verify-cff.sh     # CFF 矩阵（fixture × 3 版本 × 2 模式 + ctor 用例）
sh tools/verify-vm.sh      # VM 矩阵（--vm-out 产物在真实 lua 上跑，与原生输出比对）
JAVA_HOME=... sh gradlew cleanTest test
```

---

## 2. 当前状态（务必读完第 4 节再动手）

### 2.1 已实现且**已验证**

| 层 | 内容 | 证据 |
|---|---|---|
| P1 | CFF（`LuaMinimalFlattening`，OLLVM 风格 dispatcher）5.3/5.4/5.5 全绿 | `verify-cff.sh` = **84 identical / 0 failed / 212 flattened prototypes**（3/3 稳定） |
| P2 | "参与计算并抵消"花指令（`emitParticipatingJunk`，4 种形态 + 收敛式死分支） | AGGRESSIVE 预设内，语义矩阵全绿 |
| P3(部分) | 每站点参数化：`SIMPLE_NIL` 从 `nil==nil` 改成 `nil == 每站点随机常量`；junk guard 77–100%、nil guard 100%、dispatcher 93–99% 唯一；MBA 族**降级为 cost**（文档注明不算强度层） | `python3 tools/audit-predicates.py <tool> <agg.luac>`；`HARD CHECK one step identities: 0 OK` |
| P4(部分) | VM 化：私有 ISA、每 chunk 随机 opcode 双射、LCG 流加密、加密常量池、生成式 per-chunk 解释器、`--vm-out` | `verify-vm.sh` = **14 identical / 34 refused / 0 failed**（floor 11） |
| P5 | 五件套脚本 + 修复：`verify-cff.sh` 加看门狗/rc 检查；`verify-vm.sh` 检查工具 rc（拒绝=exit 3，崩溃=failed） | 见第 5 节 |

### 2.2 ⚠️ 本轮在飞、**尚未跑完整套验证**的改动（接手第一件事）

我（上一位）这一轮重做了 **P3 的 runtime-seeded dispatcher**，起因是发现它之前是
`val useMask = false && secretSlot != null` —— **硬关掉的**。

现状：`LuaMinimalFlattening` 现在**在每个使用点重新求值 mask**：

```
mask = #_VERSION ^ <每原型 salt>
GETTABUP mask, env, K("_VERSION"); LEN mask, mask; LOADK probe, K(salt); BXOR mask, mask, probe
```

- **压力测试**：`big_module_53 --flatten` 20/20、`--jmp 3 --flatten` 20/20 **通过**（此前 1/6~1/10 概率崩）。
- **未验证**：改完后 `verify-cff.sh` / `verify-all.sh` / `gradlew test` **还没跑完**（用户让停）。
  **接手第一件事**：按顺序跑
  ```sh
  JAVA_HOME=... sh gradlew installDist && JAVA_HOME=... sh gradlew cleanTest test
  sh tools/verify-cff.sh          # 期望 84/0（如掉到 83 或更低，看是不是 5.4/5.5 某行）
  sh tools/verify-vm.sh           # 期望 14/34/0
  sh tools/verify-all.sh          # 期望 FOUR PIECE VERIFICATION: PASS
  ```
  任何一项不过：**回退这一改动**（git diff 就能看到），不要带着未验证的改动往下走。

其他本轮改动（相对安全，但同样要复测）：

- `LuaVmTranslator` / `LuaVmInterpreter` / `LuaVmInterpreterGenerator` / `generated VM module`：
  **5.3 数值 `for` 正确实现**（`FORPREP` 先减一个 step 再**跳到 FORLOOP**；`FORLOOP` 加 step、
  **按 step 符号**比较、写回 `R[A]` 与 `R[A+3]`）；**5.4/5.5 数值 `for` 一律拒绝**（它们是 count-based 布局）。
  证据：`for i=1,3 do print(i) end` 从 `0 1 2 3` 修正为 `1 2 3`；
  新 fixture `src/test/resources/real/ctor/c14_numericfor_print.{lua,_53/_54/_55.luac}`；
  `LuaVmNumericForTest` 3 个用例（含负 step 10 7 4 1、5.4/5.5 必须 refused）。
- `cli/Main.kt`：`--vm-out` 拒绝时 **exit 3**（原来 exit 0，导致"崩溃"与"合法拒绝"无法区分）。
- `tools/verify-cff.sh` / `verify-vm.sh`：rc 检查 + 看门狗 + `mktemp` + 自编译差分用例
  （`for i=1,3 do print(i) end`、负 step、float step、零次循环）。
- `tools/verify-vm.sh` 现在把"工具崩了但没有产物"计为 **failed** 而不是 refused（对抗报告 M6）。

---

## 3. 尚未完成（按优先级；这是下一位的主战场）

### P0 — 正确性/闸门
1. **`tools/verify-all.sh` 的反编译支柱不是闸门**（对抗报告 M5，第 50–57 行只 echo exit code，从不计 BAD）。
   unluac 完全成功也会打印 PASS。→ 必须设阈值并纳入 BAD。
2. **`tools/audit-predicates.py` 不健全**：
   - 第 97 行的窗口解析用 `re.match(r'prototype #\d+', line)`，**缩进的嵌套原型头识别不到**，
     于是整个 chunk 被当成一个原型、窗口跨原型边界（对抗报告 m10 的根因）。
     → 改成 `^\s*prototype #\d+`。
   - `one step identity` 只看 `LOADK` 紧跟算术，漏 5.3 的 RK 操作数与 5.4/5.5 的 `*K`/`*I` 形式（m8）。
   - 同寄存器比较（`EQ x x`）没有检查（m8）。
   → 目标：达到"goomba / MBA-Blast 一步化简"视角的自检，并作为硬门槛（现在已有 `HARD CHECK`，
     但工具本身要先健全）。
3. **死 `RETURN` 收尾**（对抗方建议，用户已确认要做）：
   在所有 pass 之后加一步：凡尾指令不是 `RETURN` 的原型，补一条**不可达**的
   `RETURN A=0 B=1 C=0`（5.4/5.5 可用 `RETURN0`）；它前面应是 `JMP`，永不执行，不影响语义，
   但让 unluac 等按官方约定校验的工具能正常反编译。→ 实现后三版本重跑 + unluac 复测。

### P1 — P3 收尾
4. dispatcher runtime seed 覆盖率与强度：per-use 方案落地后，用 audit 确认
   `dispatcher: runtime seeded` 从 ~23% 升到接近 100%（并记录 `#_VERSION` 的可折叠性边界：
   它是全局读，静态化简器不能在不假设 `_VERSION` 值的情况下折叠）。
5. 每站点唯一参数：dispatcher 仍有 1–7% 重复（多为窗口里没有常量导致的人工产物），
   修 audit 后重测；MBA 族保持"cost"标注。

### P2 — P4 的真实缺口（对抗报告已实测，别信文档旧话）
6. **handler 异构 / 多态 ISA 是假的**（M3）：`elseif op == N` 的 handler 表与 dispatch 顺序
   在每个产物里**字节相同**（sorted handler bodies md5 `2a2477…`）。→ 必须做真正的
   per-chunk handler 体异构（等价重写：不同局部名/不同等价算术/不同分支形状）+ dispatch 顺序
   随机化 + 超指令合并，并给出跨模块 md5 不同的证据。
7. **VM blob 可静态还原**（M4）：`/tmp/adv2/vmdecrypt.py` ~60 行即可全量反汇编；数值常量明文存储、
   seed 是字面量、**LCG 是教科书常量**（`1664525 / 1013904223`，64 位分支
   `6364136223846793005 / 1442695040888963407`）。
   **用户明确要求：LCG 这些全部要修，不许教科书。** → 每 chunk 随机的乘数/增量（且满足
   LCG 满周期条件）、seed **运行期派生**（不要字面量）、数值常量也进加密池、
   最好把 decoder 也搬进 VM/加密层。
8. VM 覆盖：`--vm-out` 目前 5.3 数值 `for` 支持、5.4/5.5 拒绝；泛型 `for`/表操作 5.4/5.5 仍拒绝。
   目标：按版本补齐并各自用差分矩阵验证（**每补一类都要有真实 lua 差分证据**）。
9. VM 产物目前是 **Lua 源码**（不是字节码）：要进预设需先用 `luac` 编译并复验。

### P3 — 完全缺失的两个 pass（用户点名要做）
10. **local 变量 / 寄存器级混淆（完全没有）**：
    目前只有 `localVariables.clear()`（清调试信息）。没有任何 liveness 驱动的
    寄存器重命名 / 合并 / 分裂 / live-range splitting，也没有 Tigress 式 "variable → table/array"。
    后果（对抗报告 INFO）：`base64Alphabet` / `isPrime` / `gcd` / `matrixMultiply` 等
    **标识符直接泄漏**到 aggressive 产物里，而 leak 检查没测这些名字。
    → 建议：基于 `LuaDataFlow` 做寄存器合并/分裂 + 局部变量名清洗 + 部分变量转表；
      并把"标识符泄漏"纳入 leak 检查（含 `debug` 信息与字符串常量两条途径）。
11. **常量加密 / 常量转字符串（完全没有）**：
    `--strings` 只加密 `LuaConstant.Str`；数值/布尔常量原样躺在常量表（`K1=1`、`K3=10`）。
    没有"从加密 table `GETTABLE` 取常量"、没有 `tonumber("…")` 还原、没有算术重物化。
    → 建议：per-chunk 加密常量表 + 运行期解密种子 + 逐站点重物化（`K1+K2`、`K1^K2` 等），
      并保证 **5.3 常量表上限 / `MAXARG_Bx` / `RK` 索引 >255 的 staging 路径**都覆盖；
      门槛 fixture 用 `big_module`（常量多）。

### P4 — 已知遗留
12. 5.5 数值 `for` 在 CFF 里的寄存器布局（VM 已拒绝，CFF 侧要用真实 lua 差分确认）。
13. `docs/obfuscation-algorithms.md` 里历史数字互相矛盾（flattened prototypes 122/182/288/294/212…），
    统一成一处的"最近一次完整测量"表格，并标注测量时间与命令。

---

## 4. 血泪教训（省你几天）

1. **帧顶以上的寄存器不是私有的。** 任何 `CALL` 的 `L->top = A + B` 最多只到"原始帧顶 + 1"，
   而 `precall` 允许 **C 被调函数从那里往上压 `LUA_MINSTACK`(20) 个临时值，返回时不清空**。
   于是"放在帧顶之上"的扁平化寄存器（state/probe/mask/junk）会被踩成**栈垃圾**。
   症状：`attempt to perform bitwise operation on a nil value`，**产物字节完全相同但只有
   1/6~1/10 的运行会崩**（取决于那个槽里原来是什么）。这正是 P3 mask 之前被"硬关掉"的原因。
   - 结论：**长驻值（mask）必须在每个使用点重新求值**（现在的做法），或者用真正的私有槽；
     `probe` 因为每次用前都重载所以安全。
   - 同类历史 bug：块入口的 state 重新派生曾经用**未加密的 `LOADK state, id`**，
     而 dispatcher 探针是 `id ^ mask` → 永远匹配不上 → 落到安全网 `RETURN`。
2. **5.3 的 `TEST A B C`**：C=1 表示"下一条 `JMP` 在 `R[A]` 为真时执行"（用真实 `luac -l` 验证，
   别猜）；5.4/5.5 用 `k` 位且极性不同。
3. **数值 `for` 的布局**（`lvm.c` 为准）：
   - 5.3：`R[A]` 内部索引、`R[A+1]` limit、`R[A+2]` step、`R[A+3]` 控制变量（函数体读它）；
     `FORPREP` 先 `R[A] -= step` **再跳到 `FORLOOP`**；`FORLOOP` 加 step、按符号比较、写回 A 和 A+3。
   - 5.4/5.5：count-based（`R[A+1]`/`R[A]` 是剩余次数，`FORPREP` 可能直接跳过整个循环），
     5.5 还把控制变量挪到 `R[A+2]`。**没建模就拒绝，绝不错译。**
4. **测量纪律**：
   - 对比产物要**归一化**（`sed -E 's/[^ ,]+\.lua:[0-9]+: //g'`），并且**看完整 diff**，
     不要只看首行（我曾经因为只看首行，把"字符串常量被踩坏"漏了几轮）。
   - 同一产物**连跑多次**判断是否环境相关（字节相同但结果不同 ⇒ 未初始化读，不是代码生成问题）。
   - 验证脚本必须有 `mktemp` + 看门狗 + 检查工具 rc；历史假失败多来自共享 `/tmp` 路径与遗留进程。
   - **每次改动后**：`installDist` → `cleanTest test` → 相关矩阵，一次只动一层。
5. 修改源码时**逐条 assert 替换成功**；大段脚本化编辑曾多次把文件改坏（丢声明、重复插入）。

---

## 5. 文件地图

```
src/main/kotlin/.../obfuscate/
  LuaMinimalFlattening.kt     CFF 主体（单位划分 / dispatcher / 参与式花指令 / runtime mask）
  LuaControlFlowNoise.kt      花指令 + 不透明谓词族 + installRuntimeSecret + appendEnvLookup
  LuaControlFlowFlattening.kt 调度入口（minimal 分支是活的）
  LuaObfuscator.kt            预设与 pass 顺序：jumps → strings → junk → clones → flattening
  LuaStringEncryptor.kt       字符串加密（只动 LuaConstant.Str）
  LuaArithmetic.kt            5.3/5.4/5.5 算术与 MMBIN glue
src/main/kotlin/.../vm/       P4：LuaVmProgram / Translator / Interpreter / InterpreterGenerator /
                              LuaVmObfuscation（--vm-out）+ generated VM module
src/main/kotlin/.../bytecode/ 解析/写回/分析（LuaDataFlow、LuaConstantReferences、printer）
tools/                        verify-all.sh / verify-cff.sh / verify-vm.sh / audit-predicates.py
docs/obfuscation-papers.md    §23 落地映射 / §24 分层策略 / §25 反制清单
docs/obfuscation-algorithms.md 进度与根因日志（每轮必须更新）
对抗报告: /tmp/adv2/ADVERSARIAL-REPORT.md（含 C1 wrong-bytecode、M3–M7、P3 审计细节）
```

---

## 6. 建议的第一个 30 分钟

1. `JAVA_HOME=... sh gradlew installDist && cleanTest test`，然后 `verify-cff.sh`、`verify-vm.sh`、
   `verify-all.sh`。记录**真实**数字（不达标就先回退第 2.2 节的在飞改动）。
2. 修 `audit-predicates.py` 的两个不健全点（缩进原型头、RK/`*K` 操作数、`x x` 比较），
   重新测量 P3 指标。
3. 实现"死 `RETURN` 收尾"（第 3 节 P0-3），三版本重跑 + unluac 复测。
4. 然后按第 3 节 P2（**LCG 非教科书化 + VM handler 异构 + 常量加密**）和 P3（**local 变量 pass +
   常量加密 pass**）推进，每一类都要有：真实 lua 差分证据 + 四件套 PASS + 文档更新。

**判据永远是**：`tools/verify-all.sh` 打印 `FOUR PIECE VERIFICATION: PASS`，
`verify-cff.sh` 无 failed，`verify-vm.sh` 无 failed 且 identical 不低于 floor。
达不到就别往预设里放。
