if redis.call('get', KEYS[1]) == ARGV[1] then
    -- 如果匹配，则删除锁
    return redis.call('del', KEYS[1])
end
    -- 如果不匹配，返回0表示释放失败
    return 0