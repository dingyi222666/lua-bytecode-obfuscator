-- realistic encoder: recursion, closures, pattern escapes, numeric formatting
local escapes = { ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t" }

local function encodeString(value)
    return '"' .. value:gsub('[\0-\31\\"]', function(char)
        return escapes[char] or string.format("\\u%04x", char:byte())
    end) .. '"'
end

local function encode(value, seen)
    local kind = type(value)
    if kind == "nil" then return "null" end
    if kind == "boolean" then return tostring(value) end
    if kind == "number" then return string.format("%.14g", value) end
    if kind == "string" then return encodeString(value) end
    if kind ~= "table" then error("cannot encode a " .. kind) end
    seen = seen or {}
    if seen[value] then error("cycle detected while encoding") end
    seen[value] = true
    local isArray, count = true, 0
    for key in pairs(value) do
        count = count + 1
        if type(key) ~= "number" or key % 1 ~= 0 then
            isArray = false
            break
        end
    end
    local parts = {}
    if isArray and count > 0 then
        for index = 1, #value do
            parts[#parts + 1] = encode(value[index], seen)
        end
        seen[value] = nil
        return "[" .. table.concat(parts, ",") .. "]"
    end
    for key, item in pairs(value) do
        parts[#parts + 1] = encodeString(tostring(key)) .. ":" .. encode(item, seen)
    end
    seen[value] = nil
    return "{" .. table.concat(parts, ",") .. "}"
end

local payload = {
    name = "lua 字节码",
    version = 5.4,
    tags = { "obfuscate", "bytecode" },
    nested = { ok = true, ratio = 0.5 },
}
print(encode(payload))
print(encodeString('quote " backslash \\ newline \n tab \t'))
