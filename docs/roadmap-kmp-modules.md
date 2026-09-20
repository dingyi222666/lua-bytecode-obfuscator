# KMP 与多模块迁移记录

这份文档记录当前 VM 工作之后的架构目标，便于后续恢复开发。它不是新的运行时契约。

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Lua 5.3/5.4/5.5 字节码模型 | 已完成（JVM 版） | 读取、编辑、写回和版本字段已存在。|
| VM translator / 加密程序 / 生成解释器 | 部分完成 | 已有多版本能力和拒绝机制；仍有 ISA 字段宽度、调试/GC 观察等边界。|
| 输入 chunk → 现有混淆管线 → VM lift | 已接入，按原型报告覆盖 | `Options.inputObfuscation` 会复制输入并调用 `LuaObfuscator`；已修复混淆注入 upvalue 未初始化导致的 VM 崩溃。完整 AGGRESSIVE 仍以实际 translator 接受/拒绝报告为准。|
| 生成 Lua 源码内部混淆/CFF | 已完成（轻量无 parser） | `LuaVmSourceObfuscator` 对 VM 入口调用做随机私有 dispatcher；它是源码级 staging，不宣称目标字节码级 CFF。|
| 生成解释器目标字节码级 CFF | 未完成 | 后续需要目标版本 `luac`/loader/editor/validator 闭环。|
| LuaVmIr | 已移除 | 暂不引入额外 IR；当前 translator 直接生成私有 VM ISA。|
| 单体 Gradle 工程拆分 | 未完成 | 现有源码仍在一个 JVM module。|
| commonMain KMP 核心 | 未完成 | 暂无 KMP source set。|
| jvmMain | 未完成 | 后续承载 JVM 文件、进程和测试适配。|
| jsMain 网页加密 | 未完成 | 后续提供浏览器 UI、Worker、版本/特性选择和下载。|
| nativeMain / Native CLI | 未完成 | 后续只发布 Native CLI，不再默认发布 JVM CLI。|
| 可选特性插件发布 | 部分完成 | 现有 `LuaObfuscationModule` 是 API 扩展点，但还没有独立 Gradle artifact。|

## 目标模块图

```text
:lua-bytecode-model       commonMain: model, opcode, constants, prototype
:lua-bytecode-io          commonMain: reader/writer; platform adapters in jvm/js/native
:lua-bytecode-edit        commonMain: editor DSL, instruction factory, expressions
:lua-bytecode-analysis    commonMain: CFG, data-flow, relocation, validation
:lua-obfuscation-api      commonMain: pass/module contracts and reports
:lua-obfuscation-strings  optional feature module
:lua-obfuscation-constants optional feature module
:lua-obfuscation-jumps    optional feature module
:lua-obfuscation-cff      optional feature module
:lua-obfuscation-register optional feature module
:lua-vm                   optional feature module: translator, private ISA, runtime
:lua-runtime-jvm          jvmMain adapters and differential runner
:lua-runtime-js           jsMain browser API and web UI
:lua-cli-native           nativeMain CLI only
```

依赖方向必须保持单向：`model → io/edit/analysis → obfuscation-api → feature modules → vm/frontends`。特性模块不能反向依赖 CLI 或平台实现。

## 后续实施顺序

1. 先把纯数据结构、opcode、常量和 prototype 移到 `:lua-bytecode-model` 的 `commonMain`。
2. 把 `java.io`, `java.nio`, `java.util.Random`, `MessageDigest` 等替换为 `expect/actual` 或平台接口。
3. 把 reader/writer 与 editor DSL 拆成独立模块，保留现有包名兼容 facade。
4. 把分析和每个混淆 pass 逐个迁移为独立 feature artifact；每个 pass 只依赖 API、编辑和分析模块。
5. 把 VM 作为可选模块；源 CFF、VM lift、生成 Lua 源码 staging 混淆分别出报告。
6. 最后添加 `jsMain` 网页端和 `nativeMain` CLI；JVM 仅保留库调用与测试适配。
7. 每一步都运行现有 225 tests、三版本差分矩阵和 VM 矩阵，不能用拒绝样本替代失败样本。

## 恢复时的验收条件

- `commonMain` 不出现 `java.*`、文件系统、进程或 JVM CLI 依赖。
- 每个可选混淆模块可以单独禁用、单独发布和单独测试。
- 网页端只调用 common API；文件读取、Worker 和下载由 `jsMain` 实现。
- Native CLI 是唯一默认 CLI；JVM 入口不再作为默认 application artifact。
- VM 报告必须区分 `source.cff`、`vm.lift`、`interpreter.source.cff` 和未完成的目标字节码 CFF。
- 任何“完整 VM”声明都必须同时给出三版本、特性、拒绝原因和真实解释器差分结果。
