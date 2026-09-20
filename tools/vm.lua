--// LuaVirtualMachine: a small register VM used by the P4 virtualization layer.
--
-- The obfuscator translates a Lua prototype into this VM's own instruction
-- stream (a plain byte string, later encrypted by the string layer), so the
-- chunk only carries:
--
--     [ this VM interpreter ] + [ encrypted program ] + [ constant table ]
--
-- Instruction encoding (8 bytes, little endian):
--
--     op:u8  a:u8  b:u8  c:u8  x:i32
--
-- `x` carries constants, jump offsets and immediates; `b`/`c` carry register or
-- constant flags through their high bit (`RK`), so one generic operation covers
-- the register and constant operand forms of the original ISA.
--
-- The opcode numbering is deliberately *data* here (`OPS` below): the translator
-- can permute it per chunk (including swapping equivalent operations such as
-- `ADD` with `SUB` over a negated operand), which is the P4.3/P4.4 hardening.
local M = {}

local byte, char, sub = string.byte, string.char, string.sub
local floor = math.floor

-- opcodes
local MOVE, LOADK, LOADI, LOADNIL, LOADBOOL = 1, 2, 3, 4, 5
local GETUPVAL, SETUPVAL = 6, 7
local ADD, SUB, MUL, MOD, IDIV, DIV, POW = 8, 9, 10, 11, 12, 13, 14
local BAND, BOR, BXOR, SHL, SHR = 15, 16, 17, 18, 19
local UNM, NOT, LEN = 20, 21, 22
local EQ, LT, LE, TEST, TESTSET = 23, 24, 25, 26, 27
local JMP = 28
local INDEX, SETINDEX = 29, 30
local NEWTABLE, SELF = 31, 32
local CALL, TAILCALL = 33, 34
local RETURN, RETURN0, RETURN1 = 35, 36, 37
local FORPREP, FORLOOP = 38, 39
local CLOSURE = 40

local ARITH = {
  [ADD] = function(x, y) return x + y end,
  [SUB] = function(x, y) return x - y end,
  [MUL] = function(x, y) return x * y end,
  [MOD] = function(x, y) return x % y end,
  [IDIV] = function(x, y) return x // y end,
  [DIV] = function(x, y) return x / y end,
  [POW] = function(x, y) return x ^ y end,
  [BAND] = function(x, y) return x & y end,
  [BOR] = function(x, y) return x | y end,
  [BXOR] = function(x, y) return x ~ y end,
  [SHL] = function(x, y) return x << y end,
  [SHR] = function(x, y) return x >> y end,
}

local function decode(program)
  local count = 0
  for i = 1, 4 do
    count = count + byte(program, i) * 256 ^ (i - 1)
  end
  local function u32(p)
    local v = 0
    for i = 0, 3 do v = v + byte(program, p + i) * 256 ^ i end
    if v >= 2147483648 then v = v - 4294967296 end
    return v
  end
  return count, function(index)                 -- index is 1 based instruction slot
    local p = 5 + (index - 1) * 8
    return byte(program, p), byte(program, p + 1), byte(program, p + 2), byte(program, p + 3), u32(p + 4)
  end
end

-- `rk` operands: high bit set means "constant index", otherwise a register.
local function rk(value, flag, R, K)
  if flag >= 128 then return K[(flag - 128) * 256 + value] end
  return R[value]
end

