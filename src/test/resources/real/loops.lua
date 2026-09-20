-- minimal per-construct loop fixture: one function per control flow shape, deterministic output
local function numericFor()
    local total = 0
    for index = 1, 10 do
        total = total + index
    end
    return total
end

local function numericForStep()
    local total = 0
    for index = 10, 1, -3 do
        total = total + index
    end
    return total
end

local function whileLoop(limit)
    local total, index = 0, 0
    while index < limit do
        index = index + 1
        total = total + index * 2
    end
    return total
end

local function repeatLoop(limit)
    local total, index = 0, 0
    repeat
        index = index + 1
        total = total + index
    until index >= limit
    return total
end

local function genericFor()
    local total = 0
    for _, value in ipairs({ 4, 5, 6 }) do
        total = total + value
    end
    return total
end

local function genericForNext()
    local total = 0
    for key, value in next, { 7, 8, 9 } do
        total = total + key + value
    end
    return total
end

local function breakOut()
    local total = 0
    for index = 1, 20 do
        if index == 6 then break end
        total = total + index
    end
    return total
end

local function nestedBreak()
    local total = 0
    for outer = 1, 4 do
        for inner = 1, 4 do
            if inner == 3 then break end
            total = total + outer * inner
        end
    end
    return total
end

local function gotoLoop()
    local total, index = 0, 0
    while true do
        index = index + 1
        if index > 5 then break end
        if index % 2 == 1 then goto continue end
        total = total + index
        ::continue::
    end
    return total
end

print(numericFor(), numericForStep(), whileLoop(7), repeatLoop(6), genericFor(), genericForNext(), breakOut(), nestedBreak(), gotoLoop())
