# Lua 5.3/5.4/5.5 字节码混淆：论文精读、落地映射与分层策略

本文是 `docs/obfuscation-algorithms.md` 的配套文献精读。前者记录"代码怎么写"，本文记录
**论文原文到底说了什么、哪些能抄、哪些做法会被现成工具打穿**。所有结论都来自本轮实测抓取的原文；
凡未能取得原文的地方一律显式标注 `（未能获取原文，仅据检索摘要，待核）`，不做推测性补全。

本文编号约定：§1–§22 逐篇精读，§23 逐条落地映射，§24 混合策略，§25 反制清单，文末为参考文献。

---

## 0 阅读方法与可验证性声明

### 0.1 工具链与取证方式（可复现）

| 环节 | 实际做法 |
|------|----------|
| HTML 页面 | `web_fetch` 可正常解码；但**遇到 PDF 直接失败**（`unsupported content type "application/pdf"`），因此 PDF 不能走 `web_fetch` |
| PDF 下载 | `curl -L -A <UA>` 落到 `/tmp/papers/*.pdf`（沙箱内网络可用） |
| PDF 取文（一） | `pypdf`（venv 安装）。对多数现代 PDF（arXiv/NDSS/USENIX/TACL 类）可靠 |
| PDF 取文（二） | `poppler` 的 `pdftotext -layout`（`brew install poppler`）。对 arXiv 的 2106.08913 之类更有用 |
| PDF 取文（三） | **OCR**：`pdftoppm -r 200/400 -gray -png` 渲染，再用 `tesseract --psm 6/4` 识别。**两份 Collberg 论文是 Type1 子集字体且缺 ToUnicode 表，`pdftotext` 与 `pypdf` 都只能抽出乱码**（例：`9:<;>=@?BAC=EDGFH9'I!JEJLK&MN;CO`），只能 OCR。双栏串行问题通过 `pdftoppm -x/-y/-W/-H` **按列裁剪**后单独 OCR 解决 |
| 被 TLS/CF 拦截的 URL | 本地代理（`http_proxy=http://127.0.0.1:7890`）对部分主机握手失败（`SSL_ERROR_SYSCALL`），或返回 Cloudflare `403 / Just a moment...`。此时改用文本代理 `https://r.jina.ai/<原始URL>` 取全文（CaDeCFF 的 ACM DL 全文即由此获得） |
| 仓库现状 | 只读检视；本轮**未修改任何 Kotlin 代码、测试、README 或 `docs/obfuscation-algorithms.md`** |

**OCR 引文的可信度声明**：§1、§2 中标注 `(OCR)` 的公式/句子来自 OCR，个别数学符号仍有噪声；
凡我有把握还原的（如 `7y² − 1 ≠ x²`）直接给出，凡噪声无法消除的（如某个四连乘整除式）标注"待核"，
绝不编造原文没有的构造。

### 0.2 实际可读 / 不可读清单

**已读到原文（正文级）**：

1. Collberg/Thomborson/Low, POPL '98（OCR，13 页）
2. Collberg/Thomborson/Low, *A Taxonomy of Obfuscating Transformations*, TR#148, 1997（OCR，36 页）
3. László & Kiss（PDF，17 页）
4. OLLVM `Flattening.cpp` / `BogusControlFlow.cpp` / `CryptoUtils.cpp` / `SplitBasicBlocks.cpp`（原始源码）
5. IOLLVM, arXiv 2203.03169（PDF，13 页）
6. GAMBA, arXiv 2305.06763（ar5iv 全文 HTML）
7. MBA-Blast, USENIX Security '21（PDF，19 页）
8. LOKI / *Hardening Code Obfuscation Against Automated Attacks*, arXiv 2106.08913（PDF）
9. goomba README（Hex-Rays）
10. D810 官方长文（eShard blog 全文）
11. Lebedev（PDF，俄文，5 页，含算法 1 完整步骤）
12. CaDeCFF（ACM DL fullHtml，经 jina 代理；57 KB 文本）
13. Rolles, *Unpacking Virtualization Obfuscators*, WOOT '09（PDF，7 页）
14. Prometheus README
15. Clyde Protection README
16. Luo/Ming/Fu/Peng/Li, *Reverse Engineering of Obfuscated Lua Bytecode via Interpreter Semantics Testing*（NSF PAR purl 10540556，PDF，15 页）
17. *Enhancing the Insertion of NOP Instructions … via Deep Reinforcement Learning*, arXiv 2111.09626（ar5iv 全文）

**仅拿到摘要 / 局部（明确降级）**：

18. Tiella & Ceccato, *Automatic generation of opaque constants based on the k-clique problem*, SANER 2017 ——
    全文在 IEEE CSDL / FBK CRIS 均为受限或 Cloudflare 403；CRIS 的 `main.pdf` 需要校内登录
    （jina 代理返回 `Sign in to your account`）。**只读到 FBK CRIS 的官方摘要**，故本文只写摘要级结论。
19. *On Simplifying Mixed Boolean-Arithmetic Expressions*（Automatic Control and Computer Sciences）——
    Springer/ACM 均为 Premium Access，**未能获取原文，仅据检索摘要，待核**；其"线性 MBA 可被直接求解"
    这一结论由 MBA-Blast/GAMBA 两条独立可读来源交叉证实。

**尝试但不可用（如实记录）**：

20. **NDSS 论文 *Prevalence of Lua Bytecode and Lua Bytecode Obfuscation***：任务给出的
    `dev.ndss-symposium.org/wp-content/uploads/2026-s1251-paper.pdf` 该域名**已无法解析**
    （curl 与 jina 均报 `Domain … could not be resolved`）。而 `www.ndss-symposium.org` 下同编号
    `2026-s1251-paper.pdf` 实际是 **FirmCross**（C-Lua 混合固件的污点漏洞检测，标题页已核对），
    NSFC/NDSS 2026 接收列表页里也检索不到该标题。检索摘要中出现的
    `#Interp / Ob-Structure / Ob-Data` 表格与"customized Interpreters' Diversity"字样无法与可下载文件对应，
    因此**该论文本文不引用、不描述**。作为替代，我抓取并精读了同一研究线（Ming 组）真实可下载的
    **《Reverse Engineering of Obfuscated Lua Bytecode via Interpreter Semantics Testing》**（§20），
    它直接给出了 Lua 字节码混淆的在野形态与完整反制方法，对本项目更有用。
