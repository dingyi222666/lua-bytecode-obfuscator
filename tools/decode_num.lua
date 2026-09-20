-- Exact tag + 8-byte little-endian records. Parameters patched by LuaConstantEncryptor.
return function(data, idx, seed)
  local byte, char, unpack = string.byte, string.char, string.unpack
  local m, inc, shift = 1061988609, 718832137, 11
  local state = (seed ~ (idx * -1640531527)) & 0x7FFFFFFF
  local bytes = {}
  for i = 1, 9 do
    state = (state * m + inc) % 2147483648
    bytes[i] = byte(data, i) ~ ((state >> shift) & 0xFF)
  end
  local tag = bytes[1]
  if tag == 1 then return bytes[2] ~= 0 end
  local raw = char(bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9])
  if tag == 0 then return (unpack("<i8", raw)) end
  if tag == 2 then return (unpack("<d", raw)) end
  error("kget: corrupted constant blob")
end
