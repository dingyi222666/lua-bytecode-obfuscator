-- realistic algorithms: nested loops, recursion, table mutation, multiple returns
local function sieve(limit)
    local composite = {}
    local primes = {}
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

local function quicksort(values, low, high)
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
    quicksort(values, low, j)
    quicksort(values, i, high)
    return values
end

local memo = { [0] = 0, [1] = 1 }
local function fib(n)
    if not memo[n] then
        memo[n] = fib(n - 1) + fib(n - 2)
    end
    return memo[n]
end

local primes = sieve(60)
print(#primes, primes[1], primes[#primes])
print(table.concat(quicksort({ 9, 3, 7, 1, 8, 2, 6, 4, 5 }), ","))
print(fib(30))
