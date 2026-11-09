-- Takes stock from a product's counter if it holds enough, in one atomic step
-- (DESIGN.md Section 2, inventory-service). Redis runs a script without
-- interleaving any other command, so there is no window between the check and
-- the decrement.
--
-- KEYS[1]: the counter (inventory:stock:<productId>)
-- ARGV[1]: the quantity to take, > 0
-- Returns  1: taken
--          0: insufficient, nothing changed
--         -1: not loaded (no counter), nothing changed; not the same as zero stock
local current = redis.call('GET', KEYS[1])
if not current then
    return -1
end
local quantity = tonumber(ARGV[1])
if tonumber(current) >= quantity then
    redis.call('DECRBY', KEYS[1], quantity)
    return 1
end
return 0
