-- Adds to a product's counter only if it exists (DESIGN.md Section 2,
-- inventory-service). A plain INCRBY on a missing key would create a counter
-- holding only the delta; a missing counter must stay missing, so that it is
-- loaded from PostgreSQL when next needed.
--
-- KEYS[1]: the counter (inventory:stock:<productId>)
-- ARGV[1]: the amount to add; negative for a decrease
-- Returns the new value, or nil if there is no counter.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('INCRBY', KEYS[1], ARGV[1])
end
return false
