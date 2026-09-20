-- closures, upvalues, varargs, nested functions, utf-8 and long strings
local counter = 0

local function makeCounter(step)
    local value = 0
    return function()
        value = value + step
        counter = counter + value
        return value, counter
    end
end

local function sum(...)
    local total = 0
    for _, v in ipairs({...}) do
        total = total + v
    end
    return total
end

local pad = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor"
local utf8Text = "中文常量 · ünïcødé · 日本語"
local longPad = "lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet lorem ipsum dolor sit amet "

local c = makeCounter(3)
local a, b = c()
print(type(a), type(b), sum(1, 2, 3), pad, utf8Text, #longPad)
