# lua-bytecode-obfuscator

## 代码只供学习使用，严禁基于此代码进行任何商业活动。

Lua 字节码工具链（从 [LuaStringFog](https://github.com/dingyi222666/LuaStringFog) 迁移而来）：
**解析（load）→ 编辑（edit）→ 写出（dump）** 标准 Lua 5.3 / 5.4 / 5.5 字节码，
为后续的字节码混淆打基础。

## 进度

- [x] Lua 5.3 / 5.4 / 5.5 chunk 解析（undump），含 5.5 的字符串去重表与对齐
- [x] chunk 写出（dump）：未修改的 chunk 可以**逐字节还原**
- [x] 编辑 API + Kotlin DSL（常量、指令、行号、upvalue、调试信息）
- [x] 操作码表 + 操作数模式表（5.3 47 / 5.4 83 / 5.5 85 个），按模式语义化格式化操作数
- [x] **字符串 / 常量池加密**（真实 luac 预编译 decryptor + upvalue 串联，已用真实 Lua 执行验证）
- [x] **JMP 跳板**（跳转间接化）与**花指令 / 虚假控制流**（可独立配置、可叠加；
      谓词按 Collberg POPL'98 的整数恒真式实现，结构按 OLLVM BogusControlFlow 的收敛式设计）
- [x] `decompile` 包：对接 unluac 用于度量混淆强度
- [x] 统一控制流平坦化：运行期控制包、命名空间、多/嵌套 dispatcher、分段更新、
      网关、洗牌、切分及受契约约束的表/图候选；[配置与验证范围](docs/ccf-implementation.md)
- [x] VM 混淆产物：自定义 ISA、加密程序/常量池、生成 Lua 解释器；
      泛型 for、关闭、元方法、协程和 5.5 vararg 的[支持范围与验证](docs/vm-runtime-support.md)
- [ ] VM 设计文档的完整保护策略与全语义覆盖

## 快速开始

默认 CLI 是 **Native CLI**（roadmap 第 6 步验收：JVM 入口不再是默认
application artifact）。构建并运行：

```bash
./gradlew :lua-cli-native:linkReleaseExecutableNative
build/lua-cli-native/bin/native/releaseExecutable/lua-cli-native.kexe --help
# 示例：
lua-cli-native.kexe chunk.luac --obfuscate balanced --seed 7 --dump out.luac
lua-cli-native.kexe chunk.luac --vm-out interpreter.lua
```

JVM 入口（`cli/Main.kt`，flag 超集：`--summary`、`--replace-string`、
`--verify` 等）保留为库入口（不再是 application artifact）：把根模块及其
各 KMP 模块 jar 全部放上类路径后直接调用
`io.github.dingyi222666.luabytecode.cli.MainKt`，或用 Kotlin/IDE 直接跑。

```kotlin
val chunk = LuaChunkLoader.load(File("script.luac"))

println(chunk.version)                    // LUA_5_5
println(chunk.prototypes.size)            // 主函数 + 内嵌函数
val main = chunk.mainPrototype
println(main.constants)                   // [Str(hello world), Integer(42), ...]
println(main.lineForPc(3))                // 指令 3 所在的源码行
println(LuaChunkPrinter.print(chunk))     // 类 luac -l 的清单
```

### 2. 加载 → 编辑 → 写出

```kotlin
val chunk = LuaChunkLoader.load(File("script.luac"))

chunk.edit {
    // 遍历所有原型（主 chunk 在前，深度优先）
    eachPrototype {
        replaceStringText("secret", "obfuscated")
    }

    main {
        patch(3) { it.withA(0) }
        append(instruction { opcode = opcode("RETURN"); a = 0; b = 1; c = 1; k = 1 }, line = 1)
    }
}

LuaChunkWriter.dump(chunk, File("script.obfuscated.luac"))
```

一行式（load + edit + dump）：

```kotlin
editChunkFile(File("in.luac"), File("out.luac")) {
    eachPrototype { strip() }        // 等价于 luac -s
}
```

命令行（Native CLI）同样可以完成日常混淆（`--dump` 后会立刻重新解析校验产物）：

```bash
lua-cli-native.kexe in.luac --strings --jmp=3 --dump out.luac
```

### 3. 直接用 Lua 验证

```bash
luac -o script.luac script.lua
lua-cli-native.kexe script.luac --dump roundtrip.luac
lua roundtrip.luac        # 与原脚本输出一致
lua script.luac
```

## 混淆（pass 独立配置）

每个 pass 都能单独开关、单独调参，也可以直接用预设组合：

```kotlin
val chunk = LuaChunkLoader.load(File("script.luac"))

// 只要字符串加密
LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
    stringEncryption = StringEncryptionConfig(minLength = 6),
))

// 只要跳板 + 花指令，不要加密
LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
    jumpIndirection = JumpIndirectionConfig(depth = 3, junkPerHop = 2),
    junkCode = JunkCodeConfig(junkPerBlock = 4, guardedBranches = 4, deadBlockSize = 6, unreachableBlocks = 4),
))

// 预设：light / balanced / aggressive
LuaObfuscator.obfuscate(chunk, ObfuscationConfig.AGGRESSIVE)

LuaChunkWriter.dump(chunk, File("script.obf.luac"))
```

各 pass 也可单独调用（`LuaStringEncryptor.encrypt` / `LuaJumpIndirection.apply` / `LuaJunkCode.apply`）。

| pass | 关键参数 | 作用 |
|------|----------|------|
| `LuaStringEncryptor` | `minLength` / `encryptKeys` / `maxConstants` / `seed` | 字符串密文与运行期解密；可覆盖全局名、字段名、方法名及共享比较字面量 |
| `LuaConstantEncryptor` | `maxConstants` / `seed` | LOADK/LOADKX 的整数、布尔和浮点常量；保留完整 64 位载荷 |
| `LuaJumpIndirection` | `depth` / `runtimeRoutes` / `decoyBlocks` | 乱序多跳、运行期双路径、真实路径不可达的交叉循环块；独立校验后提交 |
| `LuaJunkCode` | `guardedBranches` / `predicateFamily` / `seed` | 随机嵌套 MBA 与非平方关系；结果用于分支判断，不再生成常量／nil／自异或伪谓词 |
| `LuaControlFlowFlattening` | `BASELINE` / `FULL` / 独立特性 | 统一 CFF 内核、运行期控制包和逐原型翻译验证 |
| `stripDebugInfo` | — | 最后剥离行号、局部变量、upvalue 名和 source |

高级组合与独立选项：

```sh
build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator input.luac \
  --obfuscate aggressive --seed 42 --cff-report cff.json --dump protected.luac

build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator input.luac \
  --encrypt-keys --constants --jmp-advanced --jmp-decoys 24 \
  --junk 4 --predicate MIXED --dump protected.luac
```

`aggressive` 开启短字符串／字段名加密、常量加密、动态 JMP、12 个不可达块、随机 MBA 和 FULL CFF。省略 `--seed` 每次生成不同结果；固定 seed 可复现。实际覆盖与跳过原因以报告为准；缺少可靠运行期参数的原型不生成常量替代谓词。

实现保留原 `_ENV`／局部变量绑定及元方法顺序，`a.b=c` 对应解密键后的表访问，不是字段改名。原型工作副本防止字符串／常量／JMP 失败时残留半成品；解密器初始化放在 `VARARGPREP` 之后并重定位跳转。Lua 5.3 JMP 的关闭 upvalue 副作用仍由原跳转执行；5.4/5.5 的后向 FORLOOP/TFORLOOP 不接到末尾跳板，TFORPREP 仍直达 TFORCALL。

运行库依赖、保留明文的边界、随机 MBA 的验证与反编译测试说明见 [高级混淆说明](docs/advanced-obfuscation.md)；CFF 配置和证明范围见 [CFF 实现说明](docs/ccf-implementation.md)。

## API 一览

| 入口 | 说明 |
|------|------|
| `LuaChunkLoader.load(File/InputStream/ByteArray)` | 解析 chunk，可校验期望版本 |
| `LuaChunkWriter.dump(chunk[, File/OutputStream])` | 写回标准 luac 字节码（规范化输出） |
| `LuaChunk.edit { }` / `editChunkFile(in, out) { }` | 编辑 DSL |
| `LuaChunkPrinter.print(chunk)` | 可读清单 |
| `LuaOpcodes` / `LuaOpcodeModes` | 三个版本的操作码名与操作数模式（由 `lopcodes.c` 生成） |
| `LuaLineInfo` | 行号表编码/解码（与 `luaG_getfuncline` 一致） |

### 编辑 API（`LuaPrototypeEdit.kt`）

```kotlin
// 常量
prototype.indexOfStringConstant("print")        // -> Int，找不到返回 -1
prototype.internString("key")                   // 已存在则复用，返回常量下标
prototype.replaceStringConstants { it.replacing("a", "b") }   // 返回改写数量
prototype.forEachStringConstant { index, value -> }
val LuaConstant.stringValue: String?            // 只有字符串常量非空

// 指令（自动维护 lineInfo / absoluteLineInfo 与局部变量 pc 区间）
prototype.setInstruction(pc, instruction)
prototype.patchInstruction(pc) { it.withBx(7) }
prototype.insertInstruction(pc, instruction, line = 3)
prototype.insertInstructions(pc, listOf(...), lines = listOf(...))
prototype.appendInstruction(instruction)
prototype.removeInstruction(pc)
prototype.removeInstructions(3..5)
prototype.replaceInstructions(3..5, listOf(...))
prototype.transformInstructions { pc, instruction -> instruction }

// 其它
prototype.addUpvalue(LuaUpvalue(inStack = true, index = 0, kind = 0), LuaString.of("x"))
prototype.addNestedPrototype(child)
prototype.clearDebugInfo()                      // 单个原型去调试信息
chunk.clearDebugInfo()                          // 整个 chunk 去调试信息
```

指令是**值对象**：用工厂构造、用 `withXxx` 派生新值。

```kotlin
LuaInstruction.abc(version, opcode = add, a = 1, b = 2, c = 3, k = 0)
LuaInstruction.abx(version, opcode = loadK, a = 0, bx = 3)
LuaInstruction.asbx(version, opcode = loadI, a = 1, sbx = -12)
LuaInstruction.ax(version, opcode = extraArg, ax = 9)
LuaInstruction.sj(version, opcode = jmp, sj = -3)
LuaInstruction.vabc(version, opcode = newTable, a = 0, vB = 0, vC = 100, k = 0)
luaInstruction(version) { opcode = opcode("JMP"); sj = -3 }   // DSL，按模式取操作数
instruction.withA(5).withK(1)
```

### 编辑 DSL（`LuaChunkDsl.kt`）

```kotlin
chunk.edit {
    main { }                        // 主原型
    prototype(2) { }                // 按前序下标
    prototypeWhere({ it.constantCount > 10 }) { }
    eachPrototype { }               // 全部原型
    eachNestedPrototype { }         // 跳过主 chunk
    replaceStringConstants { prototype, value -> }
    strip()
}

LuaPrototypeScope:                // 每个原型的编辑作用域
    code / constants / upvalues / children / locals / lineInfo
    internString / internConstant / locate / locateString / stringConstants
    set / patch / insert / append / remove / replace / transformInstructions
    instruction(index) / instruction { opcode = ... } / opcode("MOVE")
    child(index) { } / eachChild { } / addChild / addUpvalue / strip
```

## 支持的格式

严格按官方 `lundump.c` / `ldump.c` 实现（Lua 5.3.6、5.4.6、5.5.1）：

| 项目 | Lua 5.3 | Lua 5.4 | Lua 5.5 |
|------|---------|---------|---------|
| chunk 头 | `ESC Lua` + 0x53 + format + `LUAC_DATA` + `int`/`size_t`/指令/整数/浮点宽度 + `LUAC_INT`(0x5678) + `LUAC_NUM`(370.5) | 同左，去掉 `int`/`size_t` 宽度 | 4 组「宽度 + 值」：`int`=-0x5678、`Instruction`=0x12345678、`lua_Integer`=-0x5678、`lua_Number`=-370.5 |
| 整数 / 长度字段 | 定长原生整数 | 7 位变长，**最后一字节**置 0x80 | 7 位变长，**除最后一字节外**都置 0x80 |
| 字符串 | 单字节长度，`0xFF` 转义后接 `size_t` | 变长长度 | 变长长度 + **去重表**（`size=0` 时跟一个索引，索引 0 表示 `nil`），并且长度包含结尾 `'\0'` |
| 整数常量 | 定长 `lua_Integer` | 定长 | **zig-zag 变长**（非负 `2x`，负数 `-2x-1`） |
| 常量标签 | `nil=0x00` `bool=0x01`+值 `float=0x03` `int=0x13` 字符串 `0x04/0x14` | `nil` `false=0x01` `true=0x11` `int=0x03` `float=0x13` | 同 5.4 |
| upvalue 描述 | `instack` + `idx` | `+ kind` | `+ kind` |
| `source` 位置 | 原型开头 | 原型开头 | **嵌套原型之后** |
| 变长参数标记 | 布尔 | 布尔 | `PF_*` 位（`isvararg = flag & (PF_VAHID\|PF_VATAB)`） |
| 调试行号 | 每指令一个绝对行号 `int` | 每指令 1 字节相对量 + 绝对锚点（两个变长整数） | 每指令 1 字节相对量 + 绝对锚点（**对齐后的裸 `int` 对**） |
| 对齐 | 无 | 无 | 指令表前 4 字节对齐、绝对行号表前 4 字节对齐 |
| 操作码 | 47 个，opcode 6 位 | 83 个，opcode 7 位 | 85 个：新增 `GETVARG`/`ERRNNIL`，`SHLI`/`SHRI` 顺序调换，`FORLOOP`/`FORPREP`/`TFORLOOP` 改成 `iABx`，`NEWTABLE`/`SETLIST` 用新的 `ivABC` 布局 |

其它行为：

- 通过 `LUAC_INT`（以及 5.5 的 `LUAC_INST`）自动探测字节序，支持 big endian chunk；
- 校验 chunk 头、`LUAC_DATA`、主闭包 upvalue 数与原型 upvalue 数一致；
- 所有计数都会检查剩余字节，损坏的 chunk 不会造成超大内存分配；
- 解析错误统一抛 `LuaBytecodeFormatException`（包含出错字节偏移）；
- dump 是**规范化**的：dumping 未修改的 chunk 会得到与原文件完全相同的字节；
  `lineInfo` 与指令数不一致、5.3 出现绝对行号、`upvalueNames` 数量不匹配时会明确报错，
  而不是写出 Lua 无法加载的 chunk。

## 测试与验证

fixture 全部由真实 `luac` 5.3.6 / 5.4.6 / 5.5.1 编译（源码在 `src/test/resources/lua/`）：

- 18 个 fixture：常量、闭包/upvalue、变长参数、嵌套函数、UTF-8 与超长字符串（>254 字节，
  触发 5.3 的 `size_t` 转义）、大 chunk（>127 常量/指令，触发多字节变长整数）、
  5.5 字符串去重、`luac -s` 剥离调试信息；
- 86 个单元测试，含**逐字节 round trip**、常量引用表对着真实 luac 输出的校验、
  以及混淆后 dump→reload 的合法性检查；
- 额外用真实 `lua` 5.3.6 / 5.4.6 / 5.5.1 执行「原始 / round-trip / 编辑后 / 混淆后」的 chunk，
  输出完全一致（balanced 与 aggressive 两种预设共 24 次执行）。

```bash
./gradlew test
```

## 命令行

```
lua-bytecode-obfuscator — Lua 5.3 / 5.4 / 5.5 bytecode toolkit

Usage:
  lua-bytecode-obfuscator <chunk.luac> [options]

Inspect:
  --expect <5.3|5.4|5.5>  Fail unless the chunk declares this version
  --summary               Only print chunk level information, no prototype tree
  --no-code               Do not list instructions
  --no-debug              Hide line info, locals and upvalue names

Obfuscate (all optional, combinable):
  --obfuscate <preset>          light | balanced | aggressive
  --encrypt-keys               Encrypt global, field and method names
  --constants                 Encrypt numeric / boolean pool loads
  --jmp-advanced               Verified runtime routes and unreachable blocks
  --jmp-decoys <n>             Unreachable blocks per prototype
  --strings[=<minLength>]       Encrypt string constants
  --jmp[=<depth>]               Route jumps through trampoline hops
  --junk[=<n>]                  Inject junk / dead branches
  --cff <baseline|full|stress>  Request CFF features with per-prototype verification
  --cff-features <list>         Enable/disable individual CFF features
  --cff-report <path>           Save actual features and downgrade reasons
  --cff-allow-allocation-effects Allow private allocation under the host contract
  --strip-debug                 Drop line info, locals, upvalue names and sources

Edit:
  --replace-string <from>=<to>  Rewrite the UTF-8 text of every string constant
  --strip                       Strip all debug information (like luac -s)
  --dump <out.luac>             Write the (edited) chunk back out
  --verify                      Re-dump in memory and compare with the input

Examples:
  lua-cli-native.kexe script.luac --obfuscate balanced --dump script.obf.luac
  lua-cli-native.kexe script.luac --strings --jmp=3 --junk=2 --dump out.luac
  lua-cli-native.kexe script.luac --vm-out interpreter.lua
```

> Native CLI 面向最小可用子集；`--summary`、`--replace-string`、`--verify`、
> `--cff-features` 等完整 flag 面保留在 JVM 库入口 `cli/Main.kt`（flag 对齐表
> 见 docs/roadmap-kmp-progress.md Phase 6 后半）。

## 目录结构

按职责分包（模型 / 装卸 / 分析 / 编辑 / 打印 / 混淆 / 反编译 / CLI 互不混杂）：

```
src/main/kotlin/io/github/dingyi222666/luabytecode/
├── bytecode/                    # 字节码数据模型与格式常量
│   ├── LuaChunk.kt / LuaPrototype.kt / LuaConstant.kt / LuaString.kt
│   ├── LuaChunkHeader.kt / LuaInstruction.kt / LuaUpvalue / LuaLocalVariable ...
│   ├── LuaBytecodeVersion.kt    # 版本枚举 + varint 风格 + 5.5 PF_* 标志
│   ├── LuaOpcodes.kt            # 5.3 / 5.4 / 5.5 操作码名表（由 lopcodes.h 生成）
│   ├── LuaOpcodeModes.kt        # 操作数模式表（iABC / iABx / iAsBx / iAx / isJ / ivABC）
│   └── LuaBytecodeFormatException.kt
├── bytecode/io/                 # 装卸（undump / dump）
│   ├── LuaChunkLoader.kt  LuaChunkReader.kt  LuaChunkWriter.kt
├── bytecode/analysis/           # 分析（只读算法）
│   ├── LuaConstantReferences.kt # K/RK 操作数表、jumpTarget、基本块划分
│   ├── LuaCodeRelocation.kt     # jumpTo / relocated / retargeted
│   └── LuaLineInfo.kt           # 行号表编解码（与 luaG_getfuncline 一致）
├── bytecode/edit/               # 编辑 API + DSL（原地修改）
│   ├── LuaPrototypeEdit.kt  LuaChunkDsl.kt
├── bytecode/print/              # 渲染
│   └── LuaChunkPrinter.kt
├── obfuscate/                   # 混淆（每个 pass 独立配置）
│   ├── LuaStringCipher.kt       # 与 tools/decode.lua 逐位一致的 keystream
│   ├── LuaStringEncryptor.kt    # 常量池 / 字符串加密
│   ├── LuaControlFlowNoise.kt   # LuaJumpIndirection（跳板）+ LuaJunkCode（花指令/虚假控制流）
│   └── LuaObfuscator.kt         # 流水线 + 预设（LIGHT / BALANCED / AGGRESSIVE）
├── decompile/                   # 反编译（对接 unluac，用于度量混淆强度）
│   └── ExternalDecompiler.kt
├── cli/                         # 命令行
│   └── Main.kt
└── legacy/stringfog/            # 原有源码级字符串混淆实现（保留）
```

资源：`src/main/resources/obfuscator/decode_<53|54|55>.luac`（由 `tools/decode.lua` + 真实 `luac` 预编译，
`tools/generate-decryptor.sh` 可重新生成）。

## 后续计划

- [ ] 指令模式语义化反汇编进一步提升（跳转目标、常量/upvalue 引用注释已具备）
- [ ] 常量/字符串池加密（把字符串常量改成运行时解密的调用）
- [x] 控制流混淆（CFF、运行期 JMP、多种随机嵌套谓词）
- [ ] 反混淆（反编译）方向探索
