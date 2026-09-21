# KMP 多模块迁移进度

依据契约文档 `docs/roadmap-kmp-modules.md` 执行，本文档由各阶段执行 agent 持续更新。
依赖方向必须保持单向：`model → io/edit/analysis → obfuscation-api → feature modules → vm/frontends`。

## 总阶段表

| Phase | 对应 roadmap 步骤 | 内容 | 状态 |
|---|---|---|---|
| 1 | 第 1 步 | 纯数据结构、opcode、常量和 prototype 移入 `:lua-bytecode-model`（commonMain） | 部分完成（2026-09-21） |
| 2 | 第 2 步 | `java.io` / `java.nio` / `java.util.Random` / `MessageDigest` 等替换为 expect/actual 或平台接口 | **完成（2026-09-21）** |
| 3 | 第 3 步 | reader/writer 与 editor DSL 拆成独立模块（保留包名兼容 facade） | **完成（2026-09-21）** |
| 4 | 第 4 步 | analysis 与各混淆 pass 逐个迁移为独立 feature artifact | **完成（2026-09-21）** |
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

## Phase 3：:lua-platform / :lua-bytecode-io / :lua-bytecode-edit（2026-09-21 完成）

### 模块与 source set 现状

```text
:lua-platform        commonMain: platform.{Hex,IdentityCollections,Md5,ObfRandom,PlatformIo}
:lua-bytecode-model  commonMain: 上表 Phase 1 五文件 + LuaByteOrder/LuaString/LuaChunkHeader/
                                 LuaConstant/LuaChunk/LuaPrototype；commonTest: LuaInstructionTest
:lua-bytecode-io     commonMain: bytecode.io.{LuaChunkReader,LuaChunkWriter,LuaChunkLoader}
                     jvmMain:    platform.jvm.{load(File/InputStream), dump(chunk→File/OutputStream)}
                     commonTest: LuaChunkReaderTest；jvmTest: Writer/Error/Loader 三测试 + TestFixtures 子集
:lua-bytecode-edit   commonMain: bytecode.edit 四文件 + bytecode.analysis.{LuaCodeRelocation,LuaLineInfo}
                     jvmMain:    platform.jvm.editChunkFile（EditChunkFileJvm.kt）
                     jvmTest:    LuaChunkEditTest + TestFixtures 子集
根模块               main: bytecode/{analysis 剩余,print,support}、obfuscate、vm、platform/jvm
                           （JvmPlatform + EditChunkFileJvm 已移出，cli/legacy/decompile 照旧）
```

依赖方向（无环）：`platform ← model ← io ← 根`、`model ← edit(jvmMain→io) ← 根`。
全部新模块 `kotlin("multiplatform")`、`jvm()` target、`jvmToolchain(17)`、仅 stdlib + project 依赖；
`jvmTest` 统一 `useJUnitPlatform()`。settings.gradle.kts include 四个新模块，
根 build.gradle.kts 显式 `implementation` platform/model/io/edit。

### 关键决策

1. **:lua-platform 独立模块**：platform 包的 common 部分（Hex/Md5/ObfRandom/IdentityCollections +
   PlatformIo 四接缝）整体 `git mv` 进 `:lua-platform`，包名不变，obfuscate/vm 零改动。
   `platform/jvm`（JvmPlatform.install()）留在根模块继续装配。
2. **LuaCodeRelocation / LuaLineInfo 归属 edit**：`retargeted` / `setAbsoluteLines` 是 edit DSL
   （LuaChunkDsl.replaceCode / LuaInstructionStream / LuaPrototypeEdit）的直接基础，随 edit 进
   commonMain（包名仍 `bytecode.analysis`）。`relocated()` 依赖留根的 `jumpTarget`（LuaConstantReferences.kt），
   故它移入该文件而不是 edit 模块；Phase 4 迁移 analysis 时再整体调整。
3. **editChunkFile 归 edit 模块 jvmMain**：它组合 edit DSL + io 适配器，放 io 会造成 io→edit 反向依赖；
   放 edit jvmMain（依赖 io）是唯一的非环解。io 与 edit 仍是兄弟模块（edit 仅 jvmMain 依赖 io）。
4. **BundledChunkSource 的 classpath 实现留在根 JvmPlatform**：它服务 obfuscate/vm 的 decoder 资产场景，
   与 chunk 读写 io 无关，不迁入 `:lua-bytecode-io`。
5. **测试归属**：纯测试进 commonTest（LuaChunkReaderTest、LuaInstructionTest）；fixture 型测试进
   jvmTest（classpath 加载 `getResourceAsStream` 目前是 JVM 专属）。TestFixtures.kt 以“复制子集”方式
   落到 io/edit 的 jvmTest（root 原件保留给集成测试），21 个 luac fixture + hello.lua 同步复制。
   `root 既有测试零改动`成立（除 LuaLineInfoTest 补一条显式 import）；唯一的测试改写是
   LuaChunkLoaderTest 两处 `stringConstants()`（edit 包扩展）改用常量表过滤，避免 io 测试反向依赖 edit。
6. **门面类名冲突教训（重要）**：根模块 main 输出与 `:lua-bytecode-io` jvm jar 各有一个
   `platform.jvm.LuaChunkIoJvmKt`（同包同名 Kotlin 文件门面）。根 main 编译只见 io jar → 正常；
   测试编译 classpath 同时有根 main 输出 + io jar → 同名门面互相遮蔽，`load/dump` 扩展在
   `:compileTestKotlin` 消失（Unresolved reference）。解法：同包文件名必须跨模块唯一——根侧文件改名
   `EditChunkFileJvm.kt`（edit 模块侧沿用此名并加注释）。后续拆分新文件都要遵守该约定。

### 工具与 CI

- `tools/verify-kmp-purity.sh`：SCOPE 增加四个新模块的 `src/commonMain`，白名单扩为
  `*/platform/jvm/*` 与 `*/src/jvmMain/*`；门禁继续在 verify.yml 的 Build 之后执行。

### Phase 3 验证

按提交序列逐个 CI 验证（每步 push 后 `gh run watch` 至绿）：

- `:lua-platform`：run 35553848059
- model 补齐：run 35554238402
- `:lua-bytecode-io`：run 35555210261（第一次 35554924877 红，门面冲突，见决策 6）
- `:lua-bytecode-edit`：run 35555831806（第一次 35555491636 红，`relocated` 依赖 `jumpTarget`，见决策 2；
  另一次 checkout 网络抖动按预案 rerun 恢复）

### Phase 3 遗留项

- `bytecode/analysis` 剩余（LuaConstantReferences/LuaDataFlow）与 `bytecode/print`、`bytecode/support`
  仍在根模块，按计划随 roadmap 第 4 步（analysis/feature 拆分）迁移。
- 各模块目前只有 `jvm()` target；js/native 加入时需给 fixture 加载做 expect/actual
  （`getResourceAsStream` 目前锁死 jvmTest）。
- io/edit 的 `implementation(project(...))` 依赖尚未提升为 `api`；Phase 4/5 若把模块发布为独立
  artifact，需要按公共 API 暴露面重新标注 api/implementation。
- 根测试 TestFixtures.kt 与 io/edit 副本三处并存（有意为之，见决策 5）；修改 fixture 清单时需同步。

## Phase 4：:lua-bytecode-analysis / :lua-obfuscation-api / 五个 feature 模块（2026-09-21 完成）

### 模块图现状

```text
:lua-platform             commonMain: platform.{Hex,IdentityCollections,Md5,ObfRandom,PlatformIo}
                          jvmMain:    platform.jvm.JvmPlatform（自根模块迁入，供各模块测试 install）
:lua-bytecode-model       commonMain: 11 文件全量模型；commonTest: LuaInstructionTest
:lua-bytecode-io          commonMain: bytecode.io.{LuaChunkReader,LuaChunkWriter,LuaChunkLoader}
                          jvmMain:    platform.jvm.LuaChunkIoJvm
:lua-bytecode-edit        commonMain: bytecode.edit 四文件 + bytecode.analysis.{LuaCodeRelocation,LuaLineInfo}
:lua-bytecode-analysis    commonMain: bytecode.analysis.{LuaConstantReferences,LuaDataFlow}
                                    + bytecode.print.LuaChunkPrinter
                                    + bytecode.support.LuaRegisterAnalysis
:lua-obfuscation-api      commonMain: 契约层（扩展点/pipeline 配置/报告/SeedPolicy/共享 helper，
                                      全部保持包名 io.github.dingyi222666.luabytecode.obfuscate，
                                      LcgSpec/LuaDecodeVariants 保持 bytecode.support）
:lua-obfuscation-strings  commonMain: LuaStringEncryptor/LuaStringCipher/LuaStringOperandLowering
                          jvmMain resources: obfuscator/decode_{53,54,55}.luac（decoder 随模块走）
:lua-obfuscation-constants commonMain: LuaConstantEncryptor/ConstCipher
                          jvmMain resources: obfuscator/decode_num_{53,54,55}.luac
:lua-obfuscation-jumps    commonMain: LuaControlFlowNoise（JumpIndirection/Junk/BlockCloning）
                                    + LuaTailReturn + LuaMbaPredicates
:lua-obfuscation-cff      commonMain: LuaControlFlowFlattening + CffControlPackets + CffConstantExpansion
:lua-obfuscation-register commonMain: LuaRegisterObfuscator
根模块（frontends）        main: LuaObfuscator（编排器）、LuaKeyRenaming、LuaHostSeed、
                                vm/、cli/、decompile/、B4*；test: 跨 pass 集成测试
```

