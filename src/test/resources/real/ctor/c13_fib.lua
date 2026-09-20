local memo = { [0] = 0, [1] = 1 }
local function fib(n)
    if not memo[n] then
        memo[n] = fib(n - 1) + fib(n - 2)
    end
    return memo[n]
end
print(fib(20))
