package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {


    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 普通缓存
    public void set(String key, Object value, Long time, TimeUnit  unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time , unit);
    }

    /**
     * 逻辑过期缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit  unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public  <R, ID> R queryWithPassThrough(String keyPrefix, ID id,Class<R> type , Function<ID, R> dbFallback ,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.存在，直接返回
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //3.判断redis命中的值是否为空
        if (json != null){
            return null;
        }
        //4.不存在，根据id查数据库
        R r = dbFallback.apply(id);
        //5.数据库不存在，返回错误
        if (r== null){
            //将空值写入缓存解决缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入缓存
        this.set(key, r, time, unit );
        //7.返回
        return r;
    }
    //线程池
    private static final ExecutorService CACHE_THREAD_POOL = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期缓  存缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public  <R, ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type ,Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1.从redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.存在，直接返回
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //3.命中 先把json转为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期

        //4.1未过期,直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //4.2已过期，缓存重建
        //5.缓存重建
        //5.1获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lock);
        if (isLock){
            //5.2锁成功,开启独立线程，实现缓存重建
            CACHE_THREAD_POOL.submit(() -> {
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(CACHE_SHOP_KEY + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lock);
                }
            });
        }
        //5.3失败，返回过期的数据
        return r;
    }

//    /**
//     * 缓存击穿互斥锁
//     * @param id
//     * @return
//     */
//    private Shop queryWithMutex(Long id) {
//        //1.从redis查缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.存在，直接返回
//        if (StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //3.判断redis命中的值是否为空
//        if (shopJson != null){
//            return null;
//        }
//        //4.实现缓存重建
//        //4.1获取互斥锁
//        String lock = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lock);
//            if (!isLock){
//                //等待重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //4.2锁成功，根据id查询数据库
//            shop = getById(id);
//            //模拟重建延时
//            Thread.sleep(200);
//            //5.数据库不存在，返回错误
//            if (shop== null){
//                //将空值写入缓存解决缓存穿透
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //6.存在，写入缓存
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
//            //释放锁
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            unLock(lock);
//        }
//        //7.返回
//        return shop;
//    }

    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue( flag);
    }
    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
