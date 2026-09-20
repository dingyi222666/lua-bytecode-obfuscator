local total = 0
for a = 1, 4 do for b = 1, 4 do if b == 3 then break end total = total + a * b end end
print(total)
