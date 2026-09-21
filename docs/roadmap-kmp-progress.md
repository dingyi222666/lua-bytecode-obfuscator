# KMP 多模块迁移进度

依据契约文档 `docs/roadmap-kmp-modules.md` 执行，本文档由各阶段执行 agent 持续更新。
依赖方向必须保持单向：`model → io/edit/analysis → obfuscation-api → feature modules → vm/frontends`。

## 总阶段表

| Phase | 对应 roadmap 步骤 | 内容 | 状态 |
|---|---|---|---|
| 1 | 第 1 步 | 纯数据结构、opcode、常量和 prototype 移入 `:lua-bytecode-model`（commonMain） | 部分完成（2026-09-21） |
| 2 | 第 2 步 | `java.io` / `java.nio` / `java.util.Random` / `MessageDigest` 等替换为 expect/actual 或平台接口 | **完成（2026-09-21）** |
| 3 | 第 3 步 | reader/writer 与 editor DSL 拆成独立模块（保留包名兼容 facade） | 未开始 |
| 4 | 第 4 步 | analysis 与各混淆 pass 逐个迁移为独立 feature artifact | 未开始 |
| 5 | 第 5 步 | VM 作为可选模块（source CFF / VM lift / 生成源码 staging 分别出报告） | 未开始 |
| 6 | 第 6 步 | jsMain 网页端 + nativeMain CLI；JVM 仅保留库调用与测试适配 | 未开始 |
| 终验 | 第 7 步 | 现有 225 tests、三版本差分矩阵、VM 矩阵全绿验收 | 未开始 |

## Phase 1：:lua-bytecode-model（commonMain）

### 已完成

- 新建 `:lua-bytecode-model` 模块：`kotlin("multiplatform")` 2.4.20 插件，`jvm()` target + `jvmToolchain(17)`；commonMain 仅依赖 kotlin-stdlib，无任何其他库。
- 以下纯 Kotlin 文件以 `git mv` 移入 `lua-bytecode-model/src/commonMain/kotlin/`，**包名保持 `io.github.dingyi222666.luabytecode.bytecode` 不变**，根模块源码与 225 个测试零改动（通过根模块的 `implementation(project(":lua-bytecode-model"))` 依赖引用）：
  - `LuaBytecodeFormatException.kt`
  - `LuaBytecodeVersion.kt`（含 `LuaVarintStyle`）
  - `LuaInstruction.kt`（含 `LuaInstructionBuilder`、`luaInstruction` DSL）
  - `LuaOpcodeModes.kt`（含 `LuaOpcodeMode`）
  - `LuaOpcodes.kt`
- 移除上述文件中的 `@JvmStatic` / `@JvmOverloads`：`kotlin.jvm.*` 注解在 commonMain 不可用。仓库无 Java 源码，Kotlin 调用方（companion 成员调用语法）完全不受影响；`@JvmOverloads` 仅影响 Java 互操作重载生成，同样无调用方。
- 根 `build.gradle.kts` 增加 `implementation(project(":lua-bytecode-model"))`；`settings.gradle.kts` 增加 `include(":lua-bytecode-model")`。
- CI 验证：https://github.com/dingyi222666/lua-bytecode-obfuscator/actions/runs/35543175734（`./gradlew build`，225 tests 全绿；本次 run 同时包含根 `plugins {}` 需以 `apply false` 声明 multiplatform 插件版本的修复，见下方问题记录）

### 遗留项（Phase 2 已解除）

| 文件 | 原阻塞原因 | Phase 2 处理 |
|---|---|---|
| `LuaString.kt` | `StandardCharsets` | 已改纯 stdlib（`encodeToByteArray`/`decodeToString`/latin1 手写循环），仍在根模块；**可随时移入 model** |
| `LuaChunkHeader.kt` | 公开字段 `byteOrder: java.nio.ByteOrder` | 已替换为 `LuaByteOrder` 枚举（仍在根模块）；**可随时移入 model** |
| `LuaConstant.kt` | 传递依赖 `LuaString` | 依赖已解除，可移入 model |
| `LuaChunk.kt` / `LuaPrototype.kt` | 组合阻塞 | 依赖已解除；KDoc-only 的 io import 已随 `editChunkFile`/`dump(File)` 移交 platform.jvm 而失效，可移入 model |

> Phase 3 首个动作建议：把 `LuaString` / `LuaByteOrder` / `LuaChunkHeader` / `LuaConstant` / `LuaChunk` / `LuaPrototype` 一次性 `git mv` 进 `:lua-bytecode-model`（此时它们已是纯 common 代码）。

## Phase 2：JVM API 根除（2026-09-21 完成）

