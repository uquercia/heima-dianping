-- Lua 脚本
-- 1. 取出 KEYS 中的数据
local stockKey = KEYS[1]  -- 对应 keyList 的第一个元素
local voucherOrderKey = KEYS[2]  -- 对应 keyList 的第二个元素

-- 2. 取出 ARGV 中的参数
local userId = ARGV[1]

-- 库存不足给 2
if (tonumber(redis.call('get',stockKey))<=0)then
    return 2
end
-- 非一人一单给 1
if (redis.call('sismember', voucherOrderKey,userId)==1)then
    return 1
end
-- 并且同步扣减库存
redis.call('incrby',stockKey,-1)
-- 插入用户
redis.call('sadd',voucherOrderKey,userId)
-- 成功返回0
return 0