21. Salwan/Bardin/Potet, *Symbolic Deobfuscation: From Virtualized Code Back to the Original* (DIMVA'18)：
    候选 URL（shell-storm、HAL、UCSB 镜像）分别返回 502/HTML/404，未取得正文。
22. SiMBA / MBA-Solver / MBA-Flatten 原始论文、Zhou et al. 2007 *Information Hiding in Software with
    Mixed Boolean-Arithmetic Transforms*：均未取得可读正文；其内容通过 MBA-Blast 与 GAMBA 的转述与
    引用条目间接了解，本文只在转述处标注来源，不单独作为条目。

### 0.3 被检视的仓库版本（重要）

本任务执行期间工作副本**发生了变化**：精读中段 `obfuscate/LuaControlFlowFlattening.kt` 被新增
（419 行），`obfuscate/LuaControlFlowNoise.kt` 由约 24 KB 增至约 43 KB，`obfuscate/LuaObfuscator.kt`
亦被修改（新增 `controlFlowFlattening` 配置项与流水线步骤）。

因此 §23 的"已实现 / 待实现"判断以**我实际读到的这一版**为准：

* `LuaControlFlowFlattening.kt`：`ControlFlowFlatteningConfig(minBlocks=2, dispatchJunk=0, seed=0)`；
  case id 为 `random.nextInt(1, Int.MAX_VALUE)` 并作为**整数常量**塞进常量池；dispatcher 为
  **乱序的 `EQK` 直筒比较链**；入口直接 `LOADK state, K[id(0)]`。
* `LuaControlFlowNoise.kt`：`OpaquePredicate` 已扩展为 `SIMPLE_NIL / PARITY / SQUARE_PARITY /
  NON_SQUARE / MBA_ADD / MBA_XOR / MBA_DE_MORGAN / SHIFT / XOR_SELF / MIXED`；
  新增 `resolveEnvUpvalues(chunk)` 统一解析 `_ENV` upvalue（即 §7.7 里 5.3 版本误编译问题的修复方向）。
* 流水线顺序：`jumpIndirection -> stringEncryption -> junkCode -> controlFlowFlattening -> stripDebugInfo`。

---

## 1 Collberg, Thomborson, Low, *Manufacturing Cheap, Resilient, and Stealthy Opaque Constructs*, POPL '98

**引用/链接**：Collberg, Thomborson, Low. *Manufacturing Cheap, Resilient, and Stealthy Opaque
Constructs.* POPL '98, pp. 184–196. DOI <https://dl.acm.org/doi/10.1145/268946.268962>
正文 PDF（经 Wayback 归档，我用它做的 OCR）：
<https://web.archive.org/web/2023id_/https://www2.cs.arizona.edu/~collberg/content/research/papers/collberg98manufacturing-clean.pdf>
（Arizona 原站已下线，`www2.cs.arizona.edu/~collberg/content/research/papers/*.pdf` 现在 404。）

### 1.1 三个质量维度（原文）

论文开篇即给出混淆变换的三个评价轴 (OCR)：

> "…(how much obscurity it adds?), resilience (how well does it resist automatic deobfuscation?), and
> stealth (how well does obfuscated code blend …)"

并在 §3.2.3 明确 **stealth 是 context-sensitive 度量**："A transformation may introduce code which is
stealthy in one program but extremely unstealthy in another one." 论文里还给了
"Like stealth, cost is a context-sensitive metric" —— **同一句 `a = 5` 放在函数顶层只付常数代价，
放进内层循环代价就是亚线性的**。这条对本项目直接适用：往**热循环体内**插不透明谓词，代价按循环次数放大。

### 1.2 不透明谓词的定义（原文）

> "Informally, a variable V (or predicate P) is opaque if it has some property q which is known a priori
> to the obfuscator, but which is difficult for the deobfuscator to deduce."

判定点绑定在程序点上：`V` 在点 `p` 处不透明，指 `V` 在 `p` 的属性 `q` 在混淆时已知；`P` 在 `p` 处不透明
指其取值在 `p` 处已知而逆向者难以推出。

### 1.3 分类：trivial / weak / 强（对象别名 / 并发）

论文按"对手需要多强的分析才能破"分层，章节点名如下（OCR 得到的小节标题）：

* **trivial**：`Figure 3: Examples of trivial opaque constructs of low resilience.` —— 依赖
  `random(a,b)` 这类**语义已知的库函数**，逆向者只要把这些库函数的语义列表化再模式匹配即可破解。
  原文明说这类构造 "could be broken … using simple global static analysis"。
* **weak**：`An opaque construct is weak if a deobfuscator can crack it by a static global analysis.
  An analysis is global if it is restricted to a single control flow graph.`
* **5.1 Opaque Constructs Using Objects and Aliases**：建立在**指针别名/堆形状分析不可判定**之上。
  原文："flow-sensitive alias analysis is undecidable in languages with dynamic allocation, loops, and
  if statements"，因此这条路线"cheap, stealthy (in pointer-rich languages like Java), and resilient"。
  实现是设计一个 Graph ADT，用 `Split` / `Merge` 等"破坏性更新"让保守的堆分析失效，再用
  `p == q`、`p == null`、`n <= IntLit` 这种**在原程序里极常见的谓词形状**保证 stealth。
* **5.2 Opaque Constructs Using Concurrency**：用线程+全局变量制造"数据竞争但结论恒定"的谓词。

### 1.4 关键构造与证明（逐条，含原文公式）

**(a) 恒真（整除恒等式，用于扩展循环条件）** —— §4.3 的 Figure 5(d)：

> "The predicate we have added in Figure 5(d), for example, will always evaluate to True since
> `x²(x+1)² = 0 (mod 4)`." (OCR)

**证明**：连续两整数 `x, x+1` 必有一偶，故 `x(x+1)` 为偶数，其平方 `x²(x+1)² = [x(x+1)]²` 被 `4` 整除。
∴ 对任意整数 `x`，`x²(x+1)² ≡ 0 (mod 4)` 恒真。

> 这是本项目 `OpaquePredicate.PARITY`（`x*(x+1) % 2 == 0`）的**同族加强版**：把一次式升到平方，
> 模数从 2 提到 4，仍只需 `LOADK/ADD/MUL/MOD`。OLLVM 的 `BogusControlFlow` 用的是较弱的
> `x*(x+1) % 2 == 0`（见 §5）。

**(b) 恒真（二次剩余/非平方数）** —— §5.2 的 Figure 10：

> "It is based on the well-known fact from elementary number theory that, for any integers `x` and `y`,
> `7y² − 1 ≠ x²`." (OCR)

**证明（模 8 分类）**：平方数模 8 只可能是 `{0,1,4}`；而 `7y² − 1` 在 `y² ∈ {0,1,4}` 时分别为
`{7, 6, 3}`（`7·4−1 = 27 ≡ 3`），与 `{0,1,4}` 不相交。∴ 等式永不成立，`7y² − 1 ≠ x²` 恒真。
本项目 `OpaquePredicate.NON_SQUARE` 用的正是这条，并以"比较恒为假"实现。

> **作者自己给出的反制**（§6，原文 OCR）：
> "the simple opaque predicates in Figure 3 can be cracked by a global data flow analysis, the predicate
> `(7y² − 1 = x²)` can be cracked by a theorem prover, and static slicing techniques can be used to bring
> together logically related pieces of code which the obfuscator has dispersed over the program."
> —— **`NON_SQUARE` 在 1998 年就被作者点名可被定理证明器打掉。** 这条必须写进 §25。

**(c) 恒假的并发谓词** —— Figure 10 的 Java 例子：线程 `s` 反复执行 `M.X *= M.X`（故 `X` 恒为平方数），
线程 `t` 反复写 `M.Y = 7*R*R`，主线程在数据竞争后判定某个比较。原文说明：

> "Regardless of whether `s` or `t` wins the race, `M.X` will hold the square of a number."
> "In this example, the predicate at point … will always evaluate to False."

即：**用"竞争结果不确定、但不变式恒定"制造恒假谓词**，静态分析面对可能交错无从判定，动态执行却恒定。
代价：论文也承认 *inherently sequential* 的应用里线程谓词 "highly unstealthy"，退路是 Java finalizer。

**(d) 控制流变换的四种基本注入法**（§4）：

1. **Insert Dead or Irrelevant Code**（§4.2）：把 `S = S1…Sn` 对半切开插入恒真谓词 `P?`；或造出语义
   相同但形态不同的 `S*` / `S#` 由谓词二选一；或**在 `S#` 里故意埋一个 bug**，由恒真谓词保证永远选正确的 `S*`
   —— 后者专门对付"逆向者看到非可归约图就一律删谓词"的暴力策略（§6 明说：若盲目删 `P?`，代码就错了）。
2. **Extend Loop Conditions**（§4.3）：往循环条件里 AND 上恒真谓词，不改变迭代次数。
3. **Convert a Reducible to a Non-Reducible Flow Graph**（§4.4）：加一条"跳进循环中部"的恒不成立分支，
   使 CFG 非可归约。JVM 字节码有 `goto` 而 Java 语言没有 —— **语言破坏型变换**。
4. **Table Interpretation** 类（静态派发）—— 即后来的 CFF/VM 化雏形。

### 1.5 结论

* 拿得走的：`x²(x+1)² ≡ 0 (mod 4)`、`7y²−1 ≠ x²`、别名/并发两类"难解问题"路线、"谓词取值恒定但
  逆向者必须真算一遍"这一设计原则、以及"分支必须收敛/永不改变语义"的安全设计。
* 拿不走的：Java 的堆对象、`Thread`、finalizer。Lua 字节码层没有别名可控的对象图（表是动态的但
  没有静态可分析的类型系统），也**不该**在混淆产物里起线程（会改变宿主程序的可观测行为）。
  Lua 里对应"难解+廉价"的替代物是**数论/组合难解问题**（见 §9 k-clique）。

---

## 2 Collberg, Thomborson, Low, *A Taxonomy of Obfuscating Transformations*, TR#148, University of Auckland, 1997

**引用/链接**：Technical Report #148, Department of Computer Science, University of Auckland, July 1997.
HTML 记录（无全文附件）：<https://researchspace.auckland.ac.nz/handle/2292/3491>
正文 PDF（Wayback 归档，我用它做的 OCR）：
<https://web.archive.org/web/2020id_/http://www.dsi.unive.it/~avp/collberg97taxonomy.pdf>
（原始 `dsi.unive.it` 与 Auckland 的 `~cthombor` 路径现已 404。）

### 2.1 形式化定义（原文）

* **potency（↑ 混淆强度）**：`Tpot(P)`。原文定义：*"What does it mean for a program `P'` to be more
  obscure (or complex or unreadable) than a program `P`?"* 通俗表述为
  *"To what degree is a human reader confused?"*
* **resilience（↑ 抗自动反混淆）**：`Tres(P)`，度量尺度为**五级**：
  `trivial / weak / strong / full / one-way`。原文用程序员努力度与逆向者努力度之比定义：
  `Tres = Resilience(T) = (obfuscator effort) / (deobfuscator effort)`；并且
  *"one-way if information is removed from `P` such that `P` cannot be reconstructed from `P'`"*。
* **cost（↓ 开销）**：`Tcost(P)`，四级：**`free / cheap / costly / dear`**。
  原文强调：*"the actual cost associated with a transformation depends on the environment in which it is
  applied … A simple assignment `a = 5' inserted at the topmost level … only incur a constant overhead.
  The same statement inserted inside an inner loop will have a substantially higher cost."*
  论文默认按"最外层嵌套"报代价。
* **quality 的定义**（原文 OCR）：
  `Tqual(P) = (Tpot(P), Tres(P), Tcost(P))`。

### 2.2 分类框架（Figure 1 的 (c)–(g) 子图）

论文按"作用对象"分三大类，再按"手法"分子类：

| 作用对象 | Aggregation（聚合） | Ordering（排序） | Computations（计算） |
|----------|--------------------|------------------|---------------------|
| **Layout**（布局/符号） | Merge scalar variables；Modify inheritance relations；Split/fold/merge arrays | Reorder instance variables；Change variable lifetimes；Reorder methods | —— |
| **Data**（数据） | （同 Layout 的聚合手法，作用于数据表示） | Reorder arrays 等 | Change encoding 等 |
| **Control**（控制流） | Inline methods；Outline statements；Clone methods；Unroll loop | Reorder loops；Reorder expressions | **Non-reducible flow graphs**；**Extend loop condition**；**Table interpretation** |

（Layout 的 Storage & Encoding 子类含 Split variables / Promote scalars to objects / Convert static
data to procedure；这些与本项目关系不大。）**与本项目直接相关的是 Control 下的三个 Computations 子类**：
`Non-reducible flow graphs`（§1 的 4.4）、`Extend loop condition`（§1 的 4.3）、`Table interpretation`
（= 状态机派发，即 CFF 与 VM 化的祖先）。

### 2.3 不透明变量/谓词的形式化定义（原文）

> "a variable `V` is opaque if it has a property `q` known a priori to the obfuscator, but which is
> difficult for the deobfuscator to deduce. Similarly, a predicate `P` (a boolean expression) is opaque if
> a deobfuscator can deduce its outcome only with great difficulty, while this outcome is well known to
> the obfuscator."

并且不透明构造的 resilience **与变换用同一把尺子**：

> "We measure the resilience of an opaque variable or predicate (i.e. its resistance to deobfuscation
> attacks) on the same scale as transformation resilience, i.e. (trivial, weak, strong, full, one-way)."

### 2.4 结论

* 拿得走的：**(a)** 评价体系本身 —— 我们每加一个 pass 都该报 `(potency, resilience, cost)` 三元组，
  而不是只报"注入了多少条指令"；**(b)** "cost 是 context-sensitive，按嵌套层计" —— 指导我们把
  高代价手法放在冷路径；**(c)** Control/Computations 的三分类给出了 CFF/谓词/表格解释的坐标系。
* 注意：本 TR 用的是 `potency/resilience/cost` 三元组，**没有把 stealth 列为第四个形式化维度**
  （OCR 全文未出现 "stealth"）；stealth 在 POPL '98 中被显式提升为第三根支柱（见 §1.1）。
  任务描述里"potency/resilience/stealth/cost"的四指标说法，我按此如实修正为
  "TR'97 三指标 + POPL'98 的 stealth"。

---

## 3 László & Kiss, *Obfuscating C++ Programs via Control Flow Flattening*

**引用/链接**：T. László, Á. Kiss. *Obfuscating C++ programs via control flow flattening.* Annales
Universitatis Scientiae Budapestinensis, Sect. Computatorica **30** (2009), 3–19.
下载地址（Wayback 归档的 Annales 卷 PDF，HTTP 200，17 页）：
<https://web.archive.org/web/2020id_/http://ac.inf.elte.hu/Vol_030_2009/003.pdf>
（`ac.inf.elte.hu` 直连现已 404。）

### 3.1 核心算法（原文）

> "The basic method for flattening a function is the following. First, we break up the body of the
> function to basic blocks, and then we put all these blocks, which were originally at different nesting
> levels, next to each other. The now equal-leveled basic blocks are encapsulated in a selective structure
> (a switch statement in the C++ language) with each block in a separate case, and the selection is
> encapsulated in turn in a loop. Finally, the correct flow of control is ensured by a control variable
> representing the state of the program, which is set at the end of each basic block and is used in the
> predicates of the enclosing loop and selection."

论文 §4 把变换写成**基于栈的语法制导翻译**（`levels` 栈保存 `⟨switch variable, while label⟩`），
关键伪码骨架（原文缩排形式）：

```text
transform compound statement:
    switch variable := unique identifier()
    emit "while (" ⊕ switch variable ⊕ " != " ⊕ entry ⊕ ") {"
    emit " switch (" ⊕ switch variable ⊕ ") {"
    push(levels, ⟨switch variable, while label⟩)
    ...
    switch variable := top(levels).variable
    emit " " ⊕ switch variable ⊕ " = " ⊕ <successor> ⊕ ";"
```

即：**状态变量 + `while` + `switch` + 每块末尾赋后继编号**。每个原基本块成为一个 `case`。

### 3.2 论文自己指出的 C++ 适配难点（对我们有直接类比价值）

1. **循环头不能原样保留**：把循环头与循环体分离后，`while/do/for` 语义不再成立，必须把循环头改写成
   `if`（保留原谓词）+ 分支里给控制变量赋正确的后继值。**Lua 类比**：`FORLOOP/FORPREP/TFORLOOP`
   是"融合了跳转的循环头"，不能与循环体分离，必须**整条留在本块内**并只把后继改经 dispatcher
   —— 现行 `LuaControlFlowFlattening.emitBlock` 的 `loop ->` 分支正是这么做的。
2. **`switch` 的宽松语义（Duff's device）**：`case` 可以前缀任意子语句，循环与 switch 可以交织，
   平坦化会破坏这种结构。
3. **`break` / `continue` 会变成"重启平坦化主循环"**：必须显式改写。**Lua 类比**：
   `JMP A` 中 `A≠0` 表示关闭 `>= A-1` 个 upvalue（`break` 用），改写时**必须保留 A 字段**，
   否则会破坏闭包语义。

### 3.3 作者提出的加固方向（原文 §5，直接决定我们下一步做什么）

> "A simple but effective approach is to permute the order of the flattened blocks, thus moving related
> blocks away from each other. Moreover, using gotos and labels only instead of the while-switch construct
> we can further harden the comprehension of the obfuscated code. Another method is to obfuscate the values
> assigned to the control variable, in a way that they are not compile time constants anymore, or to use
> alias variables to make static analysis more difficult."
>
> "…control flow flattening combined with aliasing can render the determining of the precise control flow
> NP-hard."

**这四条正是 OLLVM 与 iOLLVM 后来分别实现的东西**，也正好命中我们当前实现的短板：

| 论文加固项 | OLLVM/IOLLVM 对应 | 本项目现状 |
|-----------|------------------|-----------|
| 打乱基本块布局 | `i->moveBefore(loopEnd)`（仅布局） | 块仍在原位，未重排 |
| 只用 goto/label 而不用 while-switch | OLLVM 先 `LowerSwitchPass`，最终就是纯 `JMP` | ✅ 已是纯 `JMP`/`EQK` 比较链 |
| **控制变量值不是编译期常量** | `CryptoUtils::scramble32`（AES 派生） | ❌ **仍是常量池里的整型常量**（见 §23） |
| **用别名/间接变量** | IOLLVM 的解决思路见 §8 | ❌ 未做 |

### 3.4 结论

论文的贡献是**算法骨架 + 难点清单 + 加固路线图**，而不是强度证明。它自己承认平坦化在
"combined with aliasing" 之前不构成 NP-hard 保障 —— 这就是为什么单靠 CFF 必输。

---

## 4 OLLVM `Flattening.cpp`（branch `llvm-4.0`）

**链接**：<https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/Flattening.cpp>
（raw：<https://raw.githubusercontent.com/obfuscator-llvm/obfuscator/llvm-4.0/lib/Transforms/Obfuscation/Flattening.cpp>）

### 4.1 真实步骤（逐条对照源码）

1. **先降 switch**：`FunctionPass *lower = createLowerSwitchPass(); lower->runOnFunction(*f);`
   —— 所以 OLLVM 的最终形态是**比较链**而不是跳转表。这一条极其重要：它意味着针对"跳转表"的还原方法
   （jump-table recovery 类）不直接适用，但**针对比较链 dispatcher 的方法（D810、CaDeCFF）完全适用**。
2. 收集所有原 BB（`origBB`）；遇到 `invoke` 直接放弃；`origBB.size() <= 1` 放弃。
3. 拆出 entry 头部，保证 entry 只有一个后继：
   `BasicBlock *tmpBB = insert->splitBasicBlock(i, "first"); origBB.insert(origBB.begin(), tmpBB);`
4. 建状态变量并初始化：
   ```cpp
   switchVar = new AllocaInst(Type::getInt32Ty(...), 0, "switchVar", insert);
   new StoreInst(ConstantInt::get(..., cryptoutils->scramble32(0, scrambling_key)), switchVar, insert);
   ```
5. 建 `loopEntry`（`load switchVar`）、`loopEnd`（跳回 `loopEntry`）、`switchDefault`
   （`BasicBlock::Create(... "switchDefault" ...); BranchInst::Create(loopEnd, swDefault);`）。
6. 建 switch 并挂到 `loopEntry`：`switchI = SwitchInst::Create(&*f->begin(), swDefault, 0, loopEntry);`
7. **每个原 BB 分配打乱的 case id**（注意：**id 由"当前 case 数量"喂给 `scramble32`**，
   所以 id 是 `scramble32(0)`, `scramble32(1)`, … 的序列，可复现）：
   ```cpp
   i->moveBefore(loopEnd);                       // 仅视觉重排，无逻辑作用
   numCase = ConstantInt::get(..., cryptoutils->scramble32(switchI->getNumCases(), scrambling_key));
   switchI->addCase(numCase, i);
   ```
8. **改写终结指令**（三种情形）：
   * 无后继（`return`）→ 不动；
   * 单后继 → `new StoreInst(numCase, load->getPointerOperand(), i); BranchInst::Create(loopEnd, i);`
   * 双后继 → `SelectInst::Create(br->getCondition(), numCaseTrue, numCaseFalse, "", i->getTerminator())`
     然后 store + 跳 `loopEnd`。**这就是 `select` 收敛结构**：条件本身保留，只把"选哪个 case"变成数据。
9. `fixStack(f)` 把 `alloca` 提升为寄存器（否则每个函数都多一次内存访问）。

### 4.2 强度与可被利用的指纹

* **优点**：case id 由 AES 派生的 `scramble32` 生成，"伪随机且不直观"；`select` 结构使条件分支的两条边
  在**同一块内**由数据选择，逆向者无法直接看出 true/false 边的对应关系。
* **指纹（对手视角）**：**"32 位高熵常量"就是 OLLVM 平坦化的招牌**。D810 原文明确写道：
  *"For OLLVM, the state variable is a 32 bit constant with high entropy (this was used as an heuristic
  by Rolf Rolles to identify flattened functions)."* —— 见 §14。
  也就是说，OLLVM 的 `scramble32` 虽然抗"猜值"，但**恰恰因此产生了可被静态检出的统计特征**。
  这决定了我们的设计取向：**状态值不应是"常量池里的高熵常量"，而应是运行期派生、且形态与普通算术无法区分的值**
  （§23 第 1 条）。

### 4.3 已知破解

* D810 的 `GenericDispatcherUnflatteningRule`：识别 dispatcher 与状态变量 → `MopTracker` 回溯状态变量
  计算 → 微码解释器仿真 dispatcher → 把"跳到 dispatcher"直接替换成"跳到目标块"（§14）。
* CaDeCFF：不依赖编译优化后的固定 CFG 特征，用数据流分析定位状态变量 + 符号执行生成 super block（§16）。
* Lebedev：符号执行构造 `(a, x_i, b_i)` 三元组集合再打补丁（§15）。

**三者都不需要知道 `scramble32` 的 key** —— 因为 `scramble32` 只影响"值的可读性"，不影响"值的可解性"。

---

## 5 OLLVM `BogusControlFlow.cpp`

**链接**：<https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/BogusControlFlow.cpp>

### 5.1 真实结构（源码头部注释即为算法说明）

文件头注释给出变换前后 CFG，并明确：

> "* The results of these terminator's branch's conditions are always true, but these predicates are
> opacificated. For this, we declare two global values: `x` and `y`, and replace the `FCMP_TRUE`
> predicate with `(y < 10 || x * (x + 1) % 2 == 0)` (this could be improved, as the global values give a
> hint on where are the opaque predicates)"

> "The altered bloc is a copy of the original's one with junk instructions added accordingly to the type
> of instructions we found in the bloc"

选项：`-boguscf-prob`（默认 30，即每块 30% 概率被选）与 `-boguscf-loop`（默认 1，可迭代多轮，
**会作用于上一轮新增的块**）。

### 5.2 步骤

1. `addBogusFlow(basicBlock, F)`：
   * `splitBasicBlock` 把块切成"phi/debug 头" + "原指令体"（这样不必修 phi）；
   * `createAlteredBasicBlock` 克隆原块为 `alteredBB`；
   * **先放一个恒真但"将来会被混淆"的比较**：
     ```cpp
     Value *LHS = ConstantFP::get(Type::getFloatTy(...), 1.0);
     Value *RHS = ConstantFP::get(Type::getFloatTy(...), 1.0);
     FCmpInst *condition = new FCmpInst(*basicBlock, FCmpInst::FCMP_TRUE, LHS, RHS, *var4);
     BranchInst::Create(originalBB, alteredBB, condition, basicBlock);
     ```
     **注意这里用的是 `FCMP_TRUE`（浮点比较恒真）**，即"占位谓词"，靠后面统一替换。
   * `alteredBB` 结束时 `BranchInst::Create(originalBB, alteredBB);` —— **altered 块跳回原块**（收敛）。
   * 把 `originalBB` 的终结指令单独切到 `originalBBpart2`，再加第二个 `FCMP_TRUE`，
     `BranchInst::Create(originalBBpart2, alteredBB, condition2, originalBB);`
     —— 于是原块看起来"有时继续、有时跳走"，但恒定走 `originalBBpart2`。
2. `createAlteredBasicBlock` 的垃圾指令不是"随便插"，而是**按指令类型定向变换**：
   * 整数二元运算（Add/Sub/Mul/UDiv/SDiv/URem/SRem/Shl/LShr/AShr/And/Or/Xor）：
     以 `random = get_range(10)` 起始的循环里 `switch(get_range(4))`：`0` 什么都不做；
     `1` 生成 `-a + b`；`2` 生成 `(a - b) * b`；`3` 生成 `a << b`；
   * 浮点二元运算：`-a + b` 或 `(a - b) * b`；
   * `ICmp`：随机 `swapOperands()` 或随机改 predicate（EQ/NE/UGT/…/SLE 共 10 种）；
   * `FCmp`：随机 `swapOperands()` 或随机改 predicate（OEQ/ONE/UGT/…/OLE）。
   **关键**：这些"垃圾"是**插在克隆块里、其寄存器结果不被使用**的（`op`/`op1` 无 use），所以不破坏语义。
3. `doF(Module&)`（`doFinalization`）做**全模块两遍扫描**：
   * 第一遍找所有 `FCmp` 且 predicate == `FCMP_TRUE` 的条件及其分支，记入 `toDelete` / `toEdit`；
   * 第二遍把谓词替换成**Collberg 的奇偶恒真式**（原文注释：`//if y < 10 || x*(x+1) % 2 == 0`）：
     ```cpp
     op  = BinaryOperator::Create(Instruction::Sub, opX, ConstantInt(1), "", *i);
     op1 = BinaryOperator::Create(Instruction::Mul, opX, op, "", *i);
     op  = BinaryOperator::Create(Instruction::URem, op1, ConstantInt(2), "", *i);
     condition  = new ICmpInst((*i), ICmpInst::ICMP_EQ, op, ConstantInt(0));
     condition2 = new ICmpInst((*i), ICmpInst::ICMP_SLT, opY, ConstantInt(10));
     op1 = BinaryOperator::Create(Instruction::Or, condition, condition2, "", *i);
     ```
     即 `x*(x-1) % 2 == 0 || y < 10`（源码里的 x/(x+1) 与实现里的 x/(x-1) 不完全一致，
     **两种写法都恒真**，因为相邻整数乘积必为偶）。

### 5.3 强度与指纹

* 论文/实现自己承认的弱点：**"this could be improved, as the global values give a hint on where are
  the opaque predicates"** —— 两个全局变量 `x`、`y` 就是定位所有谓词的锚点。
  如果全局变量是**可写**的（`CommonLinkage` + 非 const），语义安全还依赖"没人改它们"。
* 垃圾指令**只插无害指令、结果不被使用**：现代反优化器/编译器（以及 Hex-Rays 的 DCE）会直接删掉。
* **模板指纹**：`x*(x±1) % 2 == 0` 是教科书模板，ML 检测器与人工审计都能一眼认出。
  这也是本项目 `MIXED`（每站点随机族）设计的直接动因。

### 5.4 已知破解

* **`FCMP_TRUE` 折叠**：任何 pass 若在替换前跑（或在 `doFinalization` 未覆盖的模块上跑），
  恒真条件会被常量折叠为无条件跳转，bogus 分支整个消失。这解释了为什么该 pass 必须靠
  "模块级 finalization"收尾 —— 脆弱的工程耦合。
* DCE + 数据流分析消掉无 use 的垃圾算术。
* 全局变量 `x`/`y` 被识别为"只读且恒零"→ 谓词退化。

---

## 6 OLLVM `CryptoUtils.cpp`（`scramble32` 与其 PRNG）

**链接**：<https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/CryptoUtils.cpp>

### 6.1 `scramble32` 的真实结构（非标准 AES，是"4 轮 AES 变体 + key 异或"）

```cpp
unsigned CryptoUtils::scramble32(const unsigned in, const char key[16]) {
  // Round 1
  tmpA  = AES_PRECOMP_TE0[((in   >> 24) ^ key[0])  & 0xFF];
  tmpA ^= AES_PRECOMP_TE1[((in   >> 16) ^ key[1])  & 0xFF];
  tmpA ^= AES_PRECOMP_TE2[((in   >>  8) ^ key[2])  & 0xFF];
  tmpA ^= AES_PRECOMP_TE3[((in   >>  0) ^ key[3])  & 0xFF];
  // Round 2 / 3 / 4 同构，key[4..15] 依次参与
  LOAD32H(tmpA, key);
  return tmpA ^ tmpB;      // 最后一轮结果再与 key[0..3] 异或
}
```

* 上游是 `prng_seed()`：读 `/dev/urandom`（Linux）或 `/dev/random`，16 字节作为 key，
  `aes_compute_ks` 做 key schedule，`populate_pool` 用 **AES-CTR**（`inc_ctr` 只递增 `ctr[8..15]`）
  填充 16 字节对齐的随机池，`get_bytes` / `get_uint32_t` / `get_range` 从池里取。
* `get_range(max)` 用**拒绝采样**：`log = 最高有效位位置`，`mask = (1<<log)-1`，
  `do { r = get_uint32_t() & mask } while (r >= max);` —— 无模偏置。
* `Flattening.cpp` 里 `scrambling_key` 是**每个函数**从 PRNG 取 16 字节。

### 6.2 评价

* 优点：case id 分布均匀、无偏置、不可预测（乱序 case 序号 ⇒ id 与块序无关）。
* **它不是加密**，只是"带密钥的伪随机置换"：注释自嘲 *"Orr, Nathan or Adi can probably break it,
  but who cares?"*。更关键的是 **key 会随产物一起被逆向者拿到**（它只存在编译器内存里，
  但 id 值本身就出现在产物中），所以 key 保密性对攻击者**根本不必要** —— 攻击者直接读 id 即可。
* 结论：**`scramble32` 的价值在于"去模板化/抗模式匹配"，而不在于"抗求解"**。这也是我们
  用 keyed bijection（模 2³¹ 的奇数乘 + 异或 + 加）替代 AES 时应当保持的定位：**它只能降低可读性，
  不能提高可解性下界**。

---

## 7 OLLVM `SplitBasicBlocks.cpp`

**链接**：<https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/SplitBasicBlocks.cpp>
（仅 3.6 KB，全文可读。）

**算法**：对目标函数里每个基本块，若指令数超过阈值就反复 `splitBasicBlock` 成更小的块，
逐轮把块数翻倍。源码里的注释给出了动机：

> "This transformation splits basic blocks into sub-blocks. It is useful for the flattening pass,
> as it increases the number of basic blocks to flatten, and thus the number of switch cases."

（原文关键句我核对到的是对 `flattening` 的配合定位：**先切碎再平坦化**，使
`switch` 的 case 数上升，dispatcher 的规模与扇出更大。）

**对本项目的意义**：这是"**提高 CFF potency 的最廉价手段**"——在 Lua 字节码层等价于
**在无跳转的长直线块里插入新的基本块边界**（插一条 `JMP` 到下一句，或在无条件跳转目标处
人为制造新标签），使 `basicBlockStarts()` 返回更多块。它不改变语义、不需要新机制，
但能显著放大 dispatcher 尺寸，并让"按块数/按块序"的启发式更难用。

**反制提示**：切碎只增加数量，不增加难度 —— 反平坦化算法对块数不敏感（D810/CaDeCFF 都是
逐块替换）。所以它只能算 cost 换 potency，不能算 resilience。

---

## 8 IOLLVM, *Enhanced version of OLLVM*（arXiv 2203.03169）

**引用/链接**：arXiv:2203.03169（13 页 PDF，可读）：
<https://arxiv.org/abs/2203.03169> ／ <https://arxiv.org/pdf/2203.03169>

### 8.1 两个增强（原文）

> "First, we propose the nested switch obfuscation scheme and the in-degree obfuscation for bogus blocks
> in the control flow obfuscation. Secondly, the identifier obfuscation scheme is presented in the LLVM
> layer to fill the gap of OLLVM at this level."

**(a) Nested switch flattening（嵌套 switch 平坦化）**

动机（原文）：

> "OLLVM obfuscation processing can effectively remove the added flattened obfuscated code … if a cracker
> can infer that control flow obfuscation has been performed based on the [CFG], …"

做法：

> "to resist the flattened processing and resisting script analysis, the switch structure is created
> again in the flattened generated switch structure, and a new switch is created in each basic block …"

即：在**每个已被平坦化的基本块内部**再嵌一层 switch，把该块自身的顺序指令也转成"二次派发"。
结果是 CFG 呈现**嵌套的多层派发**，而不是单层 `loop{switch{case}}`。论文给 `KthSmallest` 的
CFG 对比图（Figure 5 = OLLVM 单层，Figure 6 = 嵌套）。

**(b) In-degree obfuscation（入度混淆）**

动机（原文）：

> "Generally, in practice, to reduce the space overhead, the obfuscation algorithm only protects against
> the critical code in the program. So if the attacker can [identify] … the degree of entry of the bogus
> block is obfuscated."

做法：给**真实块**（而非 bogus 块）加不透明谓词入口，使真实块与 bogus 块的**入度（入边数）
不可区分** —— 直接打掉 "the bogus block has in-degree 1 while real blocks have >1" 这类
`in-degree` 启发式（该启发式正是 Deflat/MODeflattener 一类工具的判别依据，见 §16）。

**(c) 标识符混淆四算法**：`Random / Directory / Illegal / Overload`。

### 8.2 作者自报的效果与局限

* 用 BinDiff 度量混淆前后相似度（BB / JI / F 相似度）作为指标，声称嵌套 switch 使相似度下降、
  且"even if an attacker … [uses] script because the characteristics of the obfuscation algorithm are
  different"。
* 标识符层：**"replace 65.2% of custom identifiers while guaranteeing program functionality"**；
  时间开销"almost negligible"。
* **作者自认的局限（重要）**：

> "In future work, we will pay attention to generating **more secure opaque predicates** and are not
> [satisfied with current ones]"

—— 即 IOLLVM 的谓词强度本身不足；嵌套 switch 与入度混淆是**结构层**的改良，不是密码学强度。

### 8.3 对本项目的直接价值

`in-degree obfuscation` 与 `nested switch` 是**我们当前完全没做、且实现成本低**的两项：

* 对应 Lua：给**真实块入口**也套一层不透明谓词守卫（复用 `LuaJunkCode` 的收敛式 guard），
  使真实块与垃圾块的入度分布趋同；
* 对应 Lua：在 `LuaControlFlowFlattening` 的块**内部**再做一次"块内符号派发"（把顺序指令切成
  更小的块并对每个小块再派发一次），形成嵌套派发。

---

## 9 Tiella & Ceccato, *Automatic generation of opaque constants based on the k-clique problem for resilient data obfuscation*（SANER 2017）

**引用/链接**：R. Tiella, M. Ceccato. SANER 2017. DOI
<https://doi.org/10.1109/SANER.2017.7884620> ／ IEEE CSDL
<https://www.computer.org/csdl/proceedings-article/saner/2017/07884620/12OmNqyUUz7>
FBK CRIS 记录（我读到摘要的地方）：
<https://cris.fbk.eu/handle/11582/309493>

> **获取状态：仅摘要（正文受限）。** FBK CRIS 的 `main.pdf`
> （`https://cris.fbk.eu/retrieve/ddb241a5-4313-ba8a-e053-3a05fe0afd55/main.pdf`）标注
> "solo utenti autorizzati"，直连 403，jina 代理返回登录页。IEEE CSDL 为付费墙。
> 因此本节的**构造细节无法核实**，只写摘要明确说了什么。

### 9.1 摘要原文可确认的内容

> "The possibility to hide constant values is a basic building block of several obfuscation techniques.
> For example, in XOR Masking a constant mask is used to encode data, but this mask must be hidden too,
> in order to keep the obfuscation resilient to attacks. In this paper, we present a novel technique based
> on the k-clique problem, which is known to be NP-complete, to generate opaque constants, i.e. values
> that are difficult to guess by static analysis. In our experimental assessment we show that our opaque
> constants are computationally cheap to generate, both at obfuscation time and at runtime. Moreover, due
> to the NP-completeness of the k-clique problem, our opaque constants can be proven to be hard to attack
> with state-of-the-art static analysis tools."

### 9.2 可确认的设计思想（以及我**不能**确认的部分）

* 可确认：以 **NP-完全问题（k-clique）** 作为"静态分析无法求解"的载体，生成**不透明常量**
  （不只是谓词）；生成与运行都廉价；用 NP-完全性作为抗静态分析的形式化论据。
* **不能确认**：k 团实例如何嵌入常量计算、如何保证"不透明常量"在运行期恒定、是否需要预计算
  查表/图、以及作者的具体反制分析。**这些我不会替论文补齐**；若下一步真要采用这条路线，
  必须先拿到正文。

### 9.3 为什么这条路线对本项目有吸引力（且有真实可读的旁证）

本项目已有的 `OpaquePredicate` 全部建立在**初等数论恒等式**上，而 §1.5 已经引用了
Collberg 自己的判定：`7y²−1 ≠ x²` "can be cracked by a theorem prover"。
k-clique 路线把"求解难度"从**定理证明**换成**组合搜索（NP-完全）**，这在对抗
§10/§12 那类代数化简器时是**正交**的：GAMBA/MBA-Blast 做的是代数重写，不解决组合搜索。

> 诚实标注：以上是"为什么值得跟进"的**推理**，不是论文原文结论（原文我没读到）。

---

## 10 GAMBA, *Simplification of General Mixed Boolean-Arithmetic Expressions*（WoRMA'23, arXiv 2305.06763）

**引用/链接**：B. Reichenwallner, P. Meerwald-Stadler (Denuvo). *Simplification of General Mixed
Boolean-Arithmetic Expressions: GAMBA.* WoRMA'23.
全文 HTML（我读的是这个）：<https://ar5iv.labs.arxiv.org/html/2305.06763>
arXiv 摘要页：<https://arxiv.org/abs/2305.06763>

### 10.1 定理基础（原文，逐字）

**Theorem 1**（Zhou et al. 的生成原理，也是化简的钥匙）：线性组合
`e(x_1,…,x_t) = Σ_{j=1..s} a_j e_j(x_1,…,x_t)` 恒等于 0 **当且仅当**系数向量
`Y = (a_1,…,a_s)^T` 是 `A·Y = o` 的解，其中 `A = (v_ij) ∈ B^{2^t × s}` 是各 `e_j` 在
`{0,1}^t` 全部赋值下的真值矩阵。一句话：**bitwise 表达式在 `B={0,1}` 上完全确定**。

**Theorem 2**（SiMBA 的依据）：`e ≡ f` 当且仅当 `e(b) = f(b)` 对**全部** `t` 个变量的 0/1 组合成立。

**定义**：
* polynomial MBA：`e(x_1..x_t) = Σ_{i∈I} a_i Π_{j∈J_i} e_ij(x_1..x_t)`；
* linear MBA：`Σ_{i∈I} a_i e_i(x_1..x_t)`（`e_i` 为 bitwise，`a_i ∈ B^n` 常数）；
* general MBA：不一定是 polynomial；非 polynomial 的两大原因被显式命名：
  **exponential MBA**（指数含非常量 MBA，如 `3x^y`）与 **mixed MBA**
  （**bitwise 运算里出现非常量常数或算术，如 `5 + (x|3) − (5&y)`**）。

> **对 Lua 的直接含义**：我们若在 `BAND/BOR/BXOR` 的操作数里放"非 0/−1 的常量"，
> 就落入 GAMBA 明确点名的 *mixed* 类，而 GAMBA **专门**处理这一类（§10.3 的 substitution）。

### 10.2 SiMBA（GAMBA 的核心子程序）

论文先指出：MBA-Blast / MBA-Solver "use Theorem 1 directly to transform each bitwise expression …",
而 **SiMBA** "uses slightly more involved insights that allow to evaluate a linear MBA as a whole on
`B={0,1}` and directly derive a first solution"。

**Algorithm 1（原文逐行）**：

```text
Algorithm 1  Simplification of an MBA e reducible to a linear one (extended SiMBA)
1. Determine linear combination  e~ ≡ e  of conjunctions
2. Identify the number t of variables in e~
3. If t <= 3:
   (a) Try to find a simpler solution using table
4. Else:
   (a) Determine partition P of e~ w.r.t. variables
   (b) For all parts p ∈ P with at most 3 variables:
       i.  Try to find a simpler solution using table
   (c) For all parts p ∈ P with more than 3 variables:
       i.  Try to find a simpler solution using bitwise creation
   (d) Compose the results
```

（其中分支 4 是 GAMBA 新增，3a 是扩展的。）

**关键能力边界**：SiMBA "guarantees to find the simplest solution for all inputs using two variables
and for most inputs using three variables"；MBA-Blast/MBA-Solver 因查表而**限制在 ≤4 变量**
（表大小 `2^{2^t}` 双指数增长）；**SiMBA 对任意变量数都可用**，且"finding simple solutions for all
inputs which are reducible to MBAs with at most three variables"。

### 10.3 GAMBA（非线性 MBA）

**Algorithm 2（原文逐行）**：

```text
Algorithm 2  Simplification of a general MBA e (GAMBA)
1. Parse e into an AST t
2. Repeat until convergence:
   (a) Refine t                                   (Subsection 5.2)
   (b) Identify linear subtrees of t              (Subsection 5.3)
   (c) Try to factorize nonlinear sums            (Subsection 5.4)
   (d) Apply SiMBA to linear subexpressions       (Algorithm 1)
   (e) Collect nodes for substitution
   (f) For all combinations of those nodes:
       i.  ...
```

四个关键技巧（原文）：

1. **Complexity metrics（§4.1）**：GAMBA 不只按"项数"选解，还支持
   **(i) MBA alternation（bitwise/算术交替次数，Eyrolles 提出）**、**(ii) AST 节点数**、
   **(iii) 项数**、**(iv) 字符串长度**，并以次级 metric 打破平手。
   —— *注意：这意味着"用字符串长度/AST 形态伪装"不挡化简，因为 GAMBA 就是按这些 metric 挑最简解的。*
2. **Factorization（§5.4）**：把高阶项的和分解成因子，用于"线性部分被乘积展开掩盖"的情形。
3. **Substitution（§5.5）**：把非线性子式替成临时变量 → 化简 → 回代。原文例：
   `X ∧ y` 先写成 `~( (~X) ∧ y )`... 具体链条为
   `X∧y → ∼((∼X)∧y) → −((∼X)∧y)−1 → ((∼X)|y) − ((∼X)&y)`，
   再用 `(∼X|y) + ((∼X)&y) → ∼X + y`，最后 `∼X → −X − 1`；回代后得 `−x − y`。
   并对"必须同时替换多个子式"的情形**枚举所有子集**（"we collect all nodes which are meant to be
   substituted and run the substitution procedure on all possible subsets of this set"）。
4. **唯一性缺失的诚实说明**：`x²` 与 `x` 在 `{0,1}` 上等价，所以**非线性 MBA 不能只靠
   `{0,1}` 求值判定**（原文例），必须回代验证。

### 10.4 强度宣称（原文数据）

* NeuReduce 数据集（10 000 条线性、2–5 变量）：**SiMBA 与 GAMBA 全部化简到 ground truth**。
* 含非多项式/非可线性化 MBA 的数据集：**"GAMBA can simplify all expression in the dataset, to an
  expression equivalent to the ground truth in almost all cases"**；QSynth EA 数据集上
  "only GAMBA's results are always identical to the ground truth"。
* 速度：**"within 50 ms typically"**，但 "GAMBA is about ten times slower compared to SiMBA"。
* 自认失败面：**"MBAs that use a high number of variables and cannot be split into expressions with
  fewer variables in a meaningful way, and … MBAs that have constants within bitwise operations"**；
  "for the 8 runs where the verification was not successful …, the expressions were drastically shorter
  as well"。

### 10.5 对本项目的结论

* **我们的 `MBA_XOR`（`((a|b) − (a&b)) == (a ~ b)`）与 `MBA_ADD`（`x+y = (x^y) + 2*(x&y)`）型
  恒等式属于"线性 MBA"，正是 GAMBA/SiMBA 保证能解的一类。** 它们只能提高可读性成本，
  不能提高求解下界。
* **`MBA_DE_MORGAN` / `SHIFT` / `XOR_SELF` 同样在代数重写射程内**（`~X → −X−1` 一条规则即可消解）。
* 真正提高下界的方向是 §10.3(4) 与 §22 的组合：**多变量 + 非线性 + 大常量 + 每站点唯一**，
  并且**主动放弃"手写恒等式模板"**。

---

## 11 *On Simplifying Mixed Boolean-Arithmetic Expressions*（Automatic Control and Computer Sciences）

**引用/链接**：Automatic Control and Computer Sciences（Allerton Press）／Springer：
<https://link.springer.com/article/10.3103/S0146411624700299> ／ ACM DL:
<https://dl.acm.org/doi/10.3103/S0146411624700299>

> **状态：未能获取原文，仅据检索摘要，待核。**

按其题名与检索所得要点，该文研究 **MBA 可化简的边界**；其核心结论（"线性 MBA 可以被直接、
可行地求解"）在 §10 GAMBA 与 §12 MBA-Blast 两条**可读**来源中被独立、反复地引用与证实
（GAMBA 原文：*"recent research [19] suggests that all linear MBAs can be solved in a straightforward
way"*；MBA-Blast 原文：*"Only MBA-Blast succeeds in simplifying all obfuscated MBA expression with
negligible overhead"*）。

**因此本项目对这条结论的采用是有依据的**，但**具体定理、算法与实验数字我没有读到原文，
不在本文中引用**。`docs/obfuscation-algorithms.md` 中把它标为"设计依据（`MBA_XOR`）"、
并预期 `MBA_XOR` 会被化简 —— 这个判断是对的，且应升级为**默认假设**（见 §25）。

---

## 12 MBA-Blast, *Unveiling and Simplifying Mixed Boolean-Arithmetic Obfuscation*（USENIX Security '21）

**引用/链接**：B. Liu, J. Shen, J. Ming, Q. Zheng, J. Li, D. Xu. *MBA-Blast.* USENIX Security '21.
会议页：<https://www.usenix.org/conference/usenixsecurity21/presentation/liu-binbin>
全文 PDF（我读的，19 页）：<https://www.usenix.org/system/files/sec21-liu-binbin.pdf>
代码：<https://github.com/softsec-unh/MBA-Blast>
（注：检索中 arXiv:2106.08913 常被误挂到这个标题下，实际它是 §22 的 LOKI。）

### 12.1 核心思想（原文）

> "Our method replaces bitwise operations with specific MBA expressions … [then] a set of reduction
> rules to simplify MBA obfuscated expressions."

即 **Table 2 的"双向替换表"**：当表达式含有 bitwise 运算时，用恒等的 MBA 形式替换（`ReplaceBoolWithMBA`），
再用普通算术化简（`ArithReduce`）得到规范形，最后用同一张表反向匹配回更简的 bitwise 形式
（`ReplaceMBAWithBool`）。原文给出的一个反例：

> "`−y + (x ∧ y) − 1` is replaced by `x ∧ ¬y`"

（此处 `∧` 为 bitwise XOR：`x ⊕ y − y − 1 = x & ~y`。）

### 12.2 Algorithm 1（原文逐行）

```text
Algorithm 1  MBA-Blast Algorithm
1: Input: MBA expression E
2: function MBA-BLAST(E)
3:   for e_s ∈ SubExpr(E) is MBA do
4:       e_s ← reducible
5:   end for
6:   while e ∈ SubExpr(E) is reducible do
7:       ReplaceBoolWithMBA(e)
8:       e' ← ArithReduce(e)
9:       ReplaceMBAWithBool(e')
10:      if e' is simpler than e then
11:          update(E, e')
12:      else
13:          e ← irreducible
14:      end if
15:  end while
16:  return E
17: end function
```

**复杂度度量**：`e'` 与 `e` 的比较用 **DAG 节点数**（原文："The complexity of `e'` and `e` are
measured by the number of their Directed Acyclic Graph (DAG) nodes."）。
**递归**："The algorithm keeps simplifying MBA sub-expressions and it terminates when no reducible
sub-expression is available."

### 12.3 效果与局限（原文）

* 数据集 1：**"MBA-Blast 62 0 0 100.0 … 0.009"**（62 条全对、0 失败、耗时 0.009 s 级）。
  数据集 2（10 000 条）：**"MBA-Blast 10,000 0 0 100.0 … 0.053"**。
* **"MBA-Blast significantly outperforms existing approaches. Only MBA-Blast succeeds in simplifying
  all obfuscated MBA expression with negligible overhead."**
* 自认局限（原文）：

> "First, the simplification result from MBA-Blast may not [be the simplest] … an adversary may attack
> MBA-Blast by inten[tionally introducing] … dependency between these rounds."

### 12.4 对本项目的结论

MBA-Blast 与 GAMBA 是两个**同向、互补**的工业级威胁：
GAMBA 强在**非线性 + 替换 + 因子分解**，MBA-Blast 强在**线性 + 极快 + 双向表匹配**。
任何"手写恒等式 + 常量操作数"的不透明谓词/状态编码都会落在两者之一的射程内。
**唯一可用的对策是 §22 的"成千上万条形式化验证过的唯一 MBA"而不是手写模板。**

---

## 13 goomba / gooMBA（Hex-Rays IDA 插件）

**引用/链接**：<https://github.com/HexRaysSA/goomba>（README 已读）；
背景博客：<https://hex-rays.com/blog/deobfuscation-with-goomba/>

### 13.1 README 要点（原文）

* "gooMBA is a Hex-Rays Decompiler plugin that simplifies Mixed Boolean-Arithmetic (MBA) expressions.
  It achieves this using several heuristics and algorithms to achieve **orders-of-magnitude better
  performance** than existing state-of-the-art solutions."
* Core Features（逐条）：
  * "Full integration with the Hex-Rays Decompiler"
  * **"Simplifies linear MBAs, including opaque predicates"**
  * "Handles sign extension for linear functions"
  * **"Verifies soundness of simplifications using the z3 SMT solver"**
  * **"Simplifies non-linear MBAs with the use of a function fingerprint oracle"**
* 指纹 oracle："The input for generating it is a list of candidate expressions in
  **msynth syntax** … generate a binary oracle file which can then be used by the plugin by specifying
  the path to it in `goomba.cfg` (`MBA_ORACLE_PATH`)." 官方预计算 oracle：
  <https://hex-rays.com/products/ida/support/freefiles/goomba-oracle.7z>
* 自 v8.3 起 **goomba 直接随 IDA Pro / IDA Teams 分发**。

### 13.2 关键威胁判断

1. **"Simplifies linear MBAs, including opaque predicates"** —— 这句话是**对我们现有实现的直接判决**：
   `PARITY / NON_SQUARE / MBA_ADD / MBA_XOR / MBA_DE_MORGAN / SHIFT / XOR_SELF` 全部是线性 MBA
   或初等数论恒等式，goomba 开箱即用。
2. **"Verifies soundness … using z3"** —— 它不是"猜"，是**带证明的化简**。所以被它化简掉的谓词
   是**确定**被消掉的，不存在"可能失败"的侥幸。
3. **非线性 MBA 用 fingerprint oracle** —— 只要候选表达式集合里有我们的形态（msynth 语法生成的
   常见模板库），就会被命中。**因此"非线性"本身不是护身符，'不在 oracle 候选集里'才是。**
4. IDA 内置 ⇒ 攻击者零成本获得该能力。

---

## 14 D810, *A journey into control flow unflattening*（eShard）

**引用/链接**：<https://www.eshard.com/blog/d810-a-journey-into-control-flow-unflattening>
（注意：仓库旧文档里的 `https://www.eshard.com/blog/...` 可用；`eshard.com/posts/...` 现 404。）

### 14.1 平坦化的解剖（原文，三种形态）

> "A state variable is used to keep track of which block of code should be executed.
> A dispatcher which uses the state variable to find which block to execute next.
> Each block of the original function is modified: instead of specifying the next blocks to execute using
> a branch, it updates the state variable and jumps to the dispatcher."

> "For OLLVM, **the state variable is a 32 bit constant with high entropy (this was used as an heuristic
> by Rolf Rolles to identify flattened functions)**. The dispatcher consists of multiple jumps that
> compares this state variable to other 32 bits constants."

