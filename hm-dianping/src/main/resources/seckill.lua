--1.参数列表
--1.1优惠券 id
local voucherId = ARGV[1]
--1.2用户 id
local userId = ARGV[2]

--2.数据key
--2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.2订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本逻辑
--3.1判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    --用户已经下过单
    return 2
end

--3.2扣减库存
redis.call('incrby', stockKey, -1)
--3.3记录订单
redis.call('sadd', orderKey, userId)