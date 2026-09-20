# VM 混淆运行时支持

验收链路是官方 `luac` → `LuaVmTranslator` → 加密程序/常量池 → 生成的 Lua 解释器 → 对应版本官方 `lua`。JVM 参考解释器只用于 ISA 单元测试，不作为元方法、关闭或协程的验收依据。

## 本轮能力

| 能力 | 生成产物行为 |
| --- | --- |
| 5.3 / 5.4 / 5.5 泛型 for | 分版本处理 iterator/state/control/result 布局；5.5 在 TFORPREP 交换 control/close 槽；5.4/5.5 注册迭代器关闭值；false 控制值继续迭代，只有 nil 结束 |
| TBC / CLOSE | 每次 TBC 进入真实 Lua `<close>` 作用域，CLOSE 按寄存器边界退出；覆盖 return、break、错误、关闭中再次错误、关闭中 yield、coroutine.close；5.3 JMP 的关闭字段也生效 |
| 共享 upvalue | 开放寄存器共享 cell；CLOSE 后旧闭包保留旧 cell，同一槽重新进入作用域会捕获新 cell；子原型通过显式索引表寻址 |
| GETVARG / VARARG | 5.5 GETVARG 查询隐藏参数及 `n`；PF_VATAB 在固定参数后建立真实参数表；带 k 的 VARARG 从该表 raw 读取并校验 `n`，保留修改与捕获后的行为 |
| ERRNNIL | 对任意非 nil 值报错，包括 false；保留常量中的全局名、源 chunk ID 和行号，错误信息随常量池加密 |
| SETLIST / EXTRAARG | 支持开放计数与 nil 占位；5.3 使用 block×50，5.4 使用 C+Ax×256，5.5 使用 vC+Ax×1024；使用 rawset 保持原生数组构造语义 |
| 元方法桥接 | 原生 Lua 运算、索引、写表、比较、长度、连接、调用；保持 MMBIN/MMBINI/MMBINK 的事件和操作数顺序，保留比较立即数的整数/浮点类型；连接按右结合处理 |
| coroutine / yield | 使用宿主可恢复的 Lua 调用栈；虚拟 pc、寄存器、top、关闭边界均保留在对应调用帧；恢复后提交一次结果，不重放调用和元方法副作用 |
| 参数/结果序列 | pack/unpack 使用显式 n；固定参数补 nil，开放参数、返回和 vararg 不依赖表长度；动态源寄存器与翻译器临时寄存器分开存储 |

所有 dispatch 形状使用相同的返回、尾调用和关闭事件协议。解释器内部冻结所需的标准库函数，源程序修改 `_ENV.table` 等全局不会破坏 VM 辅助逻辑。

## 验证

`LuaVmSemanticBridgeTest` 包含 14 组真实差分测试，种子为 0、11、42、55。按特性运行官方 Lua 5.3.6、5.4.6、5.5.1；缺失工具链会明确标记跳过。覆盖多层 VM/宿主调用、可 yield 元方法、错误对象身份、关闭顺序、受保护元表、nil/false、超过静态栈大小的动态结果、5.3 人工扩展 SETLIST 与 5.4/5.5 原生 EXTRAARG。

```sh
JAVA_HOME=/path/to/jdk17 ./gradlew test installDist --console=plain
sh tools/verify-vm.sh
```

CLI 既有矩阵当前为 **31 identical / 23 refused / 0 failed**，最低通过数已提升到 31。拒绝项不记为语义支持，`--vm-out` 拒绝时退出码为 3。

## 仍有的边界

- 私有 ISA 的寄存器/常量操作数字段为 8 位，分支偏移为有符号 8 位。超出编码能力的原型会拒绝，不截断字段。
- 5.4/5.5 数值 for 的 count-based 实现仍沿用已有独立开关，本轮没有扩大其整数溢出等边界承诺。
- 这里的元方法支持指生成程序通过原生 Lua 运算执行元方法和传播其结果、错误对象与 yield；不表示实现了调试 API 的虚拟源码栈，也不保证所有宿主类型错误的文本/位置与原 chunk 完全一致。ERRNNIL 的专用错误信息单独保留。
- 分配次数、GC/弱表回收时机、调试栈和 debug 对真实 upvalue 的修改尚无透明性保证；本轮测试通过不等于整个 VM 设计文档已全部实现。
