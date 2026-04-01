package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST_KEY;

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


    @Override
    public Result queryList() {
//        1.从redis中获取店铺类型
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(CACHE_SHOP_LIST_KEY, 0, -1);
//       2.判断是否命中
        if(CollectionUtil.isNotEmpty(shopTypeList)) {
//            2.0 如果为空对象则直接返回，防止缓存穿透
            if(StrUtil.isBlank(shopTypeList.get(0))) {
                return Result.fail("列表信息为空");
            }
//            2.1命中则转换类型并返回
            List<ShopType> typeList = new ArrayList<>();
            for(String json : shopTypeList) {
                ShopType type = JSONUtil.toBean(json, ShopType.class);
                typeList.add(type);
            }
            return Result.ok(typeList);
        }
//        3.未命中查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
//        3.1数据库中不存在
        if(CollectionUtil.isEmpty(typeList)) {
//            3.2添加空对象到redis，解决缓存穿透
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_LIST_KEY, CollectionUtil.newArrayList(""));
            stringRedisTemplate.expire(CACHE_SHOP_LIST_KEY,CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("列表信息不存在");
        }
//        3.3. 数据库中存在则转换类型
        List<String> shopTypeLists = new ArrayList<>();
        for(ShopType json : typeList) {
            String type = JSONUtil.toJsonStr(json);
            shopTypeLists.add(type);
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_LIST_KEY, shopTypeLists);
        return Result.ok(typeList);
    }
}
