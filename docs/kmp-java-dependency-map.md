# KMP Java 依赖地图（Phase 2 结算）

本文档记录 Phase 2（roadmap 第 2 步）完成时，根模块对 JVM API 的替换方式、
保留的豁免面，以及各平台接缝在后续模块拆分时的 expect/actual 规划。
契约背景见 `docs/roadmap-kmp-modules.md`，进度见 `docs/roadmap-kmp-progress.md`。

## 门禁

`tools/verify-kmp-purity.sh` 在 CI（`verify.yml` 的 "KMP purity gate" 步骤）
中对 `bytecode/ obfuscate/ vm/ platform`（platform/jvm 除外）做注释剥离后的
`java.*` / `javax.*` / `::class.java` / `kotlin.jvm.*` / `@JvmStatic` /
`@JvmOverloads` / `@JvmField` / `System.getenv|err|out|getProperty` 扫描。
Kotlin 块注释按嵌套语义剥离，KDoc 中描述"替代了什么"的文字不算违规。

## 替换总表

| 原 JVM 依赖 | 替换 | 位置 | 说明 |
|---|---|---|---|
| `java.util.Random` | `platform.ObfRandom`（继承 `kotlin.random.Random` 的 48-bit LCG 克隆） | 17 个 pass 文件 + 12 个测试文件 | 逐方法位一致克隆（含 JDK 17 `RandomGenerator` 的 `nextInt(origin,bound)` / `nextLong(bound)` 默认方法与 `nextBytes` 的 4 字节/`nextInt` 消耗模式）；`ObfRandomParityTest` 与 JVM 参考实现做差分 |
| `Collections.newSetFromMap(IdentityHashMap)` | `platform.IdentitySet` | LuaObfuscator、LuaStringEncryptor ×2、LuaConstantEncryptor ×2 | 引用相等语义（`===`）；线性扫描（键集为单 chunk 的 prototype 集合，规模小） |
| `java.util.IdentityHashMap` | `platform.IdentityMap` | LuaKeyRenaming ×2、LuaControlFlowNoise.resolveEnvUpvalues、LuaConstantEncryptor ×3、LuaStringEncryptor ×2 | 同上；`containsValue` 用值相等（同 JDK） |
| `java.nio.ByteOrder` | `bytecode.LuaByteOrder`（BIG/LITTLE + displayName/flipped） | LuaChunkHeader（公共字段）、LuaChunkReader、LuaChunkWriter、LuaChunkLoader 及测试 | 公共 API 类型变更；model 模块化后随 `LuaChunkHeader` 移入 commonMain |
| `java.nio.charset.StandardCharsets` | `encodeToByteArray` / `decodeToString`（U+FFFD 语义一致）/ latin1 手写字节循环 | LuaString | 同 JVM 行为：UTF-8 非法序列替换 U+FFFD |
| `String.format("%02x"/"%02X"/"%X"/"%0Nx")`、`Long.toHexString` | `platform.hex2 / hex2Upper / hexUpper / hexLower / hexPadded` | loader 错误消息、printer 转义、decoder 资产名、register 后缀、LuaVmInterpreterGenerator | model 模块内的 `"0x%02X".format`（LuaBytecodeVersion / LuaInstruction）用 `kotlin.text.format`（common stable），无需替换 |
| `java.security.MessageDigest`(MD5) | `platform.Md5`（RFC 1321 纯实现，内嵌轮常量） | LuaDecodeVariants structuralMd5/rawMd5 | `Md5ParityTest`：RFC 向量 + 与 JVM MessageDigest 差分（含 55/56/57/63/64 字节等 padding 边界） |
| `Math.floorDiv / floorMod` | `x.floorDiv(y)` / `x.mod(y)`（package `kotlin` 扩展） | LuaDecodeVariants、CffTranslationValidator、LuaVmInterpreter | 注意：`kotlin.math` 没有两参顶层函数 |
| `Math.multiplyExact` | Long 中间量 + 显式溢出 guard | LuaControlFlowNoise | |
| `Math.pow` | `kotlin.math.pow` | LuaVmInterpreter | |
| `java.util.ArrayDeque` | `kotlin.collections.ArrayDeque` | LuaRegisterObfuscator ×2 | `add` / `removeFirst` 语义一致 |
| `Double.doubleToLongBits` | `doubleToCanonicalBits`（NaN → `0x7ff8000000000000`，其余 `toRawBits()`） | LuaVmProgram encodeConstantBytes | 决策：保留 doubleToLongBits 的 NaN 规范化而非直接 `toRawBits()`，保证 NaN 常量的加密 payload 与既有输出字节一致（否则只会改变 NaN 输入的行为，但为了稳妥选择零行为差） |
| `java.io.ByteArrayOutputStream` | `List<Byte>` + `toByteArray()`（Pass K blob）/ 写手 `ByteSink`（writer） | LuaConstantEncryptor、LuaChunkWriter | |
| `java.io.File/InputStream/OutputStream`（io 层便捷重载） | `platform.jvm.LuaChunkIoJvm` 扩展（同名同调用形态） | LuaChunkLoader/File+Stream、LuaChunkWriter/File+Stream、`editChunkFile` | 核心 `load(ByteArray)` / `dump(chunk): ByteArray` 留在 bytecode.io；调用方补 `platform.jvm` import |
| `java.security.SecureRandom` | `platform.SecureEntropy` 接口 + `platform.jvm.JvmPlatform` 实现 | SeedPolicy（无 --seed 路径）、cli/Main VM 分支 | 公共默认 = `kotlin.random.Random.Default.nextLong()`（进程内新鲜熵，PRED-2 语义保持） |
| classpath 资产 `/obfuscator/decode_*.luac` | `platform.BundledChunkSource` 接口 + JVM classpath 实现 | LuaStringEncryptor、LuaConstantEncryptor | 公共默认 = 无资产（返回 null） |
| `System.getenv` / `System.err`（44 处调试开关与日志） | `platform.DebugLog`（enabled/value/log） | CFF、K、B4、KEY、JUNK、XPASS、VM 等全部 pass | 公共默认 = 全关且静默 |
| `/tmp/vm-subset-on` 哨兵 + `/tmp/vm-subset.txt` 追加 | `platform.TraceSink` 接口 + JVM 实现（同文件、同开关、同换行） | LuaVmTranslator 3 处 | 能力等价保留 |

