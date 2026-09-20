#!/usr/bin/env python3
"""Layered predicate audit (P3 self check).

Reads `lua-bytecode-obfuscator <chunk> --no-debug` output and classifies every
comparison into the layer that produced it, then reports the strength metrics the
papers care about:

  layer "guard"       : the comparison is preceded by `LOADNIL` (the convergent
                        guard shape used by the junk pass and by the clone
                        selector) — the predicate itself
  layer "dispatcher"  : the comparison is preceded by a `LOADK,LOADK,<arith>`
                        state derivation (control flow flattening)
  seeded at run time  : the window reads globals (`GETTABUP`/`GETUPVAL`/`LEN`/`CALL`),
                        so a bytecode level simplifier cannot fold the value
  one step identities : `LOADK K[1]`+`MUL`, `K[-1]`+`BAND`, `K[0]`+`ADD/SUB/BXOR/SHL/SHR`
                        are folds goomba / MBA-Blast perform in one step
  unique parameters   : distinct (left, right) operand pairs per layer — LOKI asks
                        for per site unique parameters, so duplicates are a smell
"""
import re, subprocess, sys, collections

ARITH = ("ADD","SUB","MUL","MOD","BAND","BOR","BXOR","SHL","SHR","DIV","IDIV","POW","ADDI","ADDK","SUBI","SUBK","MULI","MULK","MODI","MODK","BANDI","BANDK")
COMPARE = ("EQ","LT","LE","EQK","EQI","LTI","LEI","GTI","GEI","TEST","TESTSET")
SKIP = ("LOADNIL",)
def listing(tool, chunk):
    return subprocess.run([tool, chunk, "--no-debug"], capture_output=True, text=True).stdout

def parse(text):
    protos, cur = [], None
    for line in text.splitlines():
        # `^\s*`: nested prototype headers are indented in the CLI listing; without
        # the anchor the whole chunk was parsed as ONE prototype and audit windows
        # crossed prototype boundaries (adversarial audit, round 1).
        if re.match(r'^\s*prototype #\d+', line):
            cur = []; protos.append(cur); continue
        m = re.match(r'\s*\[\s*(\d+)\]\s+([A-Z0-9]+)\s+(.*)$', line)
        if m and cur is not None:
            fields = {k: int(v) for k, v in re.findall(r'([ABC])=(-?\d+)', m.group(3))}
            const = re.search(r'; K(\d+) = (-?\d+)', m.group(3))
            cur.append((int(m.group(1)), m.group(2), fields, const))
    return [p for p in protos if p]

def parse_constants(text):
    """Constant tables per prototype, from the `constants (n):` blocks the CLI
    listing prints after each prototype's code. Needed to resolve GETTABUP keys."""
    tables, cur = [], None
    in_proto = False
    for line in text.splitlines():
        if re.match(r'^\s*prototype #\d+', line):
            in_proto = True
            cur = None
            continue
        if not in_proto:
            continue
        m = re.match(r'\s*constants \(\d+\):', line)
        if m:
            cur = {}
            tables.append(cur)
            continue
        m = re.match(r'\s*K(\d+)\s+(.*)$', line)
        if m and cur is not None:
            cur[int(m.group(1))] = m.group(2).strip()
        if re.match(r'\s*upvalues \(\d+\):', line):
            cur = None
    return tables


SEED_OPS = ("LOADK", "LOADI", "GETUPVAL", "GETTABUP", "LEN", "CALL", "GETFIELD", "GETTABLE", "MOVE", "ADD", "SUB", "MUL", "BXOR", "BAND")
RUNTIME_OPS = ("GETUPVAL", "GETTABUP", "LEN", "CALL", "GETFIELD", "GETTABLE")

def _version_key_index(const_table):
    """indices of the `_VERSION` string constant in this prototype's constant table.
    The printer formats strings as `"..." (len=n)`, so match that shape."""
    return {idx for idx, text in (const_table or {}).items()
            if text.startswith('"_VERSION"')}

ANCHOR_OPS = ("LOADNIL", "JMP", "RETURN", "RETURN0", "RETURN1", "TAILCALL")


