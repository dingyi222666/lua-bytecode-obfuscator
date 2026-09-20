-- big_module.lua: a realistic ~600 line Lua module used as an obfuscation stress fixture.
-- Deterministic output: no math.random / os.time, no `pairs` order dependence.

local mathx = {}
function mathx.gcd(a, b)
    while b ~= 0 do
        a, b = b, a % b
    end
    return math.abs(a)
end
function mathx.lcm(a, b)
    if a == 0 or b == 0 then return 0 end
    return math.abs(a * b) // mathx.gcd(a, b)
end
function mathx.isPrime(n)
    if n < 2 then return false end
    if n % 2 == 0 then return n == 2 end
    local factor = 3
    while factor * factor <= n do
        if n % factor == 0 then return false end
        factor = factor + 2
    end
    return true
end
function mathx.primesUpTo(limit)
    local composite, primes = {}, {}
    for candidate = 2, limit do
        if not composite[candidate] then
            primes[#primes + 1] = candidate
            for multiple = candidate * candidate, limit, candidate do
                composite[multiple] = true
            end
        end
    end
    return primes
end
local factorials = { [0] = 1 }
function mathx.factorial(n)
    if factorials[n] then return factorials[n] end
    local value = n * mathx.factorial(n - 1)
    factorials[n] = value
    return value
end
local fibCache = { [0] = 0, [1] = 1 }
function mathx.fib(n)
    if fibCache[n] then return fibCache[n] end
    local value = mathx.fib(n - 1) + mathx.fib(n - 2)
    fibCache[n] = value
    return value
end
function mathx.matrixMultiply(a, b)
    local rows, inner, cols = #a, #b, #b[1]
    local result = {}
    for i = 1, rows do
        result[i] = {}
        for j = 1, cols do
            local sum = 0
            for k = 1, inner do
                sum = sum + a[i][k] * b[k][j]
            end
            result[i][j] = sum
        end
    end
    return result
end

local str = {}
function str.split(text, separator)
    local parts, from = {}, 1
    while true do
        local at = string.find(text, separator, from, true)
        if not at then break end
        parts[#parts + 1] = string.sub(text, from, at - 1)
        from = at + #separator
    end
    parts[#parts + 1] = string.sub(text, from)
    return parts
end
function str.trim(text)
    return (string.gsub(text, '^%s*(.-)%s*$', '%1'))
end
function str.startsWith(text, prefix)
    return string.sub(text, 1, #prefix) == prefix
end
function str.endsWith(text, suffix)
    return suffix == '' or string.sub(text, -#suffix) == suffix
end
function str.pad(text, width, filler)
    filler = filler or ' '
    while #text < width do text = text .. filler end
    return string.sub(text, 1, width)
end
function str.title(text)
    return (string.gsub(text, '(%a)([%w_]*)', function(head, tail)
        return string.upper(head) .. string.lower(tail)
    end))
end
function str.repeatJoin(parts, times) 
    local out = {}
    for _ = 1, times do
        for index = 1, #parts do out[#out + 1] = parts[index] end
    end
    return table.concat(out, '-')
end

local List = {}
List.__index = List
function List.new(...)
    return setmetatable({ items = { ... }, size = select('#', ...) }, List)
end
function List:push(value)
    self.size = self.size + 1
    self.items[self.size] = value
    return self
end
function List:pop()
    if self.size == 0 then return nil end
    local value = self.items[self.size]
    self.items[self.size] = nil
    self.size = self.size - 1
    return value
end
function List:at(index)
    if index < 1 or index > self.size then return nil end
    return self.items[index]
end
function List:map(transform)
    local result = List.new()
    for index = 1, self.size do result:push(transform(self.items[index], index)) end
    return result
end
function List:filter(keep)
    local result = List.new()
    for index = 1, self.size do
        local value = self.items[index]
        if keep(value, index) then result:push(value) end
    end
    return result
end
function List:reduce(combine, initial)
    local accumulator = initial
    for index = 1, self.size do accumulator = combine(accumulator, self.items[index]) end
    return accumulator
end
function List:join(separator)
    return table.concat(self.items, separator, 1, self.size)
end

local Stack = {}
Stack.__index = Stack
function Stack.new() return setmetatable({ data = {}, top = 0 }, Stack) end
function Stack:push(v) self.top = self.top + 1 self.data[self.top] = v end
function Stack:pop()
    if self.top == 0 then return nil end
    local v = self.data[self.top]
    self.data[self.top] = nil
    self.top = self.top - 1
    return v
end
function Stack:peek() return self.data[self.top] end

local Queue = {}
Queue.__index = Queue
function Queue.new() return setmetatable({ data = {}, head = 1, tail = 0 }, Queue) end
function Queue:enqueue(v) self.tail = self.tail + 1 self.data[self.tail] = v end
function Queue:dequeue()
    if self.head > self.tail then return nil end
    local v = self.data[self.head]
    self.data[self.head] = nil
    self.head = self.head + 1
    return v
end

local sort = {}
function sort.insertion(values)
    local copy = {}
    for index = 1, #values do copy[index] = values[index] end
    for index = 2, #copy do
        local key, probe = copy[index], index - 1
        while probe >= 1 and copy[probe] > key do
            copy[probe + 1] = copy[probe]
            probe = probe - 1
        end
        copy[probe + 1] = key
    end
    return copy
end
local function merge(left, right, out)
    local i, j = 1, 1
    while i <= #left and j <= #right do
        if left[i] <= right[j] then out[#out + 1] = left[i] i = i + 1
        else out[#out + 1] = right[j] j = j + 1 end
    end
    while i <= #left do out[#out + 1] = left[i] i = i + 1 end
    while j <= #right do out[#out + 1] = right[j] j = j + 1 end
    return out
end
function sort.merge(values)
    if #values <= 1 then return values end
    local middle = #values // 2
    local left, right = {}, {}
    for index = 1, middle do left[index] = values[index] end
    for index = middle + 1, #values do right[index - middle] = values[index] end
    return merge(sort.merge(left), sort.merge(right), {})
end
function sort.quick(values, low, high)
    low, high = low or 1, high or #values
    if low >= high then return values end
    local pivot = values[(low + high) // 2]
    local i, j = low, high
    while i <= j do
        while values[i] < pivot do i = i + 1 end
        while values[j] > pivot do j = j - 1 end
        if i <= j then
            values[i], values[j] = values[j], values[i]
            i, j = i + 1, j - 1
        end
    end
    sort.quick(values, low, j)
    sort.quick(values, i, high)
    return values
end
function sort.binarySearch(values, target)
    local low, high = 1, #values
    while low <= high do
        local middle = (low + high) // 2
        if values[middle] == target then return middle end
        if values[middle] < target then low = middle + 1 else high = middle - 1 end
    end
    return nil
end

local codec = {}
codec.hexDigits = '0123456789abcdef'
function codec.hexDump(text)
    local out = {}
    for index = 1, #text do
        local byte = string.byte(text, index)
        local high = byte // 16 + 1
        local low = byte % 16 + 1
        out[#out + 1] = string.sub(codec.hexDigits, high, high) .. string.sub(codec.hexDigits, low, low)
    end
    return table.concat(out, ' ')
end
function codec.urlEncode(text)
    return (string.gsub(text, '[^%w%-%_%.~]', function(char)
        return string.format('%%%02X', string.byte(char))
    end))
end
codec.base64Alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
function codec.base64(text)
    local out, buffer, bits = {}, 0, 0
    for index = 1, #text do
        buffer = buffer * 256 + string.byte(text, index)
        bits = bits + 8
        while bits >= 6 do
            bits = bits - 6
            local group = (buffer // (2 ^ bits)) % 64 + 1
            out[#out + 1] = string.sub(codec.base64Alphabet, group, group)
        end
    end
    if bits > 0 then
        local group = (buffer * (2 ^ (6 - bits))) % 64 + 1
        out[#out + 1] = string.sub(codec.base64Alphabet, group, group)
    end
    while #out % 4 ~= 0 do out[#out + 1] = '=' end
    return table.concat(out)
end

local text = {}
function text.parseDelimited(source, delimiter)
    local rows = {}
    for line in string.gmatch(source, '[^\n]+') do
        local row = str.split(line, delimiter)
        rows[#rows + 1] = row
    end
    return rows
end
function text.tokenize(source)
    local tokens, index, length = {}, 1, #source
    while index <= length do
        local char = string.sub(source, index, index)
        if char == ' ' or char == '\t' then
            index = index + 1
        elseif string.match(char, '%d') then
            local number = string.match(source, '^%d+%.?%d*', index)
            tokens[#tokens + 1] = { kind = 'number', value = tonumber(number) }
            index = index + #number
        elseif string.match(char, '[%a_]') then
            local word = string.match(source, '^[%a_][%w_]*', index)
            tokens[#tokens + 1] = { kind = 'word', value = word }
            index = index + #word
        else
            tokens[#tokens + 1] = { kind = 'symbol', value = char }
            index = index + 1
        end
    end
    return tokens
end

local errors = {}
function errors.attempt(fn, ...)
    local ok, result = pcall(fn, ...)
    if not ok then return nil, tostring(result) end
    return result
end
function errors.retry(fn, attempts)
    local lastError
    for attempt = 1, attempts do
        local ok, result = pcall(fn, attempt)
        if ok then return result, attempt end
        lastError = result
    end
    return nil, lastError
end

local MESSAGES = {
    'message 001: the quick brown fox jumps over the lazy dog while parsing record 1',
    'message 002: the quick brown fox jumps over the lazy dog while parsing record 2',
    'message 003: the quick brown fox jumps over the lazy dog while parsing record 3',
    'message 004: the quick brown fox jumps over the lazy dog while parsing record 4',
    'message 005: the quick brown fox jumps over the lazy dog while parsing record 5',
    'message 006: the quick brown fox jumps over the lazy dog while parsing record 6',
    'message 007: the quick brown fox jumps over the lazy dog while parsing record 7',
    'message 008: the quick brown fox jumps over the lazy dog while parsing record 8',
    'message 009: the quick brown fox jumps over the lazy dog while parsing record 9',
    'message 010: the quick brown fox jumps over the lazy dog while parsing record 10',
    'message 011: the quick brown fox jumps over the lazy dog while parsing record 11',
    'message 012: the quick brown fox jumps over the lazy dog while parsing record 12',
    'message 013: the quick brown fox jumps over the lazy dog while parsing record 13',
    'message 014: the quick brown fox jumps over the lazy dog while parsing record 14',
    'message 015: the quick brown fox jumps over the lazy dog while parsing record 15',
    'message 016: the quick brown fox jumps over the lazy dog while parsing record 16',
    'message 017: the quick brown fox jumps over the lazy dog while parsing record 17',
    'message 018: the quick brown fox jumps over the lazy dog while parsing record 18',
    'message 019: the quick brown fox jumps over the lazy dog while parsing record 19',
    'message 020: the quick brown fox jumps over the lazy dog while parsing record 20',
    'message 021: the quick brown fox jumps over the lazy dog while parsing record 21',
    'message 022: the quick brown fox jumps over the lazy dog while parsing record 22',
    'message 023: the quick brown fox jumps over the lazy dog while parsing record 23',
    'message 024: the quick brown fox jumps over the lazy dog while parsing record 24',
    'message 025: the quick brown fox jumps over the lazy dog while parsing record 25',
    'message 026: the quick brown fox jumps over the lazy dog while parsing record 26',
    'message 027: the quick brown fox jumps over the lazy dog while parsing record 27',
    'message 028: the quick brown fox jumps over the lazy dog while parsing record 28',
    'message 029: the quick brown fox jumps over the lazy dog while parsing record 29',
    'message 030: the quick brown fox jumps over the lazy dog while parsing record 30',
    'message 031: the quick brown fox jumps over the lazy dog while parsing record 31',
    'message 032: the quick brown fox jumps over the lazy dog while parsing record 32',
    'message 033: the quick brown fox jumps over the lazy dog while parsing record 33',
    'message 034: the quick brown fox jumps over the lazy dog while parsing record 34',
    'message 035: the quick brown fox jumps over the lazy dog while parsing record 35',
    'message 036: the quick brown fox jumps over the lazy dog while parsing record 36',
    'message 037: the quick brown fox jumps over the lazy dog while parsing record 37',
    'message 038: the quick brown fox jumps over the lazy dog while parsing record 38',
    'message 039: the quick brown fox jumps over the lazy dog while parsing record 39',
    'message 040: the quick brown fox jumps over the lazy dog while parsing record 40',
    'message 041: the quick brown fox jumps over the lazy dog while parsing record 41',
    'message 042: the quick brown fox jumps over the lazy dog while parsing record 42',
    'message 043: the quick brown fox jumps over the lazy dog while parsing record 43',
    'message 044: the quick brown fox jumps over the lazy dog while parsing record 44',
    'message 045: the quick brown fox jumps over the lazy dog while parsing record 45',
    'message 046: the quick brown fox jumps over the lazy dog while parsing record 46',
    'message 047: the quick brown fox jumps over the lazy dog while parsing record 47',
    'message 048: the quick brown fox jumps over the lazy dog while parsing record 48',
    'message 049: the quick brown fox jumps over the lazy dog while parsing record 49',
    'message 050: the quick brown fox jumps over the lazy dog while parsing record 50',
    'message 051: the quick brown fox jumps over the lazy dog while parsing record 51',
    'message 052: the quick brown fox jumps over the lazy dog while parsing record 52',
    'message 053: the quick brown fox jumps over the lazy dog while parsing record 53',
    'message 054: the quick brown fox jumps over the lazy dog while parsing record 54',
    'message 055: the quick brown fox jumps over the lazy dog while parsing record 55',
    'message 056: the quick brown fox jumps over the lazy dog while parsing record 56',
    'message 057: the quick brown fox jumps over the lazy dog while parsing record 57',
    'message 058: the quick brown fox jumps over the lazy dog while parsing record 58',
    'message 059: the quick brown fox jumps over the lazy dog while parsing record 59',
    'message 060: the quick brown fox jumps over the lazy dog while parsing record 60',
    'message 061: the quick brown fox jumps over the lazy dog while parsing record 61',
    'message 062: the quick brown fox jumps over the lazy dog while parsing record 62',
    'message 063: the quick brown fox jumps over the lazy dog while parsing record 63',
    'message 064: the quick brown fox jumps over the lazy dog while parsing record 64',
    'message 065: the quick brown fox jumps over the lazy dog while parsing record 65',
    'message 066: the quick brown fox jumps over the lazy dog while parsing record 66',
    'message 067: the quick brown fox jumps over the lazy dog while parsing record 67',
    'message 068: the quick brown fox jumps over the lazy dog while parsing record 68',
    'message 069: the quick brown fox jumps over the lazy dog while parsing record 69',
    'message 070: the quick brown fox jumps over the lazy dog while parsing record 70',
    'message 071: the quick brown fox jumps over the lazy dog while parsing record 71',
    'message 072: the quick brown fox jumps over the lazy dog while parsing record 72',
    'message 073: the quick brown fox jumps over the lazy dog while parsing record 73',
    'message 074: the quick brown fox jumps over the lazy dog while parsing record 74',
    'message 075: the quick brown fox jumps over the lazy dog while parsing record 75',
    'message 076: the quick brown fox jumps over the lazy dog while parsing record 76',
    'message 077: the quick brown fox jumps over the lazy dog while parsing record 77',
    'message 078: the quick brown fox jumps over the lazy dog while parsing record 78',
    'message 079: the quick brown fox jumps over the lazy dog while parsing record 79',
    'message 080: the quick brown fox jumps over the lazy dog while parsing record 80',
    'message 081: the quick brown fox jumps over the lazy dog while parsing record 81',
    'message 082: the quick brown fox jumps over the lazy dog while parsing record 82',
    'message 083: the quick brown fox jumps over the lazy dog while parsing record 83',
    'message 084: the quick brown fox jumps over the lazy dog while parsing record 84',
    'message 085: the quick brown fox jumps over the lazy dog while parsing record 85',
    'message 086: the quick brown fox jumps over the lazy dog while parsing record 86',
    'message 087: the quick brown fox jumps over the lazy dog while parsing record 87',
    'message 088: the quick brown fox jumps over the lazy dog while parsing record 88',
    'message 089: the quick brown fox jumps over the lazy dog while parsing record 89',
    'message 090: the quick brown fox jumps over the lazy dog while parsing record 90',
    'message 091: the quick brown fox jumps over the lazy dog while parsing record 91',
    'message 092: the quick brown fox jumps over the lazy dog while parsing record 92',
    'message 093: the quick brown fox jumps over the lazy dog while parsing record 93',
    'message 094: the quick brown fox jumps over the lazy dog while parsing record 94',
    'message 095: the quick brown fox jumps over the lazy dog while parsing record 95',
    'message 096: the quick brown fox jumps over the lazy dog while parsing record 96',
    'message 097: the quick brown fox jumps over the lazy dog while parsing record 97',
    'message 098: the quick brown fox jumps over the lazy dog while parsing record 98',
    'message 099: the quick brown fox jumps over the lazy dog while parsing record 99',
    'message 100: the quick brown fox jumps over the lazy dog while parsing record 100',
    'message 101: the quick brown fox jumps over the lazy dog while parsing record 101',
    'message 102: the quick brown fox jumps over the lazy dog while parsing record 102',
    'message 103: the quick brown fox jumps over the lazy dog while parsing record 103',
    'message 104: the quick brown fox jumps over the lazy dog while parsing record 104',
    'message 105: the quick brown fox jumps over the lazy dog while parsing record 105',
    'message 106: the quick brown fox jumps over the lazy dog while parsing record 106',
    'message 107: the quick brown fox jumps over the lazy dog while parsing record 107',
    'message 108: the quick brown fox jumps over the lazy dog while parsing record 108',
    'message 109: the quick brown fox jumps over the lazy dog while parsing record 109',
    'message 110: the quick brown fox jumps over the lazy dog while parsing record 110',
    'message 111: the quick brown fox jumps over the lazy dog while parsing record 111',
    'message 112: the quick brown fox jumps over the lazy dog while parsing record 112',
    'message 113: the quick brown fox jumps over the lazy dog while parsing record 113',
    'message 114: the quick brown fox jumps over the lazy dog while parsing record 114',
    'message 115: the quick brown fox jumps over the lazy dog while parsing record 115',
    'message 116: the quick brown fox jumps over the lazy dog while parsing record 116',
    'message 117: the quick brown fox jumps over the lazy dog while parsing record 117',
    'message 118: the quick brown fox jumps over the lazy dog while parsing record 118',
    'message 119: the quick brown fox jumps over the lazy dog while parsing record 119',
    'message 120: the quick brown fox jumps over the lazy dog while parsing record 120',
}
local VECTORS = {
    48, 85, 122, 159, 196, 233, 270, 307, 344, 381,
    418, 455, 492, 529, 566, 603, 640, 677, 714, 751,
    788, 825, 862, 899, 936, 973, 13, 50, 87, 124,
    161, 198, 235, 272, 309, 346, 383, 420, 457, 494,
    531, 568, 605, 642, 679, 716, 753, 790, 827, 864,
    901, 938, 975, 15, 52, 89, 126, 163, 200, 237,
    274, 311, 348, 385, 422, 459, 496, 533, 570, 607,
    644, 681, 718, 755, 792, 829, 866, 903, 940, 977,
}

local function digest(values)
    local total, product = 0, 1
    for index = 1, #values do
        total = total + values[index]
        product = (product * (values[index] % 97 + 1)) % 1000003
    end
    return total, product
end

local report = {}
report[#report + 1] = 'gcd=' .. mathx.gcd(462, 1071)
report[#report + 1] = 'lcm=' .. mathx.lcm(21, 6)
report[#report + 1] = 'primes=' .. #mathx.primesUpTo(200)
report[#report + 1] = 'factorial=' .. mathx.factorial(12)
report[#report + 1] = 'fib=' .. mathx.fib(40)
local matrix = mathx.matrixMultiply({ { 1, 2 }, { 3, 4 } }, { { 5, 6 }, { 7, 8 } })
report[#report + 1] = 'matrix=' .. matrix[1][1] .. ',' .. matrix[2][2]
report[#report + 1] = 'split=' .. #str.split('alpha,beta,gamma,delta,epsilon', ',')
report[#report + 1] = 'title=' .. str.title('lua bytecode obfuscation module')
report[#report + 1] = 'pad=' .. str.pad('42', 8, '.')
report[#report + 1] = 'join=' .. str.repeatJoin({ 'a', 'b', 'c' }, 3)
local list = List.new(3, 1, 4, 1, 5, 9, 2, 6)
report[#report + 1] = 'list=' .. list:filter(function(v) return v > 2 end):reduce(function(a, b) return a + b end, 0)
report[#report + 1] = 'listJoin=' .. list:map(function(v) return v * 2 end):join(',')
local stack = Stack.new()
for index = 1, 10 do stack:push(index * index) end
report[#report + 1] = 'stack=' .. tostring(stack:pop()) .. ',' .. tostring(stack:peek())
local queue = Queue.new()
for index = 1, 8 do queue:enqueue('q' .. index) end
report[#report + 1] = 'queue=' .. tostring(queue:dequeue()) .. ',' .. tostring(queue:dequeue())
local sorted = sort.quick({ 42, 7, 19, 3, 88, 1, 23, 5, 17, 9 })
report[#report + 1] = 'quick=' .. table.concat(sorted, ',')
report[#report + 1] = 'merge=' .. table.concat(sort.merge({ 5, 2, 9, 1, 7 }), ',')
report[#report + 1] = 'insertion=' .. table.concat(sort.insertion({ 4, 8, 2, 6 }), ',')
report[#report + 1] = 'search=' .. tostring(sort.binarySearch(sorted, 23))
report[#report + 1] = 'hex=' .. codec.hexDump('Lua!')
report[#report + 1] = 'url=' .. codec.urlEncode('a b/c?d=e&f')
report[#report + 1] = 'base64=' .. codec.base64('obfuscate me')
local rows = text.parseDelimited('a,b,c\n1,2,3\n4,5,6', ',')
report[#report + 1] = 'rows=' .. #rows .. 'x' .. #rows[1] .. ':' .. rows[3][3]
local tokens = text.tokenize('total = 12 + foo_1 * (3.5 - bar)')
report[#report + 1] = 'tokens=' .. #tokens .. ':' .. tokens[1].kind .. ',' .. tokens[#tokens].kind
local ok, err = errors.attempt(function() error('boom 42') end)
report[#report + 1] = 'error=' .. tostring(ok) .. ',' .. tostring(err)
local value, attempt = errors.retry(function(n) if n < 3 then error('retry ' .. n) end return n * 10 end, 5)
report[#report + 1] = 'retry=' .. tostring(value) .. ',' .. tostring(attempt)
local total, product = digest(VECTORS)
report[#report + 1] = 'vectors=' .. #VECTORS .. ':' .. total .. ':' .. product
report[#report + 1] = 'messages=' .. #MESSAGES .. ':' .. MESSAGES[1]:sub(1, 11) .. ':' .. MESSAGES[120]:sub(-3)
for index = 1, #MESSAGES, 17 do
    local message = MESSAGES[index]
    if str.startsWith(message, 'message') and #message > 20 then
        report[#report + 1] = 'msg' .. index .. '=' .. #message
    end
end

print(table.concat(report, '\n'))