> "For Tigress Flatten with switch dispatcher, the dispatcher is a switch statement …
> For Tigress Flatten with indirect dispatcher, the address of each of the function basic block is stored
> in a global jump table …"

### 14.2 反平坦化策略（原文三步）

> "First, we have to identify the dispatcher blocks and the state variable.
> Then, for each block which jumps to the dispatcher entry block, we have to:
>   Find the value of the state variable at the end of this block.
>   Find the address of the next block to execute based on the dispatcher and the value of the state variable.
>   Replace the jump to the dispatcher by a jump to the next block."

三个它明确点出的难点：

> "Finding the value of the state variable may not be easy: it can be computed using constant obfuscation
> or opaque predicate => We need to be able to compute it dynamically."
>
> "The dispatcher blocks may have a side effect and therefore they can't be totally removed =>
> We need to have a way to detect these instructions and copy them when we replace the jump to the
> dispatcher by the jump to the next block."
>
> "Multiple dispatchers can be used inside one function."

### 14.3 实现机制（可读到的工程细节）

* **`MopTracker`**：从块尾**反向追踪**参与状态变量计算的指令；
* **Microcode 解释器/仿真器**：`"we created a Microcode emulator which allows us to evaluate any
  microcode instruction"`，用它 `"execute the instructions found by our MopTracker to compute the value
  of the state variable for any path used"`，并 **"use this microcode interpreter to emulate the
  dispatcher and to find out what was the serial of the next block to execute"**；