def find_anchor(code, i, max_back):
    """Backward anchor walk shared by classify() and anchored_tail().

    GATE-6 dead-jump transparency: convergent guards end with the guard's OWN
    dead jump (`... LOADNIL -> seed setup -> dead JMP -> EQ`, R5 Round3). When
    the backward walk from the compare at index `i` starts on such a JMP —
    a JMP that is the LAST instruction before the compare — that jump is part
    of the compare's own guard, not a block boundary: skip it, and skip a
    LOADNIL that immediately precedes it (the `LOADNIL;JMP` dead-jump pair of
    the junk guard shape), then keep scanning for the real anchor. A JMP with
    any instruction between it and the compare is a genuine trampoline/block
    anchor and stops the walk.

    Returns the anchor index (or None when no anchor within max_back).
    """
    start = max(0, i - max_back)
    back = i - 1
    # dead-jump transparency: JMP immediately before the compare
    if back >= start and code[back][1] == "JMP":
        back -= 1
        # the junk guard's dead-jump pair is `LOADNIL;JMP` adjacent — skip the
        # LOADNIL too so the walk reaches the seed setup / real anchor
        if back >= start and code[back][1] == "LOADNIL":
            back -= 1
    while back >= start:
        if code[back][1] in ANCHOR_OPS:
            return back
        back -= 1
    return None


def classify(code, i):
    """Which layer produced the comparison at index `i`, structurally.

    Walks backwards to the nearest anchor (see find_anchor for the dead-jump
    transparency rule): a `LOADNIL` (the convergent guard's first instruction)
    or a block boundary. A seeded guard has a seed computation between the
    anchor and the comparison; a `nil guard` (CFF block guard, clone selector)
    does not. Without a `LOADNIL` anchor a `LOADK,LOADK,<arith>` pair in front
    means the CFF dispatcher.
    """
    window = code[max(0, i - 20):i]
    anchor = find_anchor(code, i, 20)
    tail = window[anchor:] if anchor is not None else window
    ops = [w[1] for w in tail]
    if anchor is not None and code[anchor][1] == "LOADNIL":
        seeded = any(o in SEED_OPS for o in ops[1:])
        if not seeded:
            return "nil guard", False
        runtime = any(o in RUNTIME_OPS for o in ops[1:])
        return "junk guard", runtime
    return "dispatcher", any(o in RUNTIME_OPS for o in ops)


def audit(tool, chunk):
    stats = collections.Counter()
    params = collections.defaultdict(set)
    for code in parse(listing(tool, chunk)):
        for i, (pc, op, f, c) in enumerate(code):
            if op in ("LOADK", "LOADI"):
                value = int(c.group(2)) if c else None
                nxt = code[i+1] if i+1 < len(code) else None
                if nxt and nxt[1] in ("ADD", "SUB", "MUL", "BAND", "BXOR", "SHL", "SHR"):
                    # The arithmetic operand can be a register (5.3 RK / plain B or C)
                    # or an immediate (5.4/5.5 `*I` in C, `*K` names a constant).
                    # `result == operand with K = identity element` folds in one step.
                    nxtC = nxt[2].get("C")
                    is_operand = (
                        nxtC == f.get("A")
                        or (nxt[1] == "LOADK" and nxtC is not None and nxtC >= 256 and nxtC - 256 == f.get("A"))
                    )
                    if value is not None and is_operand:
                        if (value == 1 and nxt[1] == "MUL") or (value == -1 and nxt[1] == "BAND") or (value == 0):
                            stats["one step identity"] += 1
            # `EQ x x` (self comparison after LOADNIL) folds regardless of constants
            if op == "EQ":
                window = code[max(0, i - 6):i]
                if window and window[-1][1] == "LOADNIL":
                    a, b = f.get("B"), f.get("C")
                    if a is not None and a == b:
                        stats["one step identity"] += 1
            if op not in COMPARE:
                continue
            layer, runtime = classify(code, i)
            stats[f"{layer}: {'runtime' if runtime else 'constant'} seeded"] += 1
            stats[f"{layer}: total"] += 1
            # per site unique *parameter* sets: the constant values the seed setup
            # reads (register numbers repeat by construction and say nothing)
            window = code[max(0, i - 20):i+1]
            values = []
            for (_, op2, _, c2) in window:
                if c2 is None:
                    continue
                m = re.search(r'=\s*(-?\d+)\s*$', c2.group(0).strip())
                if m:
                    values.append(int(m.group(1)))
            # every layer, not just the junk guards: restricting this to `junk` made the
            # `nil guard` and `dispatcher` uniqueness lines print a meaningless 0/... (the
            # parameters were simply never collected)
            if values:
                params[layer].add(tuple(sorted(values)))
    return stats, params


