# KMP 与多模块迁移记录

这份文档记录当前 VM 工作之后的架构目标，便于后续恢复开发。它不是新的运行时契约。

## 当前状态（终验后终态，2026-09-21）

验收核对与证据见 `docs/roadmap-kmp-progress.md` 终验章节；VM 能力事实基线见
`docs/vm-status-report.md`。

| 项目 | 状态 | 说明 |
|---|---|---|
| Lua 5.3/5.4/5.5 字节码模型 | 已完成（KMP common） | 读取、编辑、写回和版本字段位于 :lua-bytecode-model。|
| VM translator / 加密程序 / 生成解释器 | 部分完成 | 已有多版本能力和拒绝机制；仍有 ISA 字段宽度、调试/GC 观察等边界。|
| 输入 chunk → 现有混淆管线 → VM lift | 已接入，按原型报告覆盖 | 输入混淆/CFF 经 `LuaVmPipeline` 以 stage 函数注入 `LuaVmObfuscation.Options`（:lua-vm 不依赖任何 feature）；已修复混淆注入 upvalue 未初始化导致的 VM 崩溃。完整 AGGRESSIVE 仍以实际 translator 接受/拒绝报告为准。|
| 生成 Lua 源码内部混淆/CFF | 已完成（轻量无 parser） | `LuaVmSourceObfuscator` 对 VM 入口调用做随机私有 dispatcher；它是源码级 staging，不宣称目标字节码级 CFF。|
| 生成解释器目标字节码级 CFF | 未完成 | 后续需要目标版本 `luac`/loader/editor/validator 闭环；VM 报告以 `interpreter.bytecode.cff = not_implemented` 明示。|
| LuaVmIr | 已移除 | 暂不引入额外 IR；当前 translator 直接生成私有 VM ISA。|
| pass 插件化编排 | **已完成（2026-09-21）** | 内置 pass 以 `ObfuscationPassPlugin` 插件注册进 `ObfuscationRegistry`（:lua-obfuscation-pipeline）；执行顺序由 `runsAfter`/`runsBefore` 声明约束拓扑解析（稳定、与注册顺序无关，环/悬空引用显式异常）；宿主组合根显式装配 `builtIn()` 插件集，config null=禁用语义不变；详见 roadmap-kmp-progress.md 插件化章节。|
| 单体 Gradle 工程拆分 | **已完成** | 13 个 KMP 库模块（platform/model/io/edit/analysis/api/五个 feature/:lua-vm/:lua-obfuscation-pipeline）+ 根（JVM 库入口/集成测试）+ 宿主模块（:lua-runtime-js、:lua-cli-native，Compose Web 改造 Round 3 起另有 :lua-web-compose）。|
| commonMain KMP 核心 | **已完成** | 全部核心与 feature 代码位于 commonMain；jvm/js/linuxX64 三 target。|
| jvmMain | **已完成（JVM 适配面）** | platform.jvm 装配、chunk 文件 io 适配器与各模块 jvmTest。|
| jsMain 网页加密 | **已完成（2026-09-21，Phase 6 前半）** | 全部库模块带 js(IR) target；:lua-runtime-js 提供浏览器可用混淆 API（common API 编排子集）、静态 UI + Worker + 下载与 JS 接缝装配（见 roadmap-kmp-progress.md Phase 6 前半）。Compose Web 改造 Round 3（2026-09-21）起浏览器 UI 由 **:lua-web-compose**（Compose Multiplatform for Web，wasmJs 宿主）承担，:lua-runtime-js 保留混淆 API 与 UMD bundle 交付。|
| nativeMain / Native CLI | **已完成（2026-09-21，Phase 6 后半）** | 全部 13 个库模块带 linuxX64 target；编排器下沉 `:lua-obfuscation-pipeline`（jvm/js/linuxX64 共用）；`:lua-cli-native` 提供默认 CLI（posix 文件 io、/dev/urandom 熵、内嵌 decoder 资产、`--vm-out`），根模块已移除 `application` 插件——**Native CLI 是唯一默认 CLI**。|
| 可选特性插件发布 | **已完成（发布能力，终验补齐）** | 全部 13 个库模块带 `maven-publish`（platform/model/io/edit 于终验补齐）；:lua-runtime-js / :lua-cli-native 是宿主产物（可执行/网页资源），不发布。api/implementation 暴露面梳理是真实发布前的非阻塞待办。|
| 差分矩阵 CI | **已完成（终验接入，probe-then-run）** | verify.yml `differential-matrix` job：探测三版本 lua/luac/python3，齐备则跑 `tools/verify-all.sh`，缺失则逐项输出 skipped 理由并 exit 0（不阻塞门禁，也不算语义通过）。|
| VM 状态报告 | **已完成（终验新增）** | `docs/vm-status-report.md`：三版本矩阵、特性矩阵、拒绝原因清单、差分证据链、目标字节码 CFF 未实现的明确声明。|
| 终验（第 7 步） | **已完成（2026-09-21）** | 验收表 a–f 全过，证据见 `docs/roadmap-kmp-progress.md` 终验章节。|

## 目标模块图（实际落地名与终态标注）

```text
:lua-bytecode-model       commonMain: model, opcode, constants, prototype
                                      [落地: jvm/js/linuxX64, maven-publish; stdlib 之外零依赖]
:lua-platform             commonMain: platform.{Hex,IdentityCollections,Md5,ObfRandom,PlatformIo}
                                      [落地: jvm/js/linuxX64, maven-publish]
:lua-bytecode-io          commonMain: reader/writer; platform adapters in jvm/js/native
                                      [落地: jvm/js/linuxX64, maven-publish; js/native 文件 io 由宿主装配]
:lua-bytecode-edit        commonMain: editor DSL, instruction factory, expressions
                                      [落地: jvm/js/linuxX64, maven-publish]
:lua-bytecode-analysis    commonMain: CFG, data-flow, relocation, validation
                                      [落地: jvm/js/linuxX64, maven-publish]
:lua-obfuscation-api      commonMain: pass/module contracts, plugin contract
                                      (ObfuscationPassPlugin/Context), reports
                                      [落地: jvm/js/linuxX64, maven-publish]
:lua-obfuscation-strings  optional feature module   [落地: jvm/js/linuxX64, maven-publish, decoder 资产随模块]
:lua-obfuscation-constants optional feature module  [落地: 同上]
:lua-obfuscation-jumps    optional feature module   [落地: 同上]
:lua-obfuscation-cff      optional feature module   [落地: 同上]
:lua-obfuscation-register optional feature module   [落地: 同上]
:lua-vm                   optional feature module: translator, private ISA, runtime
                                      [落地: jvm/js/linuxX64, maven-publish; CapabilityReport 四分类契约]
:lua-obfuscation-pipeline commonMain: ObfuscationRegistry（插件 DI 注册表 + 顺序解析）
                                      + LuaObfuscator 插件化编排器 + 内置插件集
                                      （keys/debug-strip 插件 + DefaultObfuscationPasses）
                                      + LuaHostSeed + LuaVmPipeline 组合根
                                      [落地: jvm/js/linuxX64, maven-publish]
:lua-runtime-js           jsMain browser API and web UI
                                      [落地: js only 宿主（UMD bundle + 混淆 API；
                                      静态页/Worker 已由 :lua-web-compose 取代），不发布]
:lua-web-compose          wasmJs Compose-for-Web host (Compose UI over the
                          common pipeline, replaces the handwritten page)
                                      [落地: wasmJs browser 宿主，binaries.executable，不发布]
:lua-cli-native           nativeMain default CLI (posix io + platform seams)
                                      [落地: linuxX64("native") 宿主，binaries.executable，不发布]
```

依赖方向必须保持单向：`model → io/edit/analysis → obfuscation-api → feature modules → pipeline → vm-frontends`（:lua-vm 只依赖 platform/model/analysis/api，经 LuaVmPipeline 的 stage 函数与 pipeline 组合）。特性模块不能反向依赖 CLI 或平台实现。

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
