local total, i = 0, 0
while true do i = i + 1 if i > 5 then break end if i % 2 == 1 then goto cont end total = total + i ::cont:: end
print(total)