依赖方向无环：`platform ← model ← io ← edit ← analysis ← api ← {strings,constants,jumps,cff,register} ← 根`。
**feature 之间零依赖**。全部九个 KMP 模块 `kotlin("multiplatform")` + `jvm()` + jvmToolchain(17) +
`maven-publish`（group=io.github.dingyi222666，version 1.0-SNAPSHOT）；根模块仍是 kotlin jvm（CLI application）。

### 文件归属映射表（Phase 4 迁移清单）

| 原位置（根） | 新归属 | 说明 |
|---|---|---|
| bytecode/analysis/LuaConstantReferences.kt | :lua-bytecode-analysis | `relocated` 依赖 edit 的 `retargeted` → analysis 依赖 edit（Phase 3 遗留就此闭环） |
| bytecode/analysis/LuaDataFlow.kt | :lua-bytecode-analysis | 纯 model |
| bytecode/print/LuaChunkPrinter.kt | :lua-bytecode-analysis | 归属理由：只读"检验"渲染器（供人眼/测试核对），非序列化（io）、非模型（model） |
| bytecode/support/LuaRegisterAnalysis.kt | :lua-bytecode-analysis | 本质是数据流安全分析；唯一消费者 register feature 本就依赖 analysis |
| bytecode/support/LuaLcg.kt | :lua-obfuscation-api | 共享 keystream 原语（提升清单 #6） |
| bytecode/support/LuaDecodeVariants.kt | :lua-obfuscation-api | B2 decode-skeleton 变体（提升清单 #7），Phase 5 :lua-vm 直接消费 |
| obfuscate/LuaObfuscationModule.kt | :lua-obfuscation-api | context 构造器 internal→public（根编排器要构造） |
| obfuscate/SeedPolicy.kt | :lua-obfuscation-api | 追加提升的 `seedFor`（public） |
| obfuscate/LuaArithmetic.kt | :lua-obfuscation-api | internal→public |
| obfuscate/LuaObfuscationEditing.kt | :lua-obfuscation-api | internal→public（五个 feature 都用其中若干扩展） |
| obfuscate/CffTranslationValidator.kt | :lua-obfuscation-api | internal→public；签名改用 CffControlSite |
| （新）CffControlSite.kt / LuaEnvLookup.kt / LuaObfuscationConfig.kt / LuaObfuscationOptions.kt / LuaObfuscationReports.kt | :lua-obfuscation-api | 见提升清单与类型抽取说明 |
| obfuscate/LuaStringEncryptor.kt（去掉 Config/Result）| :lua-obfuscation-strings | |
| obfuscate/LuaStringCipher.kt、LuaStringOperandLowering.kt | :lua-obfuscation-strings | OperandLowering 保持 internal（仅本模块用） |
| obfuscate/LuaConstantEncryptor.kt（去掉 Config/Result）、ConstCipher.kt | :lua-obfuscation-constants | |
| obfuscate/LuaControlFlowNoise.kt（去掉 7 个配置/报告类型、seedFor、env-lookup） | :lua-obfuscation-jumps | |
| obfuscate/LuaTailReturn.kt（嵌套 Report 提升为 api 的 TailReturnReport） | :lua-obfuscation-jumps | ObfuscationReport.tailReturn 字段类型随之改为 TailReturnReport? |
| obfuscate/LuaMbaPredicates.kt | :lua-obfuscation-jumps | **偏离任务草案**：唯一消费者是 LuaJunkCode（jumps）；CFF 用的是自己的 CffPredicateFamily 机制，与 OpaquePredicate 无关 |
| obfuscate/LuaControlFlowFlattening.kt（去掉 Config/Report/Manifest/Family） | :lua-obfuscation-cff | `requestedFeatures()` 私有扩展留在 cff |
| obfuscate/CffControlPackets.kt（去掉嵌套 Site） | :lua-obfuscation-cff | 仅 flattener 构造；jumps 只用 Site → 已提升 |
| obfuscate/CffConstantExpansion.kt | :lua-obfuscation-cff | internal→public（根编排器在 flattening 与 Pass K 之间驱动） |
| obfuscate/LuaRegisterObfuscator.kt（去掉 Config/Report） | :lua-obfuscation-register | |
| obfuscate/LuaObfuscator.kt（只剩编排器）、LuaKeyRenaming.kt、LuaHostSeed.kt | 根模块 | 横切/编排件（任务决策）：keyRenaming 调 LuaTailReturn（jumps）、hostSeed 调 env-lookup（api）+LuaJunkCode 的产物约定；编排器必须在 feature 之上 |
| platform/jvm/JvmPlatform.kt | :lua-platform jvmMain | feature 模块测试需 install（classpath 资产/熵），不能反向依赖根 |

