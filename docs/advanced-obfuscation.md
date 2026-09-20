# 高级 JMP、字符串／常量与随机 MBA

## 编辑器与模块架构

所有结构性字节码修改都应通过 `bytecode.edit`：`LuaChunk.editing()` 提供可链式的原型遍历，`LuaInstructionStream` 统一 ABC、ABX、ASBX、AX、SJ 和标签跳转，`LuaExpr` 及 `emitExpression` 统一 MBA 表达式编译与 5.4/5.5 的 MMBIN 配对。混淆 pass 只负责选择算法和布局，编辑器负责版本编码、跳转重定位、行号表和局部变量范围维护。

扩展功能实现 `LuaObfuscationModule`，通过 `ObfuscationConfig.modules` 注册，在核心管线前或后运行；模块可从 `LuaObfuscationModuleContext.edit()` 获取同一套流式编辑器，并用 `attributes` 保存运行期状态。内置 pass 与外部模块共享这套 API。

`ObfuscationConfig.AGGRESSIVE` 请求本页的高级组合。每个 pass 仍能单独配置；实际执行数量与跳过原因由报告给出，不能把配置请求当作覆盖证明。

## JMP 与不可达块

`--jmp-advanced` 开启 `runtimeRoutes`，每层有两条乱序路径，读取已初始化参数的 Lua 真值选择后继。管理区不调用函数、不读环境、不跨原 CALL 保存私有值。无固定参数时保留已验证的静态链，并记录原因。

`--jmp-decoys N` 控制每个原型的不可达块数量（0–1024）。块内包含交叉入口与重叠循环区间，FORPREP/FORLOOP 保持版本方向约束；所有字均为合法 Lua 指令；可执行区域没有边进入该区域，末尾也不能 fall-through 进去。它们旨在增加结构恢复成本，并不保证任意反编译器崩溃。

每个候选在副本中生成，检查寄存器容量、编码范围、原生取指配对和目标。独立解释最终管理指令，遍历 nil/false/true 以及两个路径入口，要求有限步到达原目标。原 5.3 JMP 的 A 字段保留，附加跳转 A=0；后向循环和 TFORPREP 的原生约束保持。失败时保留原型并报告，避免偏移超限后留下半改写代码。

## 随机 MBA

支持 `MBA_CARRY_CHAIN`、`MBA_BORROW_CHAIN`、`MBA_MASK_SELECT`、`MBA_PRODUCT_PARTITION` 与 `NON_SQUARE`，`MIXED` 按站点随机选择。每个实例有不同的运行期整数投影、掩码、奇数系数、移位和旋转；基础关系再叠加 2–5 层可逆变换，两侧独立选择等价编码。中间结果进入下一层，最终结果参与路径比较。用 64 位回绕整数运算，并保持 5.4/5.5 算术和 MM 尾指令配对。

生成后独立执行所有真值输入的指令序列，检查最终真假与声明一致。测试还翻转最终比较极性，要求验证器拒绝错误实例。旧的 SIMPLE_NIL、XOR_SELF、奇偶、单层 MBA、SHIFT 和模 3 模板已删除；已删除旧的 `opaqueSeed`／`runtimeSecret`／`identityRounds`／`seededSelectors` 和常量加密 `runtimeSeed` 配置，以及 CLI 的 `--opaque-seed`；不再调用 `_VERSION`、`tostring` 或 `math.random`，也不回退为常量种子。

这是提高表达式分析成本的实现，不是 MBA 求解器不可破解的证明。原参数在某些控制边上仍可能被条件限定，读取运行值不等于每个站点都有独立熵。

## 字符串、全局与字段

`--encrypt-keys` 或 `StringEncryptionConfig(minLength=1, encryptKeys=true)` 将支持的常量操作数降为寄存器操作，再进行密文替换和运行期解密。覆盖全局读写、字段读写、SELF 方法调用、字符串表值和比较；同一个常量所有用途都安全支持时才加密。局部变量不会被错误改成 `_ENV` 全局；原始 upvalue 绑定和元方法操作次序保留。