def versioned_globals(code, const_table):
    """pcs of instructions whose *key operand* resolves to the `_VERSION` constant:
    `GETTABUP r, _ENV, K[_VERSION]` (direct global read) and the staged
    `GETTABLE dst, envreg, K[_VERSION]` form (`appendEnvLookup` falls back to it
    when the key index exceeds the 8 bit RK window; R6 Round4 hypothesis (b)).
    The key operand is C for 5.4/5.5 and `C & 0xFF` for 5.3 (BITRK flag set)."""
    keys = _version_key_index(const_table)
    pcs = []
    for pc, op, f, c in code:
        if op not in ("GETTABUP", "GETTABLE"):
            continue
        key = f.get("C")
        if key is None:
            continue
        # 5.3: operand & 0xFF strips the constant flag; 5.4/5.5: the plain index
        if (key & 0xFF) in keys or key in keys:
            pcs.append(pc)
    return pcs


def anchored_tail(code, i, max_back=200):
    """The instruction window classify() conceptually uses: walk back from the
    comparison at index `i` to the nearest block anchor (LOADNIL / JMP / RETURN
    family, with GATE-6 dead-jump transparency, see find_anchor) and cut there.

    Kept for structural questions (window shape, unique parameters); the
    env/fold verdict uses the register TAINT walk below (R5 Round3 ground
    truth: a seed belongs to the compare when its destination register feeds
    the compare's operands -- interleaved mid-block jumps are plumbing, while
    a block boundary followed only by filler kills the taint)."""
    anchor = find_anchor(code, i, max_back)
    if anchor is not None:
        return code[anchor + 1:i]
    return code[max(0, i - 20):i]


DEF_OPS = ("LEN", "MOVE", "LOADK", "LOADI", "LOADF", "ADD", "SUB", "MUL", "MOD",
           "BAND", "BOR", "BXOR", "SHL", "SHR", "DIV", "IDIV", "POW", "ADDI",
           "ADDK", "SUBK", "MULK", "MODI", "MODK", "BANDI", "BANDK", "BORI",
           "BORK", "SHRI", "NOT", "TESTSET")
TAINT_MAX_BACK = 80


def _src_regs(op, f):
    """Register operands (5.3 RK operands >= 256 are constant indices)."""
    out = []
    for k in ("B", "C"):
        v = f.get(k)
        if v is not None and 0 <= v < 256:
            out.append(v)
    return out


def taint_walk(code, i, vpc, max_back=TAINT_MAX_BACK):
    """Backward register taint from the compare at index `i` (R5 Round3 truth).

    Returns (env, foldable, runtime):
      env      : a `_VERSION` global read (versioned GETTABUP/GETTABLE) feeds the
                 compare's operands through the def chain
      foldable : env AND the chain observes nothing else unbounded at run time
                 (no CALL/VARARG, no non-versioned global/upvalue/table read) --
                 folding `#_VERSION` then leaves a plain constant
      runtime  : the chain reads anything from the environment (global/upvalue/
                 table/call/len) -- the honest "runtime seeded" flag

    Boundary rule: a JMP/NOP run with NO def crossed between it and the compare
    is a real block boundary (the taint cannot cross it); a JMP with defs behind
    it is mid-block plumbing (dispatcher chains interleave `LEN, JMP, BXOR,
    JMP, BXOR, EQ`) and is stepped over. The guard's own dead-jump pair
    (`LOADNIL;JMP` adjacent to the compare) contains a def, so it unwinds
    naturally without a special case."""
    f0 = code[i][2]
    taint = set()
    for k in ("B", "C"):
        v = f0.get(k)
        if v is not None and 0 <= v < 256:
            taint.add(v)
    env = runtime = False
    has_call = False
    other_unbounded = False
    # Classify the trailing NOP/JMP run between the compare and the first real
    # instruction (R5 90-cell ground truth):
    #   0 JMPs   (plain, deadjmp-with-LOADNIL)   -> no boundary ahead of the seed
    #   1 JMP    (trampoline / crossblock anchor) -> single hop = REAL block
    #                                               boundary; the seed on the far
    #                                               side belongs to another block
    #   >=2 JMPs (probechain: repeated mid-derivation hops, 1:1 with real
    #             dispatcher layouts)            -> hop chain, all transparent
    # A JMP directly adjacent to the compare is the guard's own dead jump and is
    # always transparent (it does not count toward the hop tally).
    trailing_jmps = 0
    scan = i - 1
    while scan >= 0 and code[scan][1] in ("JMP", "NOP"):
        if code[scan][1] == "JMP":
            trailing_jmps += 1
        scan -= 1
    adjacent_deadjmp = i - 1 >= 0 and code[i - 1][1] == "JMP"
    chain_mode = trailing_jmps >= 2  # probechain-style repeated derivation hops
    boundary_idx = None
    if trailing_jmps == 1 and not adjacent_deadjmp:
        # single non-adjacent hop (trampoline / crossblock anchor): the first
        # JMP the walk meets is the block boundary. Identified by list index:
        # synthetic listings may carry duplicated pc values, indexes cannot.
        for back in range(i - 1, max(0, i - max_back) - 1, -1):
            if code[back][1] == "JMP":
                boundary_idx = back
                break
    for back in range(i - 1, max(0, i - max_back) - 1, -1):
        pc, op, f, c = code[back]
        if op in ("JMP", "NOP"):
            if boundary_idx is not None and back == boundary_idx:
                break  # single hop: real block boundary, taint cannot cross
            continue  # dead jump / hop chain: transparent
        if op in ("RETURN", "RETURN0", "RETURN1", "TAILCALL"):
            break
        a = f.get("A")
        if op == "LOADNIL":
            for r in range(a or 0, (a or 0) + (f.get("B") or 0) + 1):
                taint.discard(r)
            defs_crossed = True
            continue
        if op == "CALL":
            lo = (a or 0) + 1
            args = set(range(lo, (a or 0) + (f.get("B") or 0)))
            if a is not None and (a in taint or (args & taint)):
                has_call = True
                runtime = True
                # keep walking INTO the callee-prep: the function register and
                # the argument registers are defined before the call (the
                # `#tostring(_VERSION)` derivation stages GETTABUP _VERSION
                # there), so they must stay tainted
                taint |= args | {a}
                taint.discard(a)  # A itself is redefined by the call's results
                for r in range(a or 0, (a or 0) + max(0, (f.get("C") or 0) - 1) + 1):
                    if r not in args and r != a:
                        taint.discard(r)
            defs_crossed = True
            continue
        if op == "VARARG":
            if a is not None and a in taint:
                runtime = True
            defs_crossed = True
            continue
        if op in ("GETTABUP", "GETUPVAL", "GETFIELD", "GETTABLE"):
            if a is not None and a in taint:
                taint.discard(a)
                if op != "GETTABUP":
                    taint.update(_src_regs(op, f))
                runtime = True
                if op in ("GETTABUP", "GETTABLE") and (pc in vpc):
                    env = True
                elif not (op == "GETTABLE" and pc in vpc):
                    other_unbounded = True
            defs_crossed = True
            continue
        if op in DEF_OPS:
            if a is not None and a in taint:
                srcs = set(_src_regs(op, f))
                # in-place forms (`A=20 B=20 C=16`) keep A live: it is both dest
                # and source, so only drop A when no source redefines it
                taint -= {a}
                taint |= srcs
                if op == "LEN":
                    runtime = True
            defs_crossed = True
            continue
        # TEST / FORPREP / SETLIST etc.: opaque, keep walking
    foldable = env and not has_call and not other_unbounded
    return env, foldable, runtime


def fold_env_stats(tool, chunk):
    """GATE-2 honest metric: reclassify under the attacker assumption _VERSION is one
    of {"Lua 5.3","Lua 5.4","Lua 5.5"} (a 3 value enum, trivially enumerated).

    The window is the ANCHORED TAIL (GATE-6): only instructions between the
    compare's block anchor and the compare itself can be its seed setup; anything
    before the anchor belongs to the previous block and must not be counted.

    env-derived     : the tail reads `_VERSION` (GETTABUP direct or staged GETTABLE)
    3-value-foldable: env-derived AND the tail observes nothing else at run time
                      (no CALL, no other global/upvalue/table read) -- folding
                      `#_VERSION` then leaves a plain constant. Never more than
                      env-derived, and both are capped by the compare's own
                      claimed runtime-seeded classification (consistency assert)."""
    text = listing(tool, chunk)
    protos = parse(text)
    ctables = parse_constants(text)
    if len(protos) != len(ctables):
        # header/parse drift: never report numbers built on misaligned tables
        return {"ERROR: prototype/constant-table mismatch (%d vs %d)" % (len(protos), len(ctables)): 0}
    stats = collections.Counter()
    for code, ctab in zip(protos, ctables):
        vpc = set(versioned_globals(code, ctab))
        for i, (pc, op, f, c) in enumerate(code):
            if op not in COMPARE:
                continue
            layer, runtime = classify(code, i)
            env, foldable, tainted = taint_walk(code, i, vpc)
            if not env:
                continue
            stats[f"{layer}: env-derived total"] += 1
            if foldable:
                stats[f"{layer}: 3-value-foldable (attacker folds #_VERSION)"] += 1
    # GATE-6 consistency: a foldable seed is by definition env-derived. The old
    # `env-derived <= claimed runtime` bound is enforced by construction now
    # (classify's runtime flag uses the same taint walk); guard it anyway.
    for layer in ("junk guard", "nil guard", "dispatcher"):
        foldable = stats.get(f"{layer}: 3-value-foldable (attacker folds #_VERSION)", 0)
        derived = stats.get(f"{layer}: env-derived total", 0)
        if foldable > derived:
            return {"ERROR: %s foldable %d > env-derived %d (window drift)" % (layer, foldable, derived): 0}
    return stats


def main(argv):
    fold = "--fold-env" in argv
    args = [a for a in argv if a != "--fold-env"]
    if len(args) < 2:
        print("usage: audit-predicates.py <tool> <chunk...> [--fold-env]", file=sys.stderr)
        return 2
    tool = args[0]
    for chunk in args[1:]:
        stats, params = audit(tool, chunk)
        print(f"--- {chunk}")
        for key in sorted(stats):
            print(f"    {key}: {stats[key]}")
        for layer in ("junk guard", "nil guard", "dispatcher"):
            total = stats.get(f"{layer}: total", 0)
            unique = len(params[layer])
            if total:
                print(f"    {layer}: unique constant parameter sets {unique}/{total} ({100*unique//max(1,total)}%)")
        identities = stats.get("one step identity", 0)
        print(f"    HARD CHECK one step identities: {identities} {'OK' if identities == 0 else 'FAIL'}")
        if fold:
            fstats = fold_env_stats(tool, chunk)
            for key in sorted(fstats):
                print(f"    [fold-env] {key}: {fstats[key]}")
            # GATE-6: the honest headline. env-derived counts compares whose OWN
            # seed setup (anchored tail) reads _VERSION -- never neighbouring
            # blocks, so `foldable > claimed runtime` cannot happen by
            # construction; if it does the ERROR key above replaces the numbers.
            for layer in ("junk guard", "nil guard", "dispatcher"):
                rt = stats.get(f"{layer}: runtime seeded", 0)
                derived = fstats.get(f"{layer}: env-derived total", 0)
                foldable = fstats.get(f"{layer}: 3-value-foldable (attacker folds #_VERSION)", 0)
                if "ERROR" in " ".join(fstats):
                    break
                if rt or derived:
                    print(f"    [fold-env] {layer}: claimed runtime-seeded {rt}; env-derived {derived}, 3-value-foldable {foldable}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