完整的替换总表、装配点、豁免面、expect/actual 规划与遗留风险见
**`docs/kmp-java-dependency-map.md`**。分五段增量落地，每段独立提交并走 CI 验证：

### 段A — ObfRandom（java.util.Random 根除）

- `platform.ObfRandom`：`kotlin.random.Random` 子类，逐方法位一致克隆 java.util.Random 的 48-bit LCG（含 seed unmask、`nextInt(bound)` 的 rejection 采样、JDK 17 `RandomGenerator` 的 `nextInt(origin, bound)` / `nextLong(bound)` 默认方法、`nextBytes` 的 4 字节/`nextInt` 消耗模式）。算法照抄 OpenJDK 17 源码（`Random.java` / `RandomSupport.java`）。
- 继承 `kotlin.random.Random` 的关键收益：`Iterable.shuffled` / `MutableList.shuffle` 自动落到 common 实现，且与原 JVM 路径（`Collections.shuffle`）绘制次序逐位一致。
- 17 个 pass 文件 + 12 个测试文件 + cli/Main 完成类型替换；`ObfRandomParityTest`（11 个测试）固定每个方法的差分一致性。
- 教训：`nextBytes` 是隐藏陷阱 —— kotlin 默认每字节消耗 8 bit，JDK 每个 `nextInt()` 填 4 字节；Pass K 的 blob 填充最先踩中（CI 第 2/3 轮暴露）。

### 段B — 端序与字符串

- `bytecode.LuaByteOrder` 枚举（BIG/LITTLE + displayName/flipped）替换全部 `java.nio.ByteOrder`（含公共 API 字段 `LuaChunkHeader.byteOrder`，grep 覆盖测试）。
- `LuaString` 去 `StandardCharsets`；`@JvmStatic` 一并移除。
- `platform.Hex`（hex2/hex2Upper/hexUpper/hexLower/hexPadded）替换 loader/printer/资产名/register 后缀的 `String.format`；model 模块内的 `kotlin.text.format`（common stable）保持不动。
- io 层 `java.io` 全部移出：`LuaChunkWriter` 核心改纯 Kotlin `ByteSink`；`load(File/InputStream)`、`dump(chunk, file/stream)`、`editChunkFile` 移到 `platform.jvm.LuaChunkIoJvm` 扩展，调用语法不变（调用方补 import，共 13 个文件）。

### 段C — MD5 / 身份集合 / 数学

- `platform.Md5`：RFC 1321 纯实现（轮常量内嵌，避免跨平台 sin 漂移）替换 LuaDecodeVariants 的 `MessageDigest`；`Md5ParityTest` = RFC 向量 + 与 JVM 参考差分（padding 边界 54..65/111..128 字节）。
- `platform.IdentitySet` / `IdentityMap`：引用相等语义（`===` 线性扫描）替换 `newSetFromMap(IdentityHashMap)` ×5 与 `IdentityHashMap` ×5；`IdentityCollectionsTest` 固定语义。
- `Math.floorDiv/floorMod` → `x.floorDiv(y)` / `x.mod(y)`（package kotlin 扩展；`kotlin.math` 无两参顶层函数）；`Math.pow` → `kotlin.math.pow`；`multiplyExact` → Long 中间量 + guard；`java.util.ArrayDeque` → `kotlin.collections.ArrayDeque`；`Long.toHexString` → `hexLower`；`Double.doubleToLongBits` → `doubleToCanonicalBits`（NaN 规范化保留，决策记录见依赖地图）。
- Pass K blob 的 `ByteArrayOutputStream` → `List<Byte>`。

### 段D — 熵源 / 资产 / 调试开关

- `platform.PlatformIo.kt`：`SecureEntropy` / `BundledChunkSource` / `DebugLog`（enabled/value/log）/ `TraceSink` 四个接缝 + `Platform` 持有器（默认值平台无关：stdlib 熵、无资产、静默调试、丢弃 trace）。
- `platform.jvm.JvmPlatform.install()`：SecureRandom 熵、classpath 资产、env 开关 + stderr、`/tmp/vm-subset-on` 哨兵 → `/tmp/vm-subset.txt`（字节级等价）。
- SeedPolicy 无种子路径、cli VM 分支、两个加密器的 decoder 资产、全部 44 处 `System.getenv/err` 调试点、LuaVmTranslator 3 处哨兵全部改走接缝。
- 装配：CLI `main()` 首行 install；测试经 JUnit autodetection 扩展（`InstallJvmPlatformExtension` + `META-INF/services` + `junit-platform.properties`）全局安装。

### 段E — 清理与门禁

