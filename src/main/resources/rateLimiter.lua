local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_time = tonumber(ARGV[2])
local current_time = tonumber(ARGV[3])

local last_tokens = tonumber(redis.call("get", key) or max_tokens)
local last_refreshed = tonumber(redis.call("get", key..":ts") or current_time)

local delta = math.max(0, current_time - last_refreshed)
local refill = math.floor(delta / refill_time)
local filled_tokens = math.min(max_tokens, last_tokens + refill)

local allowed = filled_tokens > 0
if allowed then
    filled_tokens = filled_tokens - 1
end

redis.call("set", key, filled_tokens)
redis.call("set", key..":ts", current_time)

return allowed