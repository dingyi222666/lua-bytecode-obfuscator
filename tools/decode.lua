--// string pool decoder: XORs the payload with an LCG keystream
local function decode(data, seed)
  local out = {}
  local state = seed
  local byte = string.byte
  local char = string.char
  local concat = table.concat
  for i = 1, #data do
    state = (state * 1103515245 + 12345) % 2147483648
    out[i] = char((byte(data, i) ~ (state >> 16)) & 0xFF)
  end
  return concat(out)
end
return decode