5.4 SELF 支持寄存器键；5.5 SELF 改写为保存 self 与 GETTABLE，不能照搬 5.4。EXTRAARG 的建表容量不是常量索引。LOADKX 和附加字按原子对处理。两份已解密操作数放在调用窗口下方，避免第二次解密覆盖第一次结果。

每条常量有独立 seed，每个 chunk 有不同的完整周期 LCG 参数。解密器仍可见且密钥材料随产物分发，因此这是可逆混淆，不是密码学意义上的秘密保存。解密依赖正常的 `string.byte/char` 和 `table.concat`；修改这些标准库函数、观察精确分配／调试 hook 时，不承诺透明。

`remainingPlaintextStrings` 明确报告未处理的原程序字符串条目。短于阈值、预算外、仍用于不支持的操作数（例如 Lua 5.5 ERRNNIL 错误名）的字符串会保留，解密器自身的标准库名也不做自举加密。字段加密不使用可能破坏跨原型调用的字段重命名。

## 数字与布尔常量

`--constants` 或 `ConstantEncryptionConfig()` 对 LOADK/LOADKX 的数字和布尔常量使用类型标签加 8 字节载荷。整数完整保留 64 位；浮点保留 IEEE 754 原始位，包含负零和非正规数。三个版本的 decoder 从 `tools/decode_num.lua` 编译，依赖 `string.unpack`，不再使用旧的 5 字节截断格式。解密器初始化在 VARARGPREP 后执行一次，所有原跳转和调试 PC 统一重定位。

CFF 活跃控制包内禁止注入 CALL；这部分仅用经过验证的整数 XOR 局部展开，并同步更新最终证书。共享 RK、opcode 内嵌立即数、不满足安全展开条件的常量仍保留；不能把本选项称为所有数字都无明文。字符串和常量操作在工作副本完成后整体提交。

## 验证入口

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test installDist
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  bash tools/verify-cff.sh --combinations --report /tmp/cff-matrix.json
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  python3 tools/verify-jump-decoys.py --report /tmp/jump-decoys.json
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  sh tools/verify-runtime-predicates.sh
```

`LuaAdvancedProtectionTest` 对 Lua 5.3.6/5.4.6/5.5.1、三个 seed、独立／aggressive 组合进行原生执行差分，并检查关键字段明文消失、动态 JMP 和不可达块实际生成。另有偏移超限回滚、寄存器不足回滚、闭包关闭、数值与泛型循环、开放多返回值、方法／元方法、局部环境遮蔽和完整数值表示测试。

反编译结果必须与同版本原始样本对照；不能把工具本来就不支持的 Lua 版本计作抵抗收益，也不能把不合法字节码的解析失败当作成功。

2026-09-18 验证记录：删除旧配置后的完整测试为 204 项通过、零失败、零跳过；更新后的大模块脚本在三版 Lua 上 12/12 通过，运行期谓词契约入口 5/5 通过。完整 CFF 组合矩阵 612/612 执行结果一致；最终不可达循环块和 JMP 尾层调整后，额外复跑高级组合 180/180 通过。

不可达块隔离测试的 18 项原生执行检查全部一致；Lua 5.3/5.4 的 6 个样本在不开启不可达块时可反编译，开启 24 个块后当前 unluac 均抛出异常。Lua 5.5 原始样本就不受该工具支持，不计收益。工具 SHA-256：`98be0fa84ac73ca66dce2842a2e4512226f4c611b6500dc96415571fc5538fcc`。

## 编辑 DSL 架构

混淆 pass 的指令构造统一经过 `bytecode.edit`：单条指令使用 `LuaInstructionFactory`，连续指令使用 `LuaInstructionStream`，跳转通过标签解析并按 Lua 版本重新编码。表达式混淆使用 `LuaExpr` 和 `LuaExprBuilder`，由编辑流负责寄存器分配和 5.4/5.5 的 `MMBIN` 配对。原型代码、常量池和调试信息的结构性修改通过 `LuaPrototypeScope` 完成，模块化扩展通过 `LuaObfuscationModule` 接入核心管线。