- bytecode/obfuscate/vm 全部 50 行 `@JvmStatic` / `@JvmOverloads` / `@JvmField` 移除（cli/legacy/B4 保留；无 Java 源码，Kotlin 调用方零影响）。
- `tools/verify-kmp-purity.sh`：注释剥离（Kotlin 嵌套块注释语义）后扫描 `java.*`/`javax.*`/`::class.java`/`kotlin.jvm.*`/`@Jvm*`/`System.*`；范围 bytecode/obfuscate/vm/platform，platform/jvm 豁免；CI `verify.yml` 在 Build 后新增 "KMP purity gate" 步骤。
- `docs/kmp-java-dependency-map.md` 提交（依赖地图总表 + 装配点 + 豁免面 + expect/actual 规划 + 遗留风险）。

### Phase 2 关键决策记录

1. **ObfRandom 继承 `kotlin.random.Random`** 而非独立类：`Iterable.shuffled` / `MutableList.shuffle` 的 JVM 扩展消失后仍可编译（落到 common 实现），且绘制次序与 `Collections.shuffle` 一致；`nextInt(until)`、`nextInt(from, until)`、`nextLong(until)`、`nextBytes` 等全部 override 为 JDK 位一致实现。
2. **`doubleToLongBits` 的 NaN 决策**：保留规范化（NaN → `0x7ff8000000000000`）而不是裸 `toRawBits()`，保证与既有加密输出零行为差。
3. **`IdentityMap` 保留 JVM 装箱身份怪癖**（`IdentityHashMap<Int, …>` 中 >127 的 Integer 装箱不唯一 → 条目分裂）：common `===` 语义自动复现该行为；js/native 数字语义以 JVM 为准记录。
4. **`shuffled`/`shuffle` 迁移到 common stdlib**：common `shuffle` 绘制 `nextInt(i+1)`（i 从 size-1 到 1），与 `Collections.shuffle` 的 `nextInt(i)`（i 从 size 到 2）同序同换位，输出不变（parity 测试固定）。
5. **模型内 `kotlin.text.format` 不替换**：common stable API（`LuaBytecodeVersion` / `LuaInstruction` 的 toString），非 JVM 依赖。
6. **Pass K 激进管线验收测试钉住 masterSeed**（`20260917L`）：该测试默认走 SecureRandom（PRED-2），断言是"零明文泄漏"强不变式，但个别随机流会漏 1 个 promise 槽（2026-09-21 两次 CI 偶发暴露，proto K1/K3 orig=1）。钉住后可复现；随机熵本身仍由 SeedPolicy 测试与 CLI 覆盖。

### Phase 2 遇到的问题记录

- `kotlin.math` **没有**两参顶层 `floorDiv`/`floorMod`：它们是 package `kotlin` 的扩展（`x.floorDiv(y)`、`x.mod(y)`）。
- Kotlin 十六进制字面量 `>= 0x80000000` 是 `Long`（MD5 轮常量表需要逐个 `.toInt()`）。
- Kotlin 块注释**嵌套**：KDoc 里写 `*.luac` 会打开一个未闭合的嵌套注释（`/*` + `*`），报"Unclosed comment"。
- 类内同时声明私有 `keys`/`values` 备份列表与 `MutableMap.keys/.values` override 会撞名（K2 报重载歧义）——备份列表改名 `keyList`/`valueList`。
- `shuffled(java.util.Random)` 是 JVM-only stdlib 扩展；`ObfRandom` 继承 `kotlin.random.Random` 后落到 common `shuffled`，绘制次序一致。
- CI runner 网络抖动（GnuTLS -110 / checkout 失败）多次出现，均重跑恢复。
- 自托管 runner 偶发同 run 内 checkout 失败：`gh run rerun --failed` 即恢复。

### Phase 2 完成时的验证

- 最新 CI run 绿（build + 225 原测试 + ObfRandomParity/Md5Parity/IdentityCollections 新测试 + KMP purity gate 步骤）。
- `grep java.` 在 bytecode/obfuscate/vm/platform(除 platform/jvm) 无代码级残留（仅 KDoc 描述性文字，门禁剥离注释后放行）。

## Phase 1：问题记录（历史）

- Gradle 多模块 + Kotlin 插件版本声明：根模块已通过 kotlin jvm 2.4.20 把 `kotlin-gradle-plugin` jar 放上共享构建 classpath，子模块再以带版本方式请求 `kotlin("multiplatform")` 会抛 `InvalidPluginRequestException`。解法：根 `plugins {}` 里 `id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false`，子模块不带版本直接 apply。后续新增模块沿用此模式。
- CI runner 网络抖动复现一次（GnuTLS recv error -110），重跑即恢复，与代码无关。
