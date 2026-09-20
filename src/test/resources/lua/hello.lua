-- basic constants, one named function
local greeting = "hello world, 你好 世界"
local count = 42
local negative = -12345
local ratio = 0.5
local nothing = nil
local flag = true

local function add(a, b)
    return a + b + count
end

print(add(1, 2), greeting, ratio, nothing, flag)