--- Runs one translated prototype.
-- @param program   the byte string produced by the translator
-- @param constants the prototype's constant table
-- @param upvalues  the prototype's upvalue values
-- @param args      the call arguments
function M.run(program, constants, upvalues, args)
  local K = constants or {}
  local U = upvalues or {}
  local count = 0
  for i = 1, 4 do count = count + byte(program, i) * 256 ^ (i - 1) end
  local R = {}
  local pc = 1
  local top = 0
  local function get(index)
    local op, a, b, c, x = byte(program, 5 + (index - 1) * 8), byte(program, 6 + (index - 1) * 8),
      byte(program, 7 + (index - 1) * 8), byte(program, 8 + (index - 1) * 8), 0
    local p = 9 + (index - 1) * 8
    for i = 0, 3 do x = x + byte(program, p + i) * 256 ^ i end
    if x >= 2147483648 then x = x - 4294967296 end
    return op, a, b, c, x
  end

  local argCount = #args
  for i = 1, argCount do R[i - 1] = args[i] end

  while pc <= count do
    local op, a, b, c, x = get(pc)
    if op == MOVE then
      R[a] = R[b]
    elseif op == LOADK then
      R[a] = K[x + 1]
    elseif op == LOADI then
      R[a] = x
    elseif op == LOADNIL then
      for i = a, a + b do R[i] = nil end
    elseif op == LOADBOOL then
      R[a] = b ~= 0
      if c ~= 0 then pc = pc + 1 end
    elseif op == GETUPVAL then
      R[a] = U[b + 1]
    elseif op == SETUPVAL then
      U[b + 1] = R[a]
    elseif ARITH[op] then
      R[a] = ARITH[op](rk(b, b, R, K), rk(c, c, R, K))
    elseif op == UNM then
      R[a] = -R[b]
    elseif op == NOT then
      R[a] = not R[b]
    elseif op == LEN then
      R[a] = #R[b]
    elseif op == INDEX then
      R[a] = R[b][rk(c, c, R, K)]
    elseif op == SETINDEX then
      R[a][R[b]] = rk(c, c, R, K)
    elseif op == NEWTABLE then
      R[a] = {}
    elseif op == SELF then
      local receiver = R[b]
      R[a + 1] = receiver
      R[a] = receiver[rk(c, c, R, K)]
    elseif op == JMP then
      pc = pc + x
    elseif op == EQ or op == LT or op == LE then
      local left, right = R[a], rk(b, b, R, K)
      if op == LT then left, right = left, right end
      local cond
      if op == EQ then cond = left == right else cond = left < right end
      if (op == LT) then cond = R[a] < rk(b, b, R, K)
      elseif op == LE then cond = R[a] <= rk(b, b, R, K) end
      if cond ~= (c ~= 0) then pc = pc + 1 end
    elseif op == TEST then
      if (R[a] and true or false) ~= (c ~= 0) then pc = pc + 1 end
    elseif op == TESTSET then
      if (R[b] and true or false) == (c ~= 0) then R[a] = R[b] else pc = pc + 1 end
    elseif op == CALL then
      local fn = R[a]
      local callArgs = {}
      for i = 1, b - 1 do callArgs[i] = R[a + i] end
      local results = { fn(table.unpack(callArgs, 1, b - 1)) }
      if c == 0 then
        for i = 1, #results do R[a + i - 1] = results[i] end
      else
        for i = 1, c - 1 do R[a + i - 1] = results[i] end
      end
    elseif op == TAILCALL then
      local fn = R[a]
      local callArgs = {}
      for i = 1, b - 1 do callArgs[i] = R[a + i] end
      return fn(table.unpack(callArgs, 1, b - 1))
    elseif op == RETURN then
      if b == 0 then return table.unpack(R, a, top) end
      return table.unpack(R, a, a + b - 2)
    elseif op == RETURN0 then
      return
    elseif op == RETURN1 then
      return R[a]
    elseif op == FORPREP then
      local init, limit, step = R[a], R[a + 1], R[a + 2]
      if (step > 0 and init > limit) or (step < 0 and init < limit) or step == 0 then
        pc = pc + x
      else
        R[a + 3] = init
      end
    elseif op == FORLOOP then
      local index = R[a] + R[a + 2]
      local limit = R[a + 1]
      local step = R[a + 2]
      if (step > 0 and index <= limit) or (step < 0 and index >= limit) then
        R[a] = index
        R[a + 3] = index
        pc = pc - x
      end
    elseif op == CLOSURE then
      error("virtualized CLOSURE is not supported yet")
    else
      error("virtual machine: unknown opcode " .. tostring(op) .. " at " .. pc)
    end
    pc = pc + 1
  end
end

return M