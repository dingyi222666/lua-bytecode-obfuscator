-- rich control flow: if/elseif, while, repeat, numeric for, generic for, break, goto
local function classify(n)
    if n < 0 then
        return "negative"
    elseif n == 0 then
        return "zero"
    else
        return "positive"
    end
end

local function sumEven(limit)
    local total = 0
    local i = 0
    while i < limit do
        i = i + 1
        if i % 2 ~= 0 then
            goto continue
        end
        total = total + i
        ::continue::
    end
    repeat
        total = total - 2
    until total <= 0
    for index = 1, 10 do
        if index == 5 then break end
        total = total + index
    end
    for _, value in ipairs({1, 2, 3}) do
        total = total + value
    end
    return total, classify(total)
end

print(sumEven(10))