* **多值情形的块复制**：
  > "when a block has multiple possible values for the state variable it can become quite complex …
  > To deal with such cases, we duplicate several blocks of the function to ensure we are back in the
  > simple case … The main idea of our algorithm is to duplicate any block which appears in (at least) 2
  > paths with (at least) 2 different predecessors. By doing that recursively, we will ensure that all
  > blocks that jump to the dispatcher will have only one possible value for the state variable."
* **规则化与迭代**：核心规则 `GenericDispatcherUnflatteningRule`：
  > "The rule explores all functions' blocks and tries to find all dispatchers and the state variables.
  > This is the only part which is specific to the obfuscation techniques … If no dispatcher is found,
  > we stop there … If we didn't find how the state variables are computed, we won't fix this block …
  > If we made any modification to the control flow of the function, we ensure that the rule is called
  > again."
* **自认的局限**：
  > "if the state variable is stored/read to/from memory, there may be side effects that we won't be able
  > to detect (e.g. because of aliasing). This may result in incorrect or impossible simplification.
  > Implementing a symbolic execution engine over microcode could help us design a much cleaner solution."

### 14.4 对本项目的直接判决

* **我们当前的 CFF 正中其射程**：state 是**常量池里的整型常量**（比 OLLVM 的"高熵 32 位常量"
  更糟 —— 连高熵都没有，而且是 `random.nextInt(1, Int.MAX_VALUE)` 的正数），dispatcher 是
  **直筒 `EQK` 比较链**。D810 的 `GenericDispatcherUnflatteningRule` 只需识别出
  "多个块跳向同一个入口块、该块内是若干次 `EQK state, K[x]`" 即可逐块替换。
* **唯一让 D810 变难的两点**（论文自己说的）：**(a)** 状态值由**常量混淆或不透明谓词**计算
  → 需要动态求解；**(b)** **多个 dispatcher** + dispatcher 有副作用。
  这两点恰好就是 §23 里"下一步必须做"的内容。
* `MopTracker` 的**反向数据流**是"符号执行"的轻量替身：所以"把状态值拆成多步、
  穿插内存/表操作（Lua 里是 `SETTABLE/GETTABLE`）"能提高其失败率 —— 但它自己也说
  "Implementing a symbolic execution engine over microcode could help" 意味着这条路只提高**成本**。

---

## 15 Lebedev, *Control Flow Flattening deobfuscation using symbolic execution*（ПДМ. Приложение, 2021）

**引用/链接**：В. В. Лебедев. *Деобфускация Control Flow Flattening средствами символьного
исполнения.* Прикладная дискретная математика. Приложение, 2021, №14, 134–138.
DOI <https://doi.org/10.17223/2226308X/14/29>
摘要页：<https://www.mathnet.ru/eng/pdma548>
全文 PDF（我读的，5 页，俄文）：
<https://www.mathnet.ru/php/getFT.phtml?jrnid=pdma&paperid=548&what=fullt&option_lang=eng>

### 15.1 摘要（原文要点，俄文）

方法用**符号执行**恢复被 CFF 混淆的程序逻辑：CFF 把每个基本块编号，块尾把后继编号写入
**控制寄存器**（управляющий регистр）再跳到**分派器**（диспетчер），分派器按编号跳转；
在 C 里就是 `while` 循环里套 `switch`。

### 15.2 算法 1（原文逐步翻译，保留其精确结构）

**输入**：被混淆的可执行文件 + 待反混淆函数地址；**分派器识别函数**
`f : State → (s, e, c)`，其中 `State` 是符号状态，`s` 是分派器首条指令地址，`e` 是最后一条，
`c` 是控制寄存器名（`f` 对部分 `State` 部分定义）。
**输出**：补丁集合（可能包含原程序没有的地址，需要新建可执行段）。

```text
1:  Jumps = ∅
2:  从给定地址开始符号执行；忽略对其他函数的调用；若没有可行的符号状态则转到 8
3:  若检测到分派器（f 对当前状态有定义），取出：跳入分派器的地址 a、
    符号状态 State0（寄存器+内存）、以及控制寄存器可能取值集合 X
4:  对每个 x_i ∈ X：把 State0 中的控制寄存器改成 x_i，得到 State0_i
5:  对每个 State0_i 继续符号执行，直到到达不属于分派器的地址 b_i。
    必须满足：对任意 State0_i 存在唯一的 b_i（因为 b_i 只应依赖控制寄存器，
    而它在第 4 步已被固定；违反即说明违反了对被反混淆代码的约束）
6:  把全部 (a, x_i, b_i) 加入 Jumps
7:  转 2
8:  符号执行结束，Jumps 含所有已发现分派器的全部转移
9:  对每个唯一 a 生成补丁：若 (a, x_i, b_i) 只有唯一一对，把地址 a 处的指令改成
    到 b_0 的无条件跳转；若有多对，新建可执行段，把 a 处指令改成跳入该段，
    在新段里生成"把控制寄存器与各 x_i 比较、相等则跳到 b_i"的逻辑
```

### 15.3 论文自述的适用约束（原文，第 2 节）

1. 分派器所需数据要么在**只读内存**，要么在**同一函数内初始化**；
2. **控制寄存器的取值不依赖被反混淆函数内部调用的函数，也不依赖与它无关的其他函数**；
3. **在每个分派器里，跳转地址必须只依赖控制寄存器**；
4. **分派器必须是连续代码段**；
5. **死代码会被一并"反混淆"**（论文明说死代码检测与删除不在其范围内）。

与相关工作的对比（原文）：面向 OLLVM 的 *Automatic Deobfuscation of Android Native Binary Code*
更专门；**本方法更通用、不对被混淆函数结构做假设**；SATURN 则试图整体优化+重编译。

### 15.4 对本项目的直接判决

* 约束 3（"跳转只依赖控制寄存器"）与约束 4（"分派器连续"）是**可以主动违反的**：
  * 让状态值**部分依赖被调用的函数返回值**（违反约束 2/3 的"只依赖控制寄存器"）：
    例如 state 由一个被调用的辅助闭包（注入的原型）返回，或依赖 `tostring/_VERSION` 之类的
    弱随机运行期值。
  * 让**分派器不连续**：把比较链切成若干段，中间夹带真实代码（Lua 字节码层完全可行：
    把部分 `EQK` 分派片段放到函数尾部不连续的位置，用 `JMP` 串联）。
* 约束 5 提醒我们：**死代码/环（`unreachableBlocks` / `deadCycleLength`）会被一并反混淆** ——
  它们是 **cost** 而不是 **resilience**，不应计入强度宣称。

---

## 16 CaDeCFF, *Compiler-Agnostic Deobfuscator of Control Flow Flattening*（Internetware '22）

**引用/链接**：W. Dong, J. Lin, R. Chang, R. Wang. *CaDeCFF.* Internetware '22.
ACM DL: <https://dl.acm.org/doi/10.1145/3545258.3545269>
全文 HTML（Cloudflare 拦 curl，我用 jina 代理读取）：
<https://dl.acm.org/doi/fullHtml/10.1145/3545258.3545269>
（经 `https://r.jina.ai/https://dl.acm.org/doi/fullHtml/10.1145/3545258.3545269`）

### 16.1 针对既有工具的批评（原文）

> "The basic block of CFF obfuscated binary can be divided into five categories: **Prologue**,
> **Dispatcher**, **Pre-dispatcher**, **Return** and **Relevant**. Dispatcher and Pre-dispatcher are
> useless blocks which are added by obfuscator."

> "Deflat identifies the first conditional-jumping block as **Main dispatcher**, and the predecessor of
> Main dispatcher as Pre-dispatcher. MODeflattener identifies the basic block with the most predecessors
> as Pre-dispatcher. **Then they identify the predecessors of the Pre-distributor as Relevant blocks.
> They will fail if the CFG features change.**"

> "Figure 2(b)-(d) are the CFGs with compilation optimization option O1, O2 and O3, which do not have a
> fixed graph structural features."

即：**"结构性启发式"（找前驱最多的块 / 找第一个条件跳转块）在编译优化后会失效**。

### 16.2 CaDeCFF 的算法（原文）

1. **数据流分析定位状态变量与分派指令**：

   > "After getting the state variables, CaDeCFF generates dispatcher instructions by data flow analysis.
   > Dispatch instructions include two types of instructions: instructions to assign value to state
   > variables and instructions for conditional branches."

2. **Algorithm 1：生成 super block**（原文）：

   > "First, CaDeCFF gets dispatcher blocks by using function `GetDispatchBlocks` at Line 1. Dispatcher
   > blocks can roughly be classified into two types: basic blocks which contain dispatch condition
   > branches and basic blocks which only contain a single unconditional jump instruction and whose
   > predecessors or successors belong to dispatcher blocks. CaDeCFF gets the entry addresses of all
   > super blocks, which contain function address f and all successors address of dispatcher blocks …
   > CaDeCFF returns `addr_sb_map` as the result of useful block identification."

   输出映射：`addr_sb_map := addr → sb`。

3. **Algorithm 2：super block CFG 生成**，用 **Intra-SE 与 Inter-SE**（块内/块间符号执行）：

   > "The `sb_edge_map` is a map from super block to the successor super block list:
   > `sb_edge_map := sb → next_sbs`. The set `W` is the work-list with operations `add` and
   > `removeNext` … The item is a tuple of two elements (symbolic state `state` and super block `sb`)."

4. **Algorithm 3：基于二叉树的代码生成**：

   > "For leaf node, CaDeCFF generates an unconditional jump instruction at Line 6. For non-leaf node,
   > CaDeCFF uses postorder for traversing binary tree and generates `true_code` and `false_code` …
   > The code of a non-leaf node consists of four sequential parts: instructions of node `tree.inss`,
   > a conditional jump instruction to the address of `true_code`, `false_code` and `true_code` …
   > Some paths may have the same target, CaDeCFF uses the cache mechanism."

### 16.3 对本项目的判决

CaDeCFF 的整个立论是"**不依赖 CFG 结构特征**"——它用**数据流 + 符号执行**。
这对我们的启示是**否定性的**：

* "把 dispatch 顺序打乱"、"把块布局重排"、"让 Pre-dispatcher 不是入度最大的块"
  这类**结构伪装**对 CaDeCFF 无效（它不看这些）。
* 有效的是**让状态变量的数据流本身不可解**：这也再次指向 §23 第 1 条
  （state 运行期派生 + MBA 编码 + 多步计算）。

---

## 17 Rolles, *Unpacking Virtualization Obfuscators*（WOOT '09）

**引用/链接**：R. Rolles. *Unpacking Virtualization Obfuscators.* USENIX WOOT '09.
PDF（我读的，7 页）：<https://www.usenix.org/legacy/events/woot09/tech/full_papers/rolles.pdf>

### 17.1 VM 化保护的结构（原文）

* 被保护代码被翻译成 **bytecode**，由解释器（VM harness）执行；解释器在宿主进程里模拟一个
  "VM context structure"（虚拟寄存器/栈/标志位），进出 bytecode 程序时保存/恢复上下文。
* **handler（opcode 分派器）与 VM harness 是混淆目标**，且各样本可独立定制。

### 17.2 作者面对的三类混淆与破解方法（原文）

**(a) 结构同构 + 顺序打乱**：

> "the implementation of each VM opcode dispatcher was identical … and the ordering of the dispatchers
> within the switch table. … the dispatchers can simply be fingerprinted to decide which [is which]"

**(b) "Junking"（只插垃圾指令，非变异）**：

> "Since the obfuscation on the x86 representation of the VM harness and dispatchers consisted of
> 'junking' only (insertion of random but harmless instructions between the legitimate instructions
> belonging to the handlers) as opposed to metamorphic obfuscation, **every instruction which was
> present in the non-obfuscated handler is present in its literal form in the obfuscated handler**.
> Therefore, we can simply use **regular expressions upon the disassembly of the obfuscated handlers to
> perform a comparison with the non-obfuscated handlers, and choose the largest match.**"

**(c) 常量参数的去混淆**：对从指令流取常量的 handler，

> "to slice out their constant deobfuscation routines. In the example above, we simply apply slicing
> techniques upon those instructions that modify the register al to obtain the constant deobfuscation
> routine."

**(d) 作者给出的更强方法（面向未来）**：

