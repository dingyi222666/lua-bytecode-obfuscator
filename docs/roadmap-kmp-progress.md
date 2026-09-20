# KMP 多模块迁移进度

依据契约文档 `docs/roadmap-kmp-modules.md` 执行，本文档由各阶段执行 agent 持续更新。
依赖方向必须保持单向：`model → io/edit/analysis → obfuscation-api → feature modules → vm/frontends`。

## 总阶段表

| Phase | 对应 roadmap 步骤 | 内容 | 状态 |
|---|---|---|---|
| 1 | 第 1 步 | 纯数据结构、opcode、常量和 prototype 移入 `:lua-bytecode-model`（commonMain） | 部分完成（2026-09-21） |
| 2 | 第 2 步 | `java.io` / `java.nio` / `java.util.Random` / `MessageDigest` 等替换为 expect/actual 或平台接口 | 未开始 |
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

### 遗留项（本轮未搬，Phase 2 处理）

| 文件 | 未搬原因 |
|---|---|
| `LuaString.kt` | 代码实际使用 `java.nio.charset.StandardCharsets`（`utf8` / `latin1` / `of`），属 roadmap 第 2 步的 `java.nio` 替换范围 |
| `LuaChunkHeader.kt` | 公开 API 属性 `byteOrder` 的类型是 `java.nio.ByteOrder`，需先抽象 endian 表示 |
| `LuaConstant.kt` | 自身无 import，但 `LuaConstant.Str` 引用 `LuaString`（未搬），传递依赖被阻塞 |
| `LuaChunk.kt` | 组合 `LuaChunkHeader`（未搬）；另有指向 `bytecode.io` 的 KDoc-only import（`LuaChunkLoader` / `LuaChunkWriter`） |
| `LuaPrototype.kt` | 组合 `LuaString`（未搬）；另有指向 `bytecode.io` 的 KDoc-only import（`LuaChunkLoader` / `LuaChunkWriter`） |

### 本阶段问题记录

- Gradle 多模块 + Kotlin 插件版本声明：根模块已通过 kotlin jvm 2.4.20 把 `kotlin-gradle-plugin` jar 放上共享构建 classpath，子模块再以带版本方式请求 `kotlin("multiplatform")` 会抛 `InvalidPluginRequestException`（"plugin is already on the classpath with an unknown version"）。解法：根 `plugins {}` 里 `id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false`，子模块不带版本直接 apply。后续新增模块沿用此模式。
- CI runner 网络抖动复现一次（GnuTLS recv error -110），重跑即恢复，与代码无关。

### Phase 2 提示

- `LuaString` 可用纯 stdlib 落地：`ByteArray.decodeToString()`（UTF-8，malformed → U+FFFD，与现行为一致）、`String.encodeToByteArray()`，latin1 用逐字节 `Char` 构造；届时移除 companion 上的 `@JvmStatic`。
- `LuaChunkHeader.byteOrder` 需要换成模型侧 endian 表示（如 enum），`bytecode/io` 中 `ByteOrder` 的读写点同步适配。
- 以上两项解决后，`LuaConstant` / `LuaChunk` / `LuaPrototype` 的传递依赖自动解除，即可全部移入 model 模块；`LuaChunk` / `LuaPrototype` 的 KDoc-only import（`LuaChunkLoader` / `LuaChunkWriter`）届时需改为纯文本文档或依赖倒置，避免 model 反向依赖 io。
- 若未来出现 Java 互操作需求，可在 Phase 6 的 jvmMain 侧再补 `@JvmStatic` 外观，不影响 commonMain。