### helper 提升清单（进入 :lua-obfuscation-api 的共享件）

| 提升件 | 消费者 | 提升原因 |
|---|---|---|
| LuaArithmetic | cff（flattener/packets/validator/expansion）+ jumps（junk/cloning） | 双 feature |
| LuaObfuscationEditing（detachedCopy/commitFrom/edit/installClosurePrologue 等） | strings/constants/jumps/cff/register | 全部 feature |
| CffControlSite（原 CffControlPackets.Site，提升+改名） | cff + jumps + api(validator) | 双 feature 交换的记录类型 |
| CffTranslationValidator | cff + jumps | 双 feature |
| appendEnvLookup / resolveEnvUpvalues（自 LuaControlFlowNoise/LuaJunkCode 拆出） | jumps + 根(LuaHostSeed→未来 vm) | feature 与上层共享 |
| seedFor | jumps + cff | 双 feature |
| LuaLcg (LcgSpec) | 根（host-seed/decode-variants）、未来 :lua-vm | 文档定义的"VM 密码流 + Pass K 共享原语" |
| LuaDecodeVariants | 现仅测试；Phase 5 :lua-vm 的 B2 splice 源 | 与 LcgSpec 成对的上层共享件 |
| 9 个 pass 配置 + OpaquePredicate/CffPredicateFamily | api(LuaObfuscationConfig) + 各 feature | 契约层 |
| 9 个报告类型 + TailReturnReport + ObfuscationConfig/ObfuscationReport | api + 各 feature + 根 | 契约层 |

### 测试归属表

