-- repeated strings: 5.5 stores them once and reuses them by index
local function first()
    local shared_name = "shared_literal"
    return shared_name
end

local function second()
    local shared_name = "shared_literal"
    return shared_name, "shared_literal", "only_here"
end

print(first, second)