## 装配点

* **CLI**：`cli/Main.kt` 的 `main()` 首行调用 `JvmPlatform.install()`。
* **测试**：`InstallJvmPlatformExtension`（JUnit 扩展，经
  `META-INF/services` + `junit-platform.properties` 的 autodetection 全局注册）
  在每个测试类前安装，等价于 CLI 行为。
* **库宿主**：调用 `JvmPlatform.install()`（JVM）或在 js/native 提供各自实现。

## 豁免面（本轮明确不动，属 jvmMain/legacy 范畴）

* `cli/Main.kt` — CLI 入口（可含 `java.io.File`、`SecureRandom` 等）。
* `decompile/ExternalDecompiler.kt` — 进程编排（`UNLUAC_JAR`、临时目录）。
* `legacy/stringfog/*` — 源码级遗留。
* 顶层 `B4Diag.kt`、`B4EffectRunner.kt` — 调试/差分工具。
* `platform/jvm/*` — JVM 适配器本体。
* 测试源码（JVM 测试可以随意用 JVM API，如 `MessageDigest`、`java.nio.file.Files`）。

## expect/actual 规划（模块拆分时）

| common 接口/类 | 拆分时的处理 |
|---|---|
| `SecureEntropy` | 不转 expect：common 只保留接口 + `Platform.entropy` 装配槽；jvmMain actual 由 `SecureRandom` 实现，js/native 各自实现 |
| `BundledChunkSource` | 同上；jvmMain = classpath，jsMain = fetch/打包资产，nativeMain = 打包资源 |
| `DebugLog` | 同上；jvmMain = env + stderr，jsMain = console + query 参数，nativeMain = environ + stderr |
| `TraceSink` | 同上；仅 jvmMain 提供文件哨兵实现，其余平台默认丢弃 |
| `ObfRandom` | 纯 common，原样进 commonMain（已是 `kotlin.random.Random` 子类） |
| `LuaByteOrder` / `LuaString` / `Md5` / `IdentitySet` / `IdentityMap` / `Hex` | 纯 common，原样搬入 `:lua-bytecode-model` / 公共模块 |
| `LuaChunkIoJvm`（load/dump 文件扩展） | 留在 jvmMain；commonMain 只保留 `load(ByteArray)` / `dump(): ByteArray` |

## 已知遗留风险

1. `IdentityMap`/`IdentitySet` 的线性扫描是 O(n)，若未来键集规模超过几千需换
   身份哈希实现（common 无 `identityHashCode`，届时按平台 actual 提供）。
2. `IdentityHashMap<Int, …>`（LuaKeyRenaming.collectKeyReferences）在 JVM 上的
   "装箱身份" 行为（>127 的 Integer 装箱不唯一）被 IdentityMap 的 `===` 语义
   原样保留；js/native 数字无装箱概念，语义以 JVM 为准记录在案。
3. `ObfRandom` 未覆盖 `nextLong(from, until)` / `nextDouble(from, until)`：
   这两个走 `kotlin.random.Random` 默认实现，种子确定但不与 JDK 对齐；当前
   无调用点，禁止新增使用（差异测试会作为回归防线扩展）。
4. `Iterable.shuffled` / `MutableList.shuffle` 依赖 `ObfRandom <: 
   kotlin.random.Random` 使 common 实现接管（绘制次序与 JDK
   `Collections.shuffle` 逐位一致，已由 `ObfRandomParityTest` 固定）。