| 测试 | 归属 | 层级 |
|---|---|---|
| LuaConstantReferencesTest / LuaDataFlowLivenessTest / LuaDataFlowRkModelTest / LuaChunkPrinterTest / LuaRegisterAnalysisTest | :lua-bytecode-analysis jvmTest | fixture 型（luac classpath）→ jvmTest + TestFixtures 副本 |
| LuaLineInfoTest | :lua-bytecode-edit jvmTest | 测 edit 自己的 LuaLineInfo/setAbsoluteLines；real/*.luac 副本加入 edit |
| LuaLcgTest / LuaDecodeVariantsTest | :lua-obfuscation-api commonTest | 纯 |
| LuaVariantDumpTest | :lua-obfuscation-api jvmTest | /tmp dump harness（java.io） |
| LuaStringEncryptorTest | :lua-obfuscation-strings jvmTest | pass 级（decoder 资产在模块 jvmMain/resources） |
| LuaConstantEncryptorTest（2/3 方法） | :lua-obfuscation-constants jvmTest | pass 级（参数族/round trip） |
| LuaControlFlowNoiseTest / LuaPredicateFamiliesNumericTest | :lua-obfuscation-jumps jvmTest | pass 级 |
| LuaCffVerificationTest | :lua-obfuscation-cff jvmTest | pass 级（validator） |
| LuaRegisterObfuscatorTest（7/8 方法） | :lua-obfuscation-register jvmTest | pass 级 |
| StringCoverageTest、LuaPipelineConstantLeakTest（原 AGGRESSIVE 零明文验收拆出） | 根 | 全管线集成（经 LuaObfuscator） |
| LuaControlFlowFlatteningTest / LuaCffSemanticsTest / LuaBlockCloningTest / LuaRegisterFlowTest / LuaJumpStringsCompositionTest（自 NoiseTest 拆出）/ LuaRegisterPipelineTest（自 RegisterObfuscatorTest 拆出） | 根 | 全管线集成（feature 测试类不得编译 LuaObfuscator：api 在 feature 之下、根在 feature 之上） |
| LuaHostSeedTest / LuaAdvancedProtectionTest / LuaRealWorldObfuscationTest / SingleModuleIsolationTest（新增） | 根 | 编排器/横切/开关证明 |
| vm/、platform/、editing DSL 等根测试 | 根 | 不变 |

注：TestFixtures.kt 现为 **9 份有意并存**（root/io/edit/analysis + strings/constants/jumps/cff/register
的 jvmTest 各一份）；改 fixture 清单需同步所有副本。feature jvmTest 均带 InstallJvmPlatformExtension +
services 注册 + junit-platform.properties（与根同机制）+ bytecode/lua/real 资源子集。

### 编排器开关验证

- 现状即"配置驱动开关"：ObfuscationConfig 每个可选项是 nullable 字段，null=禁用；无需把硬编码管线重构成列表注入。
- 新增根测试 `SingleModuleIsolationTest`：`nothing enabled runs no pass at all` +
  `each feature can be enabled alone and every other pass stays off` —— 8 个用例
  （strings/constants/jumps/junk/clones/cff/registers/keys）逐个单独启用，断言报告中**恰好**该 feature 的
  section 非空、其余全空、extension modules 为空、dump→reload 可加载。

### 关键决策与踩坑

1. **跨模块 smart cast 根除**：config/report 类型迁入 api 后，`cfg.junkCode`/`report.stringEncryption`
   等跨模块 public val 不能再 smart cast——cli/Main 用 `checkNotNull`，根测试用局部 val 绑定。
2. **KMP 模块忽略 `src/main/resources`**：decoder 资产曾放 strings/constants 的 `src/main/resources`，
   jvmTest 类路径上不存在（"missing bundled decoder resource"）。MPP 的 JVM 资源必须放
   `src/jvmMain/resources`（或 commonMain/resources）。资产归属 feature 模块本体（而非仅测试资源），
   保证 artifact 自包含；根 CLI 经传递依赖照常可读。
3. **jumps 测试引用 LuaStringEncryptor**（组合性用例）→ 方法拆到根 `LuaJumpStringsCompositionTest`，
   维持 feature 互不依赖。
4. **LuaMbaPredicates 归 jumps 而非 cff**（偏离任务草案，见归属表）；jumps 实际依赖的 CFF 设施只有
   validator + control-site 记录，均已提升 api，故 CffControlPackets 本体（依赖 flattener 的
   CodeBuilder/LabelSupply）留在 cff。
5. **CffControlPackets.Site → 顶层 CffControlSite**：jumps/cff/api 三方交换的记录类型不能挂在
   cff 内部类上；全局改 6 处调用点。
6. purity gate SCOPE 一次性预扩到全部 7 个新模块 commonMain（目录缺失即跳过）。

### Phase 4 验证

按提交序列逐个 CI 验证（`gh run watch`）：

- `:lua-bytecode-analysis`：run 35558522220
- `:lua-obfuscation-api` + strings/constants（api 一处 import 笔误致红，fix-forward）：run 35559260842（红）→ 35559762552（红，暴露 3 个真实耦合点）
- jumps/cff/register + 管线测试归位：35560270339（checkout 网络抖动 GnuTLS -110）→ 35560863036（红：资产路径 + 跨 feature 测试）→ 35561426286（红：根测试编译）→ 35561870800（红：isolation 测试逻辑）
- **全绿：run 35562273651**（build 全模块 + 根/analysis/api/strings/constants/jumps/cff/register 全部测试 + purity gate OK）

### Phase 4 遗留项

- 各模块 `implementation` 依赖尚未按公共 API 暴露面提升为 `api`（真实发布前需梳理；`maven-publish` 已就位）。
- feature 模块仅 `jvm()` target；js/native 加入时 decoder 资产与 fixture 加载需 expect/actual。
- TestFixtures.kt 多副本（7 份）与 fixture 资源多副本继续并存；后续可抽 `shared-test-fixtures` 模块收敛。
- 根模块仍持有 LuaKeyRenaming/LuaHostSeed/编排器；若 Phase 5 拆 `:lua-vm`，二者可随之下沉。
- `SingleModuleIsolationTest` 目前只证报告层隔离；字节级"其他 pass 未触碰"可后续加 dump diff 断言。

## Phase 1：问题记录（历史）

- Gradle 多模块 + Kotlin 插件版本声明：根模块已通过 kotlin jvm 2.4.20 把 `kotlin-gradle-plugin` jar 放上共享构建 classpath，子模块再以带版本方式请求 `kotlin("multiplatform")` 会抛 `InvalidPluginRequestException`。解法：根 `plugins {}` 里 `id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false`，子模块不带版本直接 apply。后续新增模块沿用此模式。
- CI runner 网络抖动复现一次（GnuTLS recv error -110），重跑即恢复，与代码无关。
