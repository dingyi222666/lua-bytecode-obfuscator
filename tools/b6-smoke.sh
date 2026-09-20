#!/bin/bash
# b6-smoke.sh — 一行命令端到端冒烟 (KDEC-1 / K-DEC / 产物可运行性)
# 用法: bash b6-smoke.sh [TOOL_PATH]
# 通过 = 退出码 0 且末行 SMOKE PASS; 任一样例失败 -> 退出码 1, SMOKE FAIL
#
# SMOKE-1 (R5 Round4, B5 修复): 旧版在解释器/luac 全缺失时所有形状 skip、fail=0
# 照样 exit 0 "PASS" —— 空跑假绿。修复:
#   * 实际执行 case 数 < MIN_CASES (默认 6) 即 FAIL, 不看 fail 计数
#   * skip 全部计数并显式汇总 (环境残缺可见)
#   * 通道比对从 head -1 改为全输出比对 (归一化空白): "首行对、后续错"的形态
#     不再漏 (例: 前几行对、中间静默丢行)
TOOL="${1:-/Users/dingyi/projects/kotlin_projects/lua-bytecode-obfuscator/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}"
D=$(mktemp -d /tmp/b6smoke.XXXXXX)
trap 'rm -rf "$D"' EXIT
fail=0
ran=0
skipped=0
MIN_CASES=${SMOKE_MIN_CASES:-6}
norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g; s/[ \t]+/ /g; s/^ //; s/ $//'; }

declare -a SRC=(
  'local function gcd(a, b) while b ~= 0 do a, b = b, a % b end return a end print(gcd(36, 24))'
  'print(math.maxinteger, math.maxinteger+1)'
  'local t = setmetatable({}, {__add = function() return 42 end}) print(t + t)'
  'local i, j = 0, 10 while i < j do i = i + 1 j = j - 1 end print(i, j)'
  'local fns = {} for i = 1, 3 do fns[i] = function() return i end end print(fns[1](), fns[2](), fns[3]())'
  'local a = 7 print(a)'
  'local a = 7 local b = 3 print(a*2+b, a-b, b*b+a, (a+b)*2-1)'
)
declare -a TAG=(gcd biginteger metamethod whilecmp closure silentexpr k53)
declare -a WANT=(12 '9223372036854775807 -9223372036854775808' 42 '5 5' '1 2 3' 7 '17 4 16 19')
# silentexpr/k53 = REG-53-AGGR 验收形状 (公告 #11.3): 静默空输出 (rc=0 无输出) 是最阴险失败,
# 单看报错文案会漏; whilecmp = KDEC-1 静态类型错形状 (compare number with string)。

for v in 53 54 55; do
  case "$v" in
    53) L=/tmp/lua-5.3.6/src/lua; LUAC=/tmp/lua-5.3.6/src/luac ;;
    54) L=/tmp/lua-5.4.6/src/lua; LUAC=/tmp/lua-5.4.6/src/luac ;;
    55) L=/tmp/lua-5.5.1/src/lua; LUAC=/tmp/lua-5.5.1/src/luac ;;
  esac
  [ -x "$L" ] || { echo "SMOKE skip all cases for 5.$v (interpreter missing: $L)"; skipped=$((skipped+${#SRC[@]})); continue; }
  for idx in "${!SRC[@]}"; do
    tag="${TAG[$idx]}"; body="${SRC[$idx]}"; want=$(printf '%s' "${WANT[$idx]}")
    src="$D/${tag}_$v.lua"
    printf '%s\n' "$body" > "$src"
    if ! "$LUAC" -o "$src.luac" "$src" 2>/dev/null; then echo "SMOKE skip $tag $v (luac)"; skipped=$((skipped+1)); continue; fi
    ran=$((ran+1))
    # 通道 A: aggressive 全家桶 (K-DEC / KDEC-1 面向上)
    "$TOOL" "$src.luac" --obfuscate aggressive --dump "$src.aggr" >/dev/null 2>&1
    rc=$?
    if [ $rc -ne 0 ]; then echo "SMOKE FAIL $tag $v aggressive: obfuscator rc=$rc (K-DEC 面: 若报 decoder prototype 则是模板包装)"; fail=$((fail+1)); continue; fi
    "$L" "$src.aggr" > "$src.aggr.out" 2>&1 &
    pid=$!; ( sleep 15; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & wait $pid 2>/dev/null
    got=$(norm < "$src.aggr.out" | tr -s ' \t' ' ')
    # KDEC 归类: Pass K 运行期 blob/host 错误 (KDEC-1 同根: kget 报错/解出错误类型/静默空输出)
    if [ -z "$got" ]; then
      echo "SMOKE FAIL $tag $v aggressive: REG-53 面 (静默空输出 rc=0)"; fail=$((fail+1)); continue
    fi
    if printf '%s' "$got" | grep -q "corrupted constant blob\|compare number with string\|attempt to index"; then
      echo "SMOKE FAIL $tag $v aggressive: KDEC-1 面 (kget blob/host): [$got]"; fail=$((fail+1)); continue
    fi
    if [ "$got" != "$want" ]; then echo "SMOKE FAIL $tag $v aggressive: want [$want] got [$got] (全输出比对)"; fail=$((fail+1));
    else echo "SMOKE ok   $tag $v aggressive"; fi
    # 通道 B: --vm-out (VM-CMP-1 / VM-META-1 / VM-CRASH-1 面向上)
    "$TOOL" "$src.luac" --vm-out "$src.vm.lua" >/dev/null 2>&1
    rc=$?
    if [ $rc -eq 3 ]; then echo "SMOKE ok   $tag $v vm: refused(3)"; continue; fi
    if [ $rc -ne 0 ] || [ ! -f "$src.vm.lua" ]; then echo "SMOKE FAIL $tag $v vm: rc=$rc (崩溃=VM-CRASH 面)"; fail=$((fail+1)); continue; fi
    "$L" "$src.vm.lua" > "$src.vm.out" 2>&1 &
    pid=$!; ( sleep 20; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & wait $pid 2>/dev/null
    r2=$?
    if [ $r2 -ne 0 ]; then
      head -1 "$src.vm.out" | grep -q "integrity check" \
        && echo "SMOKE FAIL $tag $v vm: B2-WIP 面 (integrity check failed, 在飞中间态)" \
        || echo "SMOKE FAIL $tag $v vm: module rc=$r2 (metamethod/crash 面)"
      fail=$((fail+1)); continue; fi
    got=$(norm < "$src.vm.out" | tr -s ' \t' ' ')
    if [ "$got" != "$want" ]; then echo "SMOKE FAIL $tag $v vm: want [$want] got [$got] (全输出比对, 比较交换面)"; fail=$((fail+1));
    else echo "SMOKE ok   $tag $v vm"; fi
  done
done
# SMOKE-1: 空跑假绿防线 —— 实际执行数不足即 FAIL, 与 fail 计数无关
if [ "$ran" -lt "$MIN_CASES" ]; then
  echo "SMOKE FAIL: only $ran case(s) actually executed (< MIN_CASES=$MIN_CASES), skipped=$skipped -- 环境残缺, 不允许判 PASS"
  exit 1
fi
if [ $fail -gt 0 ]; then echo "SMOKE FAIL ($fail failures, ran=$ran)"; exit 1; fi
echo "SMOKE PASS (ran=$ran, skipped=$skipped)"
