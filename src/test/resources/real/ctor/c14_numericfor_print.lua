-- P4.1 numeric `for` differential fixture: every iteration is printed, so the old
-- extra-iteration bug (`for i=1,3` printing `0 1 2 3`) cannot hide behind a sum.
for i = 1, 3 do print(i) end
for i = 2, 4 do print(i) end
for i = 10, 1, -3 do print(i) end
