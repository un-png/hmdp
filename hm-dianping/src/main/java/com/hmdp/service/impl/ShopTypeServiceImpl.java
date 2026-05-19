package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public  Result typeList() {
            //1.从redis查缓存
            List<String> shopTypeJson = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
            //2.存在，直接返回
            if (CollectionUtil.isNotEmpty(shopTypeJson)){
                List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson.toString(), ShopType.class);
                Collections.sort(shopTypes, ((o1, o2) -> o1.getSort()-o2.getSort()));
                return Result.ok(shopTypes);
            }
            //3.不存在，根据id查数据库
            List<ShopType> shopTypes = query().orderByAsc("sort").list();
            //4.数据库不存在，返回错误
            if (CollectionUtil.isEmpty(shopTypes)){
                return Result.fail("店铺类型不存在");
            }
            //转换为 json写入缓存
            List<String> shopTypesJson= JSONUtil.toList(JSONUtil.toJsonStr(shopTypes), String.class);
            //5.存在，写入缓存
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypesJson);
            //6.返回
            return Result.ok(shopTypes);
        }
}
