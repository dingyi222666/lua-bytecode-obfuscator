-- realistic OOP: metatables, closures, string.format, error paths
local Account = {}
Account.__index = Account

local function format(cents)
    return string.format("%d.%02d", cents // 100, cents % 100)
end

function Account.new(owner, balance)
    local self = setmetatable({}, Account)
    self.owner = owner
    self.balance = balance or 0
    self.history = {}
    return self
end

function Account:deposit(amount)
    if amount <= 0 then
        error("deposit must be positive, got " .. amount)
    end
    self.balance = self.balance + amount
    table.insert(self.history, ("deposit +%s"):format(format(amount)))
    return self.balance
end

function Account:withdraw(amount)
    if amount > self.balance then
        return nil, "insufficient funds: balance " .. format(self.balance)
    end
    self.balance = self.balance - amount
    table.insert(self.history, ("withdraw -%s"):format(format(amount)))
    return self.balance
end

function Account:statement()
    local lines = { ("statement for %s"):format(self.owner) }
    for index = 1, #self.history do
        lines[#lines + 1] = string.format("  %2d) %s", index, self.history[index])
    end
    lines[#lines + 1] = ("balance %s"):format(format(self.balance))
    return table.concat(lines, "\n")
end

local account = Account.new("ada", 500)
account:deposit(250)
local ok, err = account:withdraw(1000)
print(ok, err)
print(account:statement())