> "By performing **pure symbolic execution** upon the VM opcode handlers, we obtain a representation of
> each handler as a mathematical function that is a map from its input space (the VM context structure,
> VM EIP and its surrounding bytes, and the program's memory) into the same space. By focusing on the
> transformation of one state to another, **we can ignore irrelevant details such as those introduced
> through obfuscation (including metamorphic obfuscation).** We can then apply a theorem prover to
> determine if the handler computes the same function as one of the handlers that are known a priori."

**(e) 其他在野手法**：有些样本"**read the instruction stream backwards**"（递减 EIP）、
对**字节值↔handler 的映射**再做混淆、混淆 **handler 地址**。

### 17.3 对本项目的判决（VM 化设计红线）

| Rolles 揭示的可破点 | 我们 VM 化时必须避免 |
|--------------------|-------------------|
| handler 之间**结构同构**（同一套模板，只有常量不同） | handler 必须**每样本异构**：不同指令用不同指令序列实现（甚至多套等价实现轮换），而不只是换常量 |
| 只做 **junking**，原指令字面保留 | 必须做**变异（metamorphic）**：等价替换 + 指令重排 + 寄存器重编号 + 等价垃圾指令*参与*计算。**只插 NOP 等于没做** |
| handler 顺序/映射固定，可指纹化 | opcode 编号**每原型/每样本随机重排**（Clyde 的 shuffled opcode mapping），并**打乱 handler 在代码中的物理顺序** |
| 常量去混淆例程可以**切片**出来 | 常量的解码必须**与状态强耦合、跨多条指令交织**，使其无法被局部切片 |
| handler 可被"符号执行→查表"识别 | handler 的输出空间要**大**（不能只有几种可枚举的行为）；引入**不可静态求值的运行期值**（如宿主 `_VERSION` 派生量）参与 |

---

## 18 Prometheus（Lua/LuaJIT 混淆器）

**引用/链接**：<https://github.com/prometheus-lua/Prometheus>
README（raw，我读的）：<https://raw.githubusercontent.com/prometheus-lua/Prometheus/main/README.md>

### 18.1 README 事实

* "Prometheus is a Lua obfuscator written in pure Lua."
* "It applies a range of **AST-based transformations** … These include techniques such as
  **control-flow flattening**, **constant encryption**, and other Lua-specific obfuscation strategies."
* 支持 **Lua 5.1** 与 **LuaU（basic support, not fully finished）**。
* README 的示例输出形态（可核对的**结构**）：
  ```lua
  return(function(...)local L={"afT6mf1V","/7mJXsuvmE1c/fT3";"tn1ZSn6=","37ghSJM="; ...}
  ```
  —— 即**字符串常量全部编码后放进一个表 `L`**，外面包 `return(function(...) ... end)(...)`。
* 工程做法：CLI + 预设（`--preset Medium`）、Docker 测试套件同时跑 lua5.1 与 Luau、
  自定义 license（商业使用需署名）。

### 18.2 与 `docs/obfuscation-algorithms.md` §4 的关系

现有设计文档把 Prometheus 列为 "LuaJIT 字节码 → 自有 VM/IR" 的参考。
**按我实际读到的 README，Prometheus 是 source-level（AST）混淆器，README 并未声称做 LuaJIT
字节码到自有 VM 的翻译**（它声称的是 AST 变换 + CFF + 常量加密）。

> 这一点需要修正：仓库文档 §4 对该项目的描述（"LuaJIT 字节码 → 自有 VM/IR"）**在我读到的 README 中
> 找不到依据**。它的可借鉴之处是**工程与产物形态**（字符串表化 + 闭包包裹 + 预设体系），
> 而不是 VM 化的算法参考。

### 18.3 可借鉴的具体做法

1. **字符串集中成表 + 统一解码**：我们当前的 `LuaStringEncryptor` 是"就地替换 LOADK 为 JMP→stub"，
   每条常量一个 stub；Prometheus 的"单表 + 索引"形态在**产物体积**与**解码点收敛**上更好，
   但也**把解码入口集中成一个可被一次性 hook 的点**（成本/收益需权衡，见 §24）。
2. **闭包包裹整个 chunk**（`return(function(...) ... end)(...)`）：可隐藏全局访问与顶层变量名。
3. **预设体系**（Light/Medium/…）与我们 `ObfuscationConfig.LIGHT/BALANCED/AGGRESSIVE` 同构，
   可以互相校验参数梯度是否合理。

---

## 19 Clyde Protection（Luau/Lua VM 化保护）

**引用/链接**：<https://github.com/sfr-development/Lua-Obfuscator-Clyde-Protection>
README（raw，我读的）：<https://raw.githubusercontent.com/sfr-development/Lua-Obfuscator-Clyde-Protection/main/README.md>

### 19.1 README 事实

* "**Advanced Luau Obfuscator with VM-Based Protection**"；"a from-scratch Luau obfuscator … complete
  **Lexer > Parser > AST > Obfuscator > VM** pipeline"，TypeScript 实现。
* 三层能力（原文）：
  * "**Multi-Pass Obfuscation** — Identifier renaming, string encoding, and control flow scrambling
    applied as composable AST passes"
  * "**Dual VM Architectures** — Both **stack-based** and **register-based** virtual machines with
    configurable protection levels"
  * 配置项：`scramble`（默认 `false`，"Enable control flow scrambling"）、
    `vmType`（`"none" | "stack" | "register"`）。
* **Control Flow Scrambling 的实现描述（原文）**：
  > "`ControlFlowScrambler.ts` — Restructures linear control flow into **dispatch-table loops with
  > opaque predicates**."
* **VM 三级保护**：
  > "**Normal** — Shuffled opcode mapping, encoded constants, flattened control flow"
  （README 提到三个级别，Normal 是其中之一；更高级别为 "Full protection"。）

### 19.2 可借鉴清单（与本项目 §4 规划逐条对应）

| Clyde 做法 | 本项目对应规划 | 备注 |
|-----------|---------------|------|
| shuffled opcode mapping | `docs/obfuscation-algorithms.md` §4 步骤 2「opcode 编号随机重排」 | ✅ 方向一致，应保持"每原型独立编号表"而不仅是全局一张表 |
| encoded constants | 已有 `LuaStringEncryptor`（LCG keystream） | 应扩展到**指令流加密**（§4 步骤 3） |
| flattened control flow inside VM | 已有 `LuaControlFlowFlattening` | VM 内部 dispatch 也必须平坦化/间接化，否则 handler 表就是明文地图 |
| "dispatch-table loops **with opaque predicates**" | 尚未做 | **我们当前 dispatcher 没有任何不透明谓词**（见 §23） |
| stack-based 与 register-based **双 VM** | 未规划 | 这是**抗指纹**的关键：攻击者不可能用同一套脚本同时打两种 ISA 形态；值得纳入 §4 |
| 产物"self-contained Luau script" | 我们用"真实 luac 预编译原型注入" | 我们的做法更抗源码级检测（无新语法依赖），但依赖 `luac` 版本预编译产物；Clyde 的自包含脚本在分发上更简单 |

### 19.3 注意

Clyde 是**产品级描述**（README 级），没有给出强度评估或形式化论证。
它对本项目的价值是**架构与配置梯度**的对照，不是强度证据。

---

## 20 Luo, Ming, Fu, Peng, Li, *Reverse Engineering of Obfuscated Lua Bytecode via Interpreter Semantics Testing*

**引用/链接**：C. Luo, J. Ming, J. Fu, G. Peng, Z. Li.
*Reverse Engineering of Obfuscated Lua Bytecode via Interpreter Semantics Testing.*
NSF PAR 开放存取副本（我读的，15 页 PDF）：<https://par.nsf.gov/servlets/purl/10540556>

> 这是任务清单中 NDSS 那篇的**可读替代**（原因见 §0.2 第 20 条）。它与本项目同域（Lua 字节码混淆），
> 且给出了**在野混淆的真实形态**与**完整反制方法**，威胁建模价值高于一份"流行率测量"。

### 20.1 在野形态（原文，逐条）

* 动机："A growing number of IoT malware authors switch to Lua for malicious payload development and
  then distribute malware in **bytecode form**."
* 混淆定义：
  > "malware authors obfuscate standard Lua bytecode into a **customized bytecode specification**.
  > Only the attached interpreter can execute that particular bytecode file."
* 最小可用混淆（原文）：
  > "**GodLua** obfuscates [by randomizing] the bytecode instruction set (i.e. opcode); it attaches a
  > modified interpreter to execute the new bytecode file."
* 与虚拟化的关系（原文）：
  > "Lua bytecode obfuscation bears a striking resemblance to **code virtualization obfuscation** …
  > Lua bytecode is emulated by an embedded interpreter."
* 可加码：`"the opcode randomization could be a one-to-[one mapping] … malware authors can further
  obfuscate them by using other obfuscation schemes, such as **opaque [predicates]**"`（原文此处被截断，
  只能确认提到 opaque predicates）。
* 关键约束：**一个 opcode 占 7 bit ⇒ 只能取 `0x00..0x7F`**；
  > "An opcode occupies 7 bits, and thus a customized opcode [has 128 possible values] … the OpMode of
  > operands is decided by the opcode, as long as we find the opcode's semantics, its associated operands
  > are [determined]."
* Lua 有两种解释器结构：**decode-dispatch 循环** 与 **indirect-threaded（间接线索化）**；
  后者"violates the decode-dispatch loop [assumption]"，
  使依赖"找 decode-dispatch 主循环"的既有反混淆方法失效。

### 20.2 反制方法：interpreter semantics testing（原文）

> "Given a customized interpreter used to execute obfuscated Lua bytecode, we construct a set of
> **LuaGadgets** that can adapt to the customized interpreter. Each LuaGadget contains a carefully chosen
> opcode sequence to fulfill an observable calculation—it is designed to test one or two particular
> opcodes at a time. Next, we **mutate unknown opcode values** to generate a bunch of test cases and run
> them using the customized interpreter; we can observe the expected result only when the mutation hits
> the opcode's right value. We perform **test case prioritization** to cost-effectively recover the
> semantics of all obfuscated opcodes."

具体流程（原文 §III）：

1. **提取自定义字节码文件格式**（从解释器二进制里恢复字段含义，改 `luac` 后端以产出该格式的 LuaGadget）；
2. **构造 LuaGadget 模板**：为每个 opcode 设计最少的指令序列完成一个**可观测计算**（如测 `ADD`：
   两条 `LOADK` + 一条被测 `ADD` + 打印）。对控制流 opcode（`FORPREP`/`FORLOOP`）也构造对应模板；
   对 `GETTABUP/CALL/RETURN` 用**特制模板**（`RETURN` 单独测试，因为错误 opcode 会让解释器报异常，
   "that means the opcode mutation hits the opcode"）；
3. **变异 opcode 值 `0x00..0x7F`** 并解释执行，命中时观测到期望输出；
4. **测试用例优先级**：按 **opcode dependency** 分层 L0–L4：
   > "Two opcodes in parentheses mean they are tested at the same time … L1 represents the LuaGadgets having
   > one unknown opcode based on L0; L2 … only having one additional unknown opcode based on L0 + L1 …
   > The opcodes in L4 have complicated semantics and therefore reveal the maximum degree of opcode
   > dependency, so we test them only when the other four layers of opcode values have been recovered."
   原文提到单个 LuaGadget 里 opcode 种类上限为 **11**，
   且"the cost of LuaGadget testing increases **exponentially** with the number of unknown opcodes"。
5. **回译**：恢复全部 opcode 语义后，把混淆字节码翻回可分析形式。

### 20.3 效果（原文）

* "**LuaHunt** is able to recover the obfuscated bytecode's semantics **within 90 seconds** for each test
  case, and all of our deobfuscation results can pass the correctness [check]"，包括
  "the complicated one whose bytecode handlers are further obfuscated by code [obfuscation]"。
* 相对既有工具：**"outperform[s] these deobfuscation tools by one or two orders of magnitude."**
* 黑盒性：
  > "Our approach makes no assumptions about the interpreter's structure and is free from analyzing the
  > numerous execution traces of opcode handlers."

### 20.4 对我们 VM 化路线的**致命提示**（必须写进设计）

1. **"opcode 空间只有 7 bit（128 值）+ 每个 opcode 的语义是黑盒可测的"**
   ⇒ **单纯随机化 opcode 编号没有任何强度**：128 值穷举 × 可观测计算 = 90 秒内全恢复。
   我们在 `docs/obfuscation-algorithms.md` §4 步骤 2 里规划的"opcode 编号随机重排 + 加垃圾 opcode"
   **必须被降级为纯 cost 措施**，不能当作保护。
2. 真正的对抗点在于**打破"单条指令 = 单一可观测计算"这一前提**：
   * **多条 Lua 指令合并成一条 VM 指令**（超指令/superoperator），使 opcode 语义不是"加法"而是
     "一段依赖上下文的行为"；
   * **同一 opcode 依状态/上下文改变语义**（多态 ISA）：使"变异单条 opcode + 观察输出"不再能确定语义；
   * **handler 的行为依赖不可静态求值的运行期值**，使黑盒测试的输出不确定（但要保证自洽）。
3. 原文的 LuaGadget 技术在**我们有"真实 luac 预编译原型注入"能力**的前提下对它尤其有效
   （我们本身就证明"用改过的 luac 产出任意字节码"是可行的）—— 所以**攻击者也会这么做**。

---

## 21 *Enhancing the Insertion of NOP Instructions to Obfuscate Malware via Deep Reinforcement Learning*（Computers & Security；arXiv 2111.09626）

**引用/链接**：arXiv:2111.09626。全文 HTML（我读的）：
<https://ar5iv.labs.arxiv.org/html/2111.09626> ／ <https://arxiv.org/abs/2111.09626>
期刊：Journal of Computers & Security（arXiv 页注明）。

### 21.1 问题设定（原文）

> "We propose a general framework powered by a **Double Q-network** to induce misclassification over
> malware families. The framework trains an agent through a convolutional neural network to **select the
> optimal positions in a code sequence to insert dead code instructions** …"

* 把"插入死代码"建模为 **MDP `(S, A, T, R)`**：
  * `S`：mnemonics 序列（可执行文件的汇编码序列）；
  * `A = {insert_0, insert_1, …, insert_n}`：在位置 `i` 插入一条 NOP；
  * `R`：**"the difference between the loss of the previous state and the loss of the current state"**，
    即 `r_t ∝ loss_t − loss_{t-1}`（交叉熵损失差）；
  * 用 **Double Q-learning + experience replay**，`ε` 从 1.0 退火到 0.5，
    `γ = 0.99997`，episode 上限 50 次修改，每族训练 1000 episode。
* 网络结构（原文）：mnemonics → word embedding → **size-3 卷积** → time-distributed 全连接
  （每个位置输出 1 个 Q 值）→ softmax 得到"在每个位置插 NOP"的 Q 值。

### 21.2 结果（原文）

* 分类器准确率 **98.65% → 56.53%**；对 `Kelihos_ver3 / Simda / Kelihos_ver1` 族
  **evasion rate 100%**；
* "the average number of instructions needed to mislabel malware in comparison to a random agent
  **decreased by 33%**"；某些族上 NOP 数量下降 76%–92%。
* 失败面与原因（原文）：`Ramnit / Lollipop / Tracur / Obfuscator.ACY` 族失败；原因是这些族的样本
  **本来就含 NOP**，或指令序列长一倍以上，50 次插入不够。
* 未来工作（原文）：加入更多死代码指令形态 —— **`MOV Reg, Reg`、`ADD Reg, 0`、`SUB Reg, 0`、
  `SHL Reg, 0`**；以及"研究其它常见混淆技术如 **instruction substitution、subroutine reordering、
  code reordering through jumps**"。

### 21.3 对本项目的结论（为什么这篇重要，尽管它是恶意软件侧论文）

1. **"位置"比"数量/类型"更决定效果**：随机插垃圾 vs RL 选位置，同样达到目标所需的指令数差 33%–92%。
   这直接支持我们**放弃固定模板、按"站点价值"分配预算**的方向：把垃圾/谓词预算集中在
   **控制流汇聚点、循环头、函数入口**，而不是均匀撒。
2. **单一类型（只有 NOP）不够**：论文自己说 `Tracur` 等族因为样本本来含 NOP 而失败。
   对应到 Lua：我们 `junkInstruction` 目前只从 `LOADNIL / MOVE / LOADBOOL|LOADFALSE / NOT|LOADTRUE /
   LOADK` 里随机 —— 形态偏少，容易被"识别为垃圾"。
3. **对"ML 检测器"的启发**：如果未来把"抗 ML 指纹检测"纳入威胁模型，这篇提供了
   **用 RL 优化混淆参数**这一范式；但注意阈值：论文里 50 次插入就能打掉 42 个百分点准确率，
   说明**ML 检测器对死代码极其脆弱**，我们不应把它作为主要强度指标。

---

## 22 LOKI / *Hardening Code Obfuscation Against Automated Attacks*（arXiv 2106.08913）

**引用/链接**：M. Schloegel, T. Blazytko, M. Contag, C. Aschermann, J. Basler, T. Holz, A. Abbasi.
*Hardening Code Obfuscation Against Automated Attacks.*
PDF（我读的）：<https://arxiv.org/pdf/2106.08913> ／ <https://arxiv.org/abs/2106.08913>
代码：<https://github.com/RUB-SysSec/loki>

> 注：检索常把 arXiv:2106.08913 误标为 "MBA-Blast"；**它实际是 LOKI**。两篇我都按实际标题引用。

### 22.1 核心主张（原文）

> "To achieve such protection, we propose a generic algorithm to **synthesize formally verified,
> arbitrarily complex MBA expressions**. This is in strong contrast to state-of-the-art approaches that
> rely on a few handwritten rules, greatly limiting their effectiveness."

**关键对比数字（原文）**：

> "For example, given **7,000 VM handlers, TIGRESS — the state-of-the-art academic obfuscator — uses only
> 16 unique MBAs, while our design features ~ 5,500 unique MBAs**. As a result, our MBAs are highly
> unlikely to be simplified: In fact, current state-of-the-art MBA deobfuscation tools such as
> **MBA-Blast can only simplify 0.5% of LOKI's MBAs**."

> "we present a hardening technique capable of **impeding program synthesis, reducing its success rate to
> 19%**—for TIGRESS, it is 67%."

> "our design incurs significantly less overhead compared to commercial, state-of-the-art obfuscation
> schemes (up to 40 times)."

贡献清单（原文）：设计/实现/评估 LOKI（"resilient against all known automated deobfuscation attacks,
**even in white-box scenarios**"）；通用的、**形式化验证过的**任意复杂度 MBA 合成方法；
**首个针对 program synthesis 类攻击的加固**，并给出 program synthesis 的极限经验分析。

### 22.2 对本项目最重要的三条

1. **"多样性"本身是防御指标**：Tigress 的 16 条手写 MBA 被 MBA-Blast 全部拿下；
   LOKI 的 ~5,500 条各不相同，MBA-Blast 只拿下 0.5%。**我们的 `OpaquePredicate` 只有 10 个族，
   每族的构造在所有站点上完全相同 —— 这正是 Tigress 的失败模式。**
2. **"形式化验证"是工程必需**：LOKI 用形式化验证 + fuzzing 保证"复杂且非确定的混淆变换"的正确性。
   我们当前 `x²(x+1)² ≡ 0 (mod 4)` 这类手写恒等式**可以穷举验证**（对小的 `x` 采样 + 对整数
   边界的解析论证），但一旦引入 MBA 合成与随机化，就必须建立**自动验证回路**
   （例如：对每族谓词在 `Int64` 边界值 + 随机采样上验证恒真，并纳入 `gradlew test`）。
3. **白盒场景**：LOKI 的威胁模型假设攻击者**知道混淆算法的全部细节**。这正是我们应当采用的模型
   （本项目开源），所以"算法保密"不能算强度来源。

---

## 23 我们能采用什么（逐条映射到本仓库）

> 状态标记：**✅ 已实现** ／ **⚠️ 已实现但产物实测强度低于论文标准** ／ **🔜 下一步应采纳**（按建议优先级排序）／ **⛔ 不采纳**（附理由）。
> **评级基准 = §25 反制清单的产物实测（R6 维护，随红队 Round 周期更新）**：✅ 必须同时满足"实现了"与"实测达到所引论文的强度口径"；只满足前者的标 ⚠️ 并附实测数字。
> 文件路径相对 `src/main/kotlin/io/github/dingyi222666/luabytecode/`。

### 23.1 控制流平坦化（`obfuscate/LuaControlFlowFlattening.kt`）

| 项 | 论文依据 | 状态与动作 |
|----|---------|-----------|
| 状态变量 + 比较链派发 + 每块末尾写后继 | §3.1 László&Kiss；§4.1 OLLVM | ✅ 已实现（`emitBlock` 的 `emitEdge`、`dispatchCompare`） |
| `select(cond,t,f)` 收敛结构 | §4.1 OLLVM | ✅ 已实现（`conditional ->` 分支：比较保留、true 边经 `trueEdge`、false 边为 fall-through） |
| 循环终结指令整条保留、只改后继方向 | §3.2(1) László&Kiss；§2.3 现有文档 | ✅ 已实现（`loop ->` 分支按 `jumpTarget`/fall-through 分流） |
| **case id 不是编译期常量** | §3.3 László&Kiss("obfuscate the values assigned to the control variable, in a way that they are not compile time constants anymore") | 🔜 **最高优先级**。当前 `LuaControlFlowFlattening.kt:134` 为 `IntArray(blockCount) { random.nextInt(1, Int.MAX_VALUE) }` 且 `internConstant` 成**整数常量** → 常量池里直接可见、且是 D810 点名的"高熵 32 位常量"指纹。应改为：在**函数入口用运行期算术**构造 state（keyed bijection：奇数乘 + 异或 + 加，模 2³¹），并把 id 表用 MBA 编码后逐条计算，而不是 `LOADK` 常量。 |
| dispatcher 加不透明谓词 / 去直筒化 | §19 Clyde("dispatch-table loops **with opaque predicates**")；§5.1 Collberg | 🔜 当前 dispatcher 是**纯 `EQK` 直筒比较链**、`dispatchJunk` 默认 `0`。应在每个比较前后插入收敛式不透明谓词（复用 `LuaJunkCode` 的 guard），并让 fallback 分支也参与不透明判定。 |
| 多 dispatcher / dispatcher 不连续 | §14.2 D810 自认难点（"Multiple dispatchers can be used"; "dispatcher blocks may have a side effect"） | 🔜 把比较链切成 2–4 段，段间夹真实代码或不可达环；或每原型生成 2 个独立 dispatcher，用 state 的高位选择 dispatcher。 |
| 嵌套派发（块内二次派发） | §8.1(a) IOLLVM nested switch | 🔜 在 `LuaControlFlowFlattening` 的块内部再做一层"块内小块派发"，直接提高 potency 且成本可控。 |
| 入度混淆（真实块与垃圾块入度趋同） | §8.1(b) IOLLVM in-degree obfuscation | 🔜 在 `LuaJunkCode` 里给**真实块入口**也套 guard，使真实块入度不再显著高于 `dead` 块。 |
| 基本块先切碎再平坦化 | §7 OLLVM `SplitBasicBlocks.cpp` | 🔜 低成本提高 case 数：在长直线块中插入 `JMP` 制造新块边界。**只算 potency，不算 resilience。** |
| 状态值经**内存/表**中转 | §14.3 D810 自认局限（"if the state variable is stored/read to/from memory … we won't be able to detect … because of aliasing"） | 🔜 用注入的临时表 (`NEWTABLE`/`SETTABLE`/`GETTABLE`) 做状态暂存，使 `MopTracker` 的反向数据流断链。注意寄存器/栈语义安全（现有 `reserveScratch` 机制可复用）。 |
| 状态值依赖被调用函数的返回值 | §15.3 Lebedev 约束 2/3 | ⚠️ **有条件采纳**：需保证不改变宿主行为、不引入 `_ENV` 依赖错误（见 §23.5 的 `resolveEnvUpvalues`）。可先用纯算术 + 表读取，暂不调用外部函数。 |

### 23.2 不透明谓词 / 不透明常量（`obfuscate/LuaControlFlowNoise.kt`）

| 项 | 论文依据 | 状态与动作 |
|----|---------|-----------|
| `x*(x+1) % 2 == 0`（PARITY） | §5.1 OLLVM；§1.4 同族的 `x²(x+1)² ≡ 0 (mod 4)` | ✅ 已实现（`appendParity`） |
| `7y² − 1 ≠ x²`（NON_SQUARE） | §1.4(b) Collberg POPL'98 | ✅ 已实现（`appendNonSquare`）。⚠️ **但 Collberg 本人指出它"can be cracked by a theorem prover"**，见 §25 |
| MBA 恒等式族（`MBA_ADD/MBA_XOR/MBA_DE_MORGAN/SHIFT/XOR_SELF`） | §10 GAMBA；§12 MBA-Blast；§13 goomba | ✅ 已实现。⛔ **不应作为强度来源**：goomba 明确"Simplifies linear MBAs, including opaque predicates"，MBA-Blast 在 10 000 条线性 MBA 上 100% 成功率、0.053 s。**⚠️ GATE-5 事故备注（§25.16 的本地实证）**：`appendMbaAdd` 曾被抄错——`BAND` 在 `BXOR` 覆写 `x` 之后才读寄存器、且整体加倍而非只倍 `x&y`，该"恒真式"对所有输入恒假（R6 独立数值枚举 0..200 全假 [已验证]）；junk 守卫的两路汇聚把假谓词完全掩盖，直到 clone 的 good/trap 选择器不汇聚才显形为 P0 乱码。教训 = 手写模板**没有验证回路就是语义错误产物**（§22.2 第 2 条）。现已有数值自证单测（`LuaPredicateFamiliesNumericTest`，对发射序列做整数机全族求值，B1）；**随机化谓词模板（PRE-2）的开闸前置条件 +1：验证回路先行**。 |
| 每站点随机族（`MIXED`） | §22 LOKI（多样性 = 防御指标）；§2.2 现有文档（抗 ML 模板） | ⚠️ **降级（R6 实测）**：实现为 11 个固定模板的均匀随机挑选，站点数 ≫ 模板数但**每族构造在所有站点完全相同**（Round1 实测：谓词 11 模板/3200 站点；VM handler 30 body 模板/24 站点）——正是 §22.2 点名的 Tigress 失败模式，不满足 LOKI"站点数 ≫ 唯一式数量"口径。🔜 应把**族的数量与每族的参数**从固定模板扩到"每次运行随机生成"（见下条） |
| **参数化谓词生成 + 自动验证** | §22 LOKI（"~5,500 unique MBAs"，MBA-Blast 只解 0.5%） | 🔜 **应采纳**：把 `appendParity/appendNonSquare/appendShift` 从"固定表达式"改为**带随机参数的模板实例**（如 `a·x²(x+1)² ≡ 0 (mod 4m)`、`k·y² − 1 ≠ x²` 的系数族），并为每族写**抽样 + 边界验证**的单元测试（`gradlew test`）。目标是"站点数 ≫ 模板数"。 |
| 运行期派生种子（`OpaqueSeed.HOST_VERSION/HOST_RANDOM`） | §14.2 D810（"computed using constant obfuscation or opaque predicate ⇒ we need to compute it dynamically"）；§15.3 Lebedev 约束 | ✅ 已实现（`appendHostVersionSeed` / `appendHostRandomSeed`，并经 `resolveEnvUpvalues` 统一解析 `_ENV`）。 应在**默认预设**里启用（当前 `AGGRESSIVE` 注释仍保留"5.3 注入路径 miscompiles"的旧告警，需以测试结论更新）。 |
| **NP-完全问题基（k-clique 类）不透明常量** | §9 Tiella&Ceccato（仅摘要） | 🔜 **候选，但需先取得正文**。理由：它与 §10/§12 的代数化简**正交**（组合搜索 ≠ 代数重写）。在拿到原文前不要实现。 |
| 别名/并发类谓词 | §1.3 Collberg 5.1/5.2 | ⛔ **不采纳**（直接形式）：Lua 字节码层没有可静态分析的指针类型系统；起线程会改变宿主可观测行为。**但其思想可平移**：用"表别名"（同一个表被两个不同路径引用）与"运行期不可判定的表形状"替代指针别名。 |
| 死代码环（`unreachableBlocks` / `deadCycleLength`） | §15.3(5) Lebedev（"死代码会被一并反混淆"） | ✅ 已实现。⛔ **不得计入 resilience 宣称**，只算 cost/potency。 |

### 23.3 指令替换 / 垃圾指令（`obfuscate/LuaControlFlowNoise.kt` 的 `junkInstruction`）

| 项 | 论文依据 | 状态与动作 |
|----|---------|-----------|
| 按指令类型定向插入等价垃圾（而非统一 NOP） | §5.2 OLLVM `createAlteredBasicBlock`（按 Add/Sub/Mul/ICmp/FCmp 分类处理） | ⚠️ 部分：当前 `junkInstruction` 只有 5 种（`LOADNIL/MOVE/LOADBOOL|LOADFALSE/NOT|LOADTRUE/LOADK`），**不区分被改写指令的类型**。🔜 应仿 OLLVM：对二元算术/比较指令生成"结果等价但多步"的替换（Lua 层注意 `ADD` 可能是浮点 ⇒ 只能在**我们自己产生的整数**上用 MBA）。 |
| 垃圾指令"参与计算" | §17.2(b) Rolles（只做 junking ⇒ 原指令字面保留，正则匹配即可识别） | 🔜 关键升级：让垃圾指令的结果**被后续真实计算用到但抵消**（如 `a = a + j; a = a - j`），而不是"结果无 use"（OLLVM 的垃圾结果无 use，会被 DCE 删除）。 |
| 位置由"价值"而非均匀决定 | §21 NOP-RL（最优位置 vs 随机位置，所需指令数差 33%–92%） |  把预算集中在**循环头、控制流汇聚点、函数入口、dispatcher 前**，而不是每个跳转等概率。 |
| 垃圾指令形态多样化 | §21.3（论文自认"只有 NOP"导致部分族失败；未来工作列 `MOV Reg,Reg`、`ADD Reg,0`、`SUB Reg,0`、`SHL Reg,0`） | 🔜 扩充 Lua 等价物：`MOVE r,r`、`ADD r,r,K0`、`SUB r,r,K0`、`MUL r,r,K1`、`BAND r,r,K(-1)`、`SETTABLE/GETTABLE` 往返等。 |
| 每条垃圾指令都"永不抛错、永不改见" | §5.2 OLLVM（垃圾结果无 use）；§1.4(a)(b) 谓词恒真 | ✅ 已实现（只写 scratch 寄存器，且 scratch 在 `maxStackSize` 之上）。 |

### 23.4 字符串 / 常量 / 指令流加密（`obfuscate/LuaStringEncryptor.kt`、`obfuscate/LuaStringCipher.kt`）

| 项 | 论文依据 | 状态与动作 |
|----|---------|-----------|
| 常量不透明化（不透明常量） | §9 k-clique（摘要）；§19 Clyde "encoded constants" | ⚠️ **范围修正（R6 实测）**：LCG keystream + 真实 luac 预编译 decryptor 注入 + upvalue 串联为真，但**保护对象只有字符串常量**；数值常量不在保护范围——Round1 实测 aggressive 产物常量池 3600 个整型明文（其中高熵 >2²⁴ 541 个，是 state/probe/seed 的注入原料，本身构成 §25.6 的 D810 高熵指纹，另见 LEAK-2/LEAK-4 的池级统计指纹）。"常量不透明化 ✅"不得外推到数值常量。**⛔ CRYPTO-ROOT（R1 Round5 跨线裁定，R6 论文口径）：Pass K 的 kget decoder LCG 参数（m=144693 / inc=1052609947 / mod=2³¹ / shift=15）与每个 stub 的 blob/idx/seed 三操作数全部明文在常量池——key 随密文发行等于无 key（§6.2 scramble32 教训的推广，Kerckhoffs推论；§22 白盒模型下算法与参数全公开）。R1 实测 20 分钟从字节码重建 decoder 主体。修复方向：keystream 参数的派生必须依赖 ≥1 个值域 ≥2³² 的运行期不可折叠量（与 CFF-1 结论同构），否则本层只是编码不是加密。 |

**⛔ R1 Round14 重测定价（CRYPTO-ROOT 四层基线终版, /tmp/red-R1/RETEST-HANDBOOK.md）**：kget 静态重放器已**全对齐**（43 行，`/tmp/red-R1/cff1/kget_replay.py`）——keystream（m/inc/mod/shift、seed^idx 派生、>>15）逐位正确，dispatcher salt 样本解出 v=1480104 ∈ 合法 salt 值域（1..2³⁰），零搜索。四层成本基线：字符串 ~20 行 / **kget 43 行（全对齐）** / VM 132 行（lift4x） / secret/mask **0 行**（#_VERSION 单值塌缩，deflatten 内建）。博弈论验收：**整体定价 = 各层最小值**——mask 层的 0 行是全局下限，B6 第一期先修 mask 层（flatten 的 mask 无 CONST-3 陷阱：set/probe 两端同式即可，可自由使用真运行时不可折叠量；K 的 seed 被"加密器可预知"锁死需二期架构前提创新）的裁定由本表数据支持。
| 把"仅被 `LOADK` 引用"的字符串就地加密、共享常量留明文 | §19/§18 工程做法；`bytecode/analysis/LuaConstantReferences.kt` | ✅ 已实现。🔜 下一步按现有文档 §1.2 的 TODO：把 `SETFIELD/GETTABUP/ERRNNIL` 的 RK 立即操作数改写成寄存器寻址（`SETFIELD→SETTABLE` 等），从而**让共享常量也能加密** |
| 指令流加密 | §19 Clyde；§4 步骤 3 规划 | 🔜 归属未来的 VM 化 pass。**⛔ CRYPTO-ROOT 同源（R1 Round5 / VM-1 / VM-3，R6 论文口径）**：现 VM 程序密流的 LCG 参数与 seed 同样明文随产物发行（教科书参数 + `seed = code.size*2654435761` 派生）——与 Pass K kget、chunk secret 三处同根：**key 派生输入全部静态**。§17.2(c) Rolles 的"常量解码例程可被切片"与 §18.3 的单点收敛都适用；在 key 派生接入运行期不可折叠量之前，本行不得从 🔜 升 ✅。 |
| 闭包/表包裹整个 chunk | §18.1 Prometheus（`return(function(...) local L={...} ... end)(...)`） | 🔜 可选：能收敛解码入口、隐藏顶层名；但**也把解码入口集中成单点**，需权衡 |

### 23.5 VM 化保护（未来 `obfuscate/LuaVmProtection.kt`）

| 项 | 论文依据 | 状态与动作 |
|----|---------|-----------|
| opcode 编号随机重排 | §19 Clyde "shuffled opcode mapping"；§4 步骤 2 规划 | ⛔ **作为强度措施不采纳**：§20.1 明确 **opcode 只有 7 bit（128 值）**，§20.2 的 LuaGadget 变异测试可在 **90 秒/用例**内全量恢复语义。降级为**纯 cost/去模板**措施 |
| 每原型独立编号表（而非全局一张） | §20.2（90 秒的前提是"每个 opcode 语义黑盒可测"） | 🔜 仍值得做（迫使攻击者逐样本重复测试），但**不改变下界** |
| handler 去同构 / 变异（metamorphic） | §17.2(a)(b) Rolles（结构同构 + 只 junking ⇒ 正则匹配即可识别 handler） | 🔜 **VM 化的第一优先级红线**：每个 handler 用**不同的等价指令序列**实现，而不是同一模板换常量 |
| handler 物理顺序打乱 + 常量解码交织 | §17.2(c) Rolles（常量去混淆例程可被切片） | 🔜 常量解码必须跨多条指令、与状态耦合，使局部切片失效 |
| **多态语义 ISA**（同一 opcode 依上下文变义） | §20.4 我的推导（攻击点是"单条指令 = 单一可观测计算"） | 🔜 **这是唯一能真正削弱 interpreter semantics testing 的方向**：超指令合并 + 上下文相关的指令语义 |
| 假 handler / 垃圾 opcode | §4 步骤 2 规划 | ️ 只能提高 cost：假 handler 的行为空间若可枚举，会被 LuaGadget 排除 |
| 栈式 + 寄存器式**双 VM** | §19 "Dual VM Architectures" | 🔜 值得纳入：显著提高"一套脚本通吃"的成本 |
| 指令流加密（滚动密钥） | §19 "encoded constants"；§4 步骤 3 | 🔜 需要"运行时逐条解密"，注意 Lua 字节码层没有自修改代码 ⇒ 只能靠解释器在 VM 内部解密指令数组 |
| 用真实 luac 预编译原型注入解释器 | 现有 `LuaStringEncryptor` 已验证的机制 | ✅ 机制已具备，VM 化直接复用 |

### 23.6 验证与工程（必须同步）

| 项 | 论文依据 | 动作 |
|----|---------|------|
| **形式化/自动验证混淆变换的正确性** | §22 LOKI（"formally verified, arbitrarily complex MBA expressions"；"port modern testing techniques, i.e., formal verification and fuzzing"） | 🔜 为每族谓词与每次 MBA 生成建立**自动验证**：整数边界值 + 随机采样 + （可选）Z3 等价性检查，纳入 `gradlew test` |
| **白盒威胁模型** | §22 LOKI（"even in white-box scenarios"） | 🔜 评估强度时假设攻击者知道我们的全部算法与源码（本项目开源），"没公开的模板"不算强度 |
| 语义等价验证（真实 lua 5.3/5.4/5.5 执行 + 输出比对） | 现有文档 §5 | ✅ 已具备；CFF/谓词改动后必须回归（尤其 `FORLOOP` 方向性、`JMP A` 的 upvalue 语义、`LOADKX/EXTRAARG` 配对） |
| 反编译对抗度度量 | §16 CaDeCFF（BinDiff 相似度）；§8.2 IOLLVM（BB/JI/F 相似度） | 🔜 引入**可复现的定量指标**（跳转数、块数、dispatcher 规模、unluac 输出差异），避免"看起来更乱"式自证 |
| 代价度量按嵌套层 | §2.1 taxonomy（cost is context-sensitive） | 🔜 报告里区分"循环内/循环外"的注入量 |

---

## 24 混合策略：各层到底买到了什么，以及对手还剩什么

### 24.1 分层与收益（每层**单独**都会被绕过，故必须叠）

| 层 | 具体做法（本仓库） | 挡住谁 | 挡不住谁（论文依据） |
|----|------------------|--------|--------------------|
| **L0 字符串/常量加密** | `LuaStringEncryptor`（LCG keystream + 预编译 decryptor 注入） | 静态字符串检索、`strings`、常量池比对 | 运行时 hook 解码点（§18.3 的单点收敛问题）、动态 trace |
| **L1 跳转间接化** | `LuaJumpIndirection`（跳板链） | 线性反编译、跳转距离分析 | 任何 CFG 重建（D810/CaDeCFF/Lebedev 都不看布局）；**纯 cost** |
| **L2 花指令 + 不透明谓词** | `LuaJunkCode`（收敛式 guard + 无限环）；**Round8 后新增：谓词全族数值自证（Bar B，§22 验证回路落地）+ 抵消对参与 seed 计算 + VARARG 源端整型子池（§25.20 整型保证）** | 朴素反编译器、ML 模板检测、DCE 不彻底的场景；**正确性已由构造保证**（恒真/恒假声明有边界+采样证明，GATE-5 类抄错必被抓） | goomba（"simplifies linear MBAs, including opaque predicates"）、GAMBA/SiMBA（线性 MBA 全解）、定理证明器（NON_SQUARE，§1.5）、DCE（无 use 的垃圾）——**resilience 侧不变**：11 固定模板/3200 站点（R6 实测）未变，线性族仍被带证明化简。PRE-2 开闸（Bar B 闭合后）是本层第一条通向非零 resilience 的工程路径。 |
| **L3 控制流平坦化** | `LuaControlFlowFlattening` + `LuaMinimalFlattening`（微平坦化后：306 原型 / 735 dispatcher 形状，COV-2 主分量收敛） | 结构阅读、AST 级还原、逐字可读函数体（unluac 干净函数体已实质消灭） | D810 `GenericDispatcherUnflatteningRule`（§14.2）、CaDeCFF（§16.2，不依赖 CFG 结构）、Lebedev（§15.2）——**CFF-1 值集传播武器未失效**：K-stub 化只使 R1 deflatten 武器 states 恢复率下降（漏报方向），适配预算 +80~100 行在其 300 行预算内。**`_VERSION` 面状态（R6 Round6 修订）**：bank_54 样本上 dispatcher 不读 `_VERSION`（R5 anchored 口径 env-derived=0）属实，但**不成立为普遍结论**——big_module_53 同构建产物实测仍有 1530 处 C 键指向 `_VERSION` 的 GETTABUP（守卫 HOST_VERSION seed 面），且存在 anchor 窗口内完整 `_VERSION` seed 链样本而 audit 报 env-derived=0（口径矛盾待 GATE-6 夹具仲裁）；secret 派生链仍受 CRYPTO-ROOT 静态性制约。 |
| **L4 VM 化** | 规划中（§23.5） | 直接语义阅读；把「反编译」变成「重建 ISA」 | Rolles 的 handler 指纹/切片/符号执行（§17.2）；LuaHunt 的 interpreter semantics testing（§20.2，90 秒/用例） |

### 24.2 各层的"收益函数"不同，不能互相替代

* **L0** 提高的是 **data 的静态可读性成本**（potency），几乎不提高 resilience。
* **L1/L2** 提高的是 **CFG 复杂度（potency）与噪声（stealth）**，其 resilience 上限被
  §1.5 与 §13.1 的**作者自述**钉死：标准谓词可被代数化简/定理证明器消掉。
* **L3** 提高的是 **执行顺序的推断成本**，但三家反平坦化（D810 / CaDeCFF / Lebedev）已证明
  经典形态可被**逐块确定性还原**。它的真实价值是**迫使攻击者投入工具**（把攻击从"读字节码"抬到
  "写 IR 仿真"），而不是"阻止还原"。
* **L4** 是唯一把攻击目标从"恢复控制流"变成"**恢复指令语义（ISA 逆向）**"的一层。
  但 §20 证明：**如果 VM 的每条指令都是"一个可观测计算"，128 值的 opcode 空间可被黑盒穷举**。
  因此 L4 的强度取决于 §23.5 里"超指令 / 多态语义 / handler 变异"的实现程度，而不取决于"是否 VM 化"。

### 24.3 明确声明：**没有任何单层是充分的**

按现成工具的能力边界，逐层列出"一个装备齐全的攻击者仍然能恢复什么"：

1. 用 **goomba**（IDA 内置，§13.1）→ 我们**当前全部**不透明谓词与线性 MBA 被带证明地化简。
   ⇒ **L2 单独 = 0 强度**。
2. 用 **D810**（§14）→ 我们**当前**的 CFF（常量池里的 31 位常量 state + 直筒 `EQK` 链）
   被 `GenericDispatcherUnflatteningRule` 逐块替换还原。⇒ **L3 单独 ≈ 0 强度**。
   即使按 §23.1 升级（运行期派生 state + 不透明谓词 + 多 dispatcher + 表中转），
   D810 自己给出的路线（微码仿真 + 反向追踪 + 块复制）只是**变慢**，并非失效。
3. 用 **CaDeCFF**（§16）→ 因为它的立论就是"不依赖 CFG 结构特征"，我们的
   "重排块布局 / 嵌套 switch / 入度混淆"**对它无效**；它用数据流 + 符号执行重建 super block。
4. 用 **Lebedev 的符号执行**（§15）→ 只要满足其 5 条约束（尤其"跳转只依赖控制寄存器"、
   "分派器连续"），我们被确定性还原。**主动违反这两条是有效的**（§23.1 的表/多 dispatcher 项）。
5. 用 **LuaHunt**（§20）→ 我们的 VM（若仍按 §4 原规划做 opcode 重排）
   **在 90 秒/用例量级被恢复语义**，且它"makes no assumptions about the interpreter's structure"。
   ⇒ **L4 的默认实现 = 接近 0 强度**。
6. 用 **GAMBA / MBA-Blast**（§10/§12）→ 若我们为了"抗静态分析"引入 `5 + (x|3) − (5&y)` 这类
   **mixed MBA**，GAMBA 的 substitution + factorization **专门**处理它（§10.3）；
   线性 MBA 则被 SiMBA/MBA-Blast **保证**求解。
7. 用 **program synthesis（Syntia/QSynth/msynth 一类）** → 即使 MBA 无法代数化简，
   §22 证明综合攻击仍能恢复部分语义（LOKI 加固后仍有 19% 成功率，Tigress 是 67%）。
   ⇒ **不存在"无法被自动化还原"的混淆**，只有"成本足够高的混淆"。

**结论（必须写进任何强度宣称里）**：本项目的目标应表述为
「把**自动化**还原的成本从"分钟级读字节码"抬高到"需要定制工具 + 人日级投入"，
并让**同一套工具无法通吃所有样本**」，
而不是"防止反编译"。任何声称"无法被逆"的表述都与 §17、§20、§22 的原文结论冲突。

### 24.4 推荐的叠加顺序与预算（基于上文）

```text
1) 先做"让每层不可互相消解"的工作（这比再加一层更重要）：
   * state 运行期派生 + MBA 编码（否则 D810 直接吃）
   * 不透明谓词参数化 + 每站点唯一（否则 goomba/MBA-Blast 直接吃）
   * handler 变异（否则 Rolles 正则 + LuaGadget 直接吃）
2) 再按 cost 从低到高叠加：
   strings -> jump indirection -> junk/predicates -> CFF -> VM
3) 预算分配（依据 §21 的"位置 > 数量"与 §2.1 的"cost 按嵌套层"）：
   把高代价手法放在冷路径（函数入口、错误分支、初始化），
   在热循环内只放廉价且必须的手法（1 条间接跳转级别），
   并用"站点价值"而非均匀概率决定注入点。
```

---

## 25 反制清单（"不要这样做"）

每条都标明**由哪篇文献的哪句话/哪个工具直接证伪**。

1. **不要用可被常量折叠的谓词**：`nil == nil`、`1 == 1`、`true`。
   依据 §5.4（`FCMP_TRUE` 折叠使整个 bogus 分支消失）。我们已有 `SIMPLE_NIL`，**只能作为
   最廉价档位存在，不能出现在默认/高强度预设里**。
2. **不要用单一模板谓词**：所有站点都用 `x*(x+1) % 2 == 0`。
   依据 §5.1 OLLVM 自己的注释（*"this could be improved, as the global values give a hint"*）
   + §22 LOKI（Tigress 的 16 条手写 MBA 被 MBA-Blast 全解；LOKI 的 ~5,500 条只被解 0.5%）。
   ⇒ **模板数必须远小于站点数**。
3. **不要依赖"可被定理证明器打掉"的数论恒等式作为主要强度**：`7y² − 1 ≠ x²`。
   依据 §1.5 原文：*"the predicate (7y² − 1 = x²) can be cracked by a theorem prover"*。
   ⇒ 保留为**噪声**可以，但不得作为"resilient"宣称。
4. **不要把线性 MBA 恒等式当作强度**：`x + y = (x ^ y) + 2*(x & y)`、
   `(a|b) − (a&b) = a ~ b`、`~X = −X − 1` 族、De Morgan 族。
   依据 §13.1 goomba *"Simplifies linear MBAs, including opaque predicates"*；
   §12.3 MBA-Blast 在 10 000 条线性 MBA 上 100% / 0.053 s；
   §10.2 SiMBA *"all linear MBAs can be solved in a straightforward way"*。
5. **不要在 bitwise 运算里放"非 0/−1 的常量"**（如 `5 + (x|3) − (5&y)`）。
   依据 §10.1 GAMBA 把这类**显式命名为 mixed MBA**，并在 §10.3 用 substitution + factorization
   **专门**处理；MBA-Blast 的 Table 2 双向替换同样覆盖。
6. **不要用"高熵 32 位常量"作为状态值**。
   依据 §14.1 D810 原文：*"the state variable is a 32 bit constant with high entropy (this was used
   as an heuristic by Rolf Rolles to identify flattened functions)"*。
   **我们当前实现恰好是这一形态**（`random.nextInt(1, Int.MAX_VALUE)` + `internConstant`）。
7. **不要用"常量池里可见的 case id"**：id 出现在产物中 ⇒ 攻击者不需要 key。
   依据 §6.2（`scramble32` 的 key 对攻击者不必要，因为 id 本身就是产物的一部分）
   + §14.2（D810 直接仿真 dispatcher 求后继，不需要知道 id 生成方式）。
8. **不要用直筒式（straight-line）单 dispatcher 而没有副作用/多实例**。
   依据 §14.2 三步策略 + 其自认的两个难点（"computed using constant obfuscation or opaque
   predicate"、"multiple dispatchers … dispatcher blocks may have a side effect"）
   + §15.3 Lebedev 约束 3/4。
9. **不要只在"结构特征"上做伪装**：重排块布局、让 Pre-dispatcher 不是入度最大的块、
   把 dispatcher 藏在函数中部。
   依据 §16.1 CaDeCFF 原文：*"They will fail if the CFG features change"* ——
   即 CaDeCFF **本来就**不用这些特征，所以针对这些特征加固是无效功。
10. **不要用"死代码环 / 不可达块"作为强度指标**。
    依据 §15.3(5) Lebedev 原文：*"if there is dead code … it will be deobfuscated like ordinary code"*；
    它们只提高 cost 与 potency。
11. **不要只插 NOP / 结果无 use 的垃圾指令**。
    依据 §5.2 OLLVM（垃圾结果无 use → 被 DCE 删除）+ §17.2(b) Rolles
    （只做 junking ⇒ *"every instruction which was present in the non-obfuscated handler is present in
    its literal form"* ⇒ 正则匹配即可识别 handler）。
12. **VM 化里不要让 handler 结构同构**（同一模板 + 只换常量）。
    依据 §17.2(a) Rolles：*"the implementation of each VM opcode dispatcher was identical … the
    dispatchers can simply be fingerprinted"*。
13. **不要以为随机化 opcode 编号能保护 VM**。
    依据 §20.1/§20.2：opcode 只有 **7 bit（128 值）**，LuaGadget 变异 + 可观测计算可在
    **90 秒/用例**恢复语义，且"makes no assumptions about the interpreter's structure"。
    ⇒ 编号随机化只能是 cost。
14. **不要把 VM 指令设计成"单条指令 = 单一可观测计算"**。
    依据 §20.2 的 LuaGadget 设计原理（*"Each LuaGadget contains a carefully chosen opcode sequence to
    fulfill an observable calculation—it is designed to test one or two particular opcodes at a time"*）。
    ⇒ 必须做**超指令合并 / 上下文相关语义**。
15. **不要指望"算法保密"或"模板未公开"**。
    依据 §22 LOKI 的白盒威胁模型（*"even in white-box scenarios"*）——本项目开源，
    所有模板都在源码里。
16. **不要用没有形式化/自动验证的随机化 MBA 合成**。
    依据 §22 LOKI（*"formally verified, arbitrarily complex MBA expressions"* +
    *"port modern testing techniques, i.e., formal verification and fuzzing … to assert the correctness
    of complex and non-deterministic obfuscation transformations"*）。
    ⇒ 没有验证回路的随机化会产生**语义错误的混淆产物**（我们已有的 `OpaqueSeed.HOST_*` 误编译
    就是这类事故的先例，见 `docs/obfuscation-algorithms.md` §7.7）。
17. **不要在热循环体内放高 cost 手法**。
    依据 §2.1 taxonomy 原文：*"A simple assignment `a=5' inserted at the topmost level … will only
    incur a constant overhead. The same statement inserted inside an inner loop will have a substantially
    higher cost."*
18. **不要把"插入了多少条指令"当成效力指标**。
    依据 §21 RL 实验：随机位置 vs 最优位置所需指令数差 33%–92% —— **位置**决定效果；
    而 §7 的切碎与 §5 的花指令都只增数量不增 resilience。
19. **不要宣称"防反编译"**。
    依据 §14.4（D810 自认的失败面是"内存别名 / 需要符号执行"，而非"不可能"）、
    §15.4、§16.2、§17.2(d)（Rolles 提出符号执行+定理证明可绕过包括变异在内的混淆）、
    §22（加固后 program synthesis 仍有 19% 成功率）。**只能宣称"抬高成本"**。
20. **不要在浮点可能出现的路径上用 MBA**（Lua 特有，非论文结论）。
    依据现有文档 §2.4：`x + y = (x ^ y) + 2*(x & y)` 等只对整数成立，
    Lua 的 `ADD` 可能是浮点 ⇒ MBA 只能作用于我们自己构造/确认的整数状态与谓词。
    这条与 OLLVM 的 §5.2"按类型分别处理 int/float 二元运算"是同一原则。

---

## 参考文献

以下为我**实际抓取并读取**的来源（含获取方式与状态）。

### 论文

1. C. Collberg, C. Thomborson, D. Low. *Manufacturing Cheap, Resilient, and Stealthy Opaque
   Constructs.* POPL '98. DOI <https://dl.acm.org/doi/10.1145/268946.268962> ·
   正文（Wayback，PDF，OCR 读取）<https://web.archive.org/web/2023id_/https://www2.cs.arizona.edu/~collberg/content/research/papers/collberg98manufacturing-clean.pdf>
2. C. Collberg, C. Thomborson, D. Low. *A Taxonomy of Obfuscating Transformations.* TR#148,
   University of Auckland, 1997. 记录 <https://researchspace.auckland.ac.nz/handle/2292/3491> ·
   正文（Wayback，PDF，OCR 读取）<https://web.archive.org/web/2020id_/http://www.dsi.unive.it/~avp/collberg97taxonomy.pdf>
3. T. László, Á. Kiss. *Obfuscating C++ Programs via Control Flow Flattening.* Annales Univ. Sci.
   Budapestinensis, Sect. Computatorica 30 (2009) 3–19. 正文（Wayback，PDF）
   <https://web.archive.org/web/2020id_/http://ac.inf.elte.hu/Vol_030_2009/003.pdf>
4. *IOLLVM: Enhanced version of OLLVM.* arXiv:2203.03169.
   <https://arxiv.org/abs/2203.03169> · <https://arxiv.org/pdf/2203.03169>
5. R. Tiella, M. Ceccato. *Automatic generation of opaque constants based on the k-clique problem for
   resilient data obfuscation.* SANER 2017. DOI <https://doi.org/10.1109/SANER.2017.7884620> ·
   IEEE CSDL <https://www.computer.org/csdl/proceedings-article/saner/2017/07884620/12OmNqyUUz7> ·
   **FBK CRIS 摘要（全文受限，仅读到摘要）** <https://cris.fbk.eu/handle/11582/309493>
6. B. Reichenwallner, P. Meerwald-Stadler. *Simplification of General Mixed Boolean-Arithmetic
   Expressions: GAMBA.* WoRMA'23. <https://arxiv.org/abs/2305.06763> ·
   全文 HTML <https://ar5iv.labs.arxiv.org/html/2305.06763>
7. *On Simplifying Mixed Boolean-Arithmetic Expressions.* Automatic Control and Computer Sciences.
   <https://link.springer.com/article/10.3103/S0146411624700299> ·
   <https://dl.acm.org/doi/10.3103/S0146411624700299> —— **未能获取原文，仅据检索摘要，待核**
8. B. Liu, J. Shen, J. Ming, Q. Zheng, J. Li, D. Xu. *MBA-Blast: Unveiling and Simplifying Mixed
   Boolean-Arithmetic Obfuscation.* USENIX Security '21.
   <https://www.usenix.org/conference/usenixsecurity21/presentation/liu-binbin> ·
   全文 PDF <https://www.usenix.org/system/files/sec21-liu-binbin.pdf> ·
   代码 <https://github.com/softsec-unh/MBA-Blast>
9. M. Schloegel, T. Blazytko, M. Contag, C. Aschermann, J. Basler, T. Holz, A. Abbasi.
   *Hardening Code Obfuscation Against Automated Attacks (LOKI).* arXiv:2106.08913.
   <https://arxiv.org/abs/2106.08913> · <https://arxiv.org/pdf/2106.08913> ·
   代码 <https://github.com/RUB-SysSec/loki>
10. W. Dong, J. Lin, R. Chang, R. Wang. *CaDeCFF: Compiler-Agnostic Deobfuscator of Control Flow
    Flattening.* Internetware '22. <https://dl.acm.org/doi/10.1145/3545258.3545269> ·
    全文 HTML（经 `r.jina.ai` 代理读取）
    <https://r.jina.ai/https://dl.acm.org/doi/fullHtml/10.1145/3545258.3545269>
11. В. В. Лебедев. *Деобфускация Control Flow Flattening средствами символьного исполнения.*
    Прикладная дискретная математика. Приложение, 2021, №14, 134–138.
    <https://www.mathnet.ru/eng/pdma548> · DOI <https://doi.org/10.17223/2226308X/14/29> ·
    全文 PDF <https://www.mathnet.ru/php/getFT.phtml?jrnid=pdma&paperid=548&what=fullt&option_lang=eng>
12. R. Rolles. *Unpacking Virtualization Obfuscators.* USENIX WOOT '09.
    <https://www.usenix.org/legacy/events/woot09/tech/full_papers/rolles.pdf>
13. C. Luo, J. Ming, J. Fu, G. Peng, Z. Li. *Reverse Engineering of Obfuscated Lua Bytecode via
    Interpreter Semantics Testing.* NSF PAR 开放存取副本
    <https://par.nsf.gov/servlets/purl/10540556>
14. *Enhancing the Insertion of NOP Instructions to Obfuscate Malware via Deep Reinforcement
    Learning.* Journal of Computers & Security. <https://arxiv.org/abs/2111.09626> ·
    全文 HTML <https://ar5iv.labs.arxiv.org/html/2111.09626>

> 注：任务清单中的 NDSS 论文 *Prevalence of Lua Bytecode and Lua Bytecode Obfuscation*
> **未能获取**（`dev.ndss-symposium.org` 域名不可解析；`www.ndss-symposium.org` 同编号文件实为
> FirmCross），详见 §0.2 第 20 条；其位置由上面第 13 条真实可读的 Lua 字节码论文替代。

### 源码 / 实现

15. OLLVM `Flattening.cpp`（branch `llvm-4.0`）
    <https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/Flattening.cpp>
16. OLLVM `BogusControlFlow.cpp`
    <https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/BogusControlFlow.cpp>
17. OLLVM `CryptoUtils.cpp`
    <https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/CryptoUtils.cpp>
18. OLLVM `SplitBasicBlocks.cpp`
    <https://github.com/obfuscator-llvm/obfuscator/blob/llvm-4.0/lib/Transforms/Obfuscation/SplitBasicBlocks.cpp>
19. goomba / gooMBA（Hex-Rays IDA 插件）<https://github.com/HexRaysSA/goomba> ·
    预计算 oracle <https://hex-rays.com/products/ida/support/freefiles/goomba-oracle.7z>
20. D810: *A journey into control flow unflattening*（eShard）
    <https://www.eshard.com/blog/d810-a-journey-into-control-flow-unflattening>
21. Prometheus（Lua 混淆器）<https://github.com/prometheus-lua/Prometheus> ·
    README <https://raw.githubusercontent.com/prometheus-lua/Prometheus/main/README.md>
22. Clyde Protection（Luau VM 化保护）
    <https://github.com/sfr-development/Lua-Obfuscator-Clyde-Protection> ·
    README <https://raw.githubusercontent.com/sfr-development/Lua-Obfuscator-Clyde-Protection/main/README.md>

### 补充链接（检索所得、未作为正文依据）

* NDSS 2026 接收论文列表 <https://www.ndss-symposium.org/ndss2026/accepted-papers/>
  （用于确认"Prevalence of Lua Bytecode…"不在可下载列表中）
* unluac（反编译验证）<https://sourceforge.net/projects/unluac/>
* `docs/obfuscation-algorithms.md`（本仓库既有设计文档，已保持一致并对其 §4/§18 的描述做了修正说明）