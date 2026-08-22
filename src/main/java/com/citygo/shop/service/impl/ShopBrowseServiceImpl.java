package com.citygo.shop.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.shop.dto.ShopBrowseQuery;
import com.citygo.shop.service.ShopBrowseService;
import com.citygo.shop.vo.ShopBrowseVO;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户端店铺浏览服务实现。
 *
 * <p>只暴露 status=1（正常营业状态）的店铺；排序字段通过 switch 白名单
 * 映射为 LambdaQueryWrapper 的方法引用，绝不把用户输入拼进 SQL，
 * 防止 SQL 注入。</p>
 */
@Service
public class ShopBrowseServiceImpl implements ShopBrowseService {

    private final ShopMapper shopMapper;

    public ShopBrowseServiceImpl(ShopMapper shopMapper) {
        this.shopMapper = shopMapper;
    }

    @Override
    public PageVO<ShopBrowseVO> page(ShopBrowseQuery query) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Shop> qw =
                Wrappers.lambdaQuery();
        // 只查正常状态店铺
        qw.eq(Shop::getStatus, 1);
        if (StringUtils.hasText(query.getCity())) {
            qw.eq(Shop::getCity, query.getCity());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            qw.like(Shop::getShopName, query.getKeyword());
        }
        // 排序白名单映射：排序字段必须白名单映射，防止 SQL 注入
        String sort = query.getSort() == null ? "default" : query.getSort();
        switch (sort) {
            case "score" -> qw.orderByDesc(Shop::getScore).orderByDesc(Shop::getId);
            case "sales" -> qw.orderByDesc(Shop::getMonthlySales).orderByDesc(Shop::getId);
            default -> qw.orderByDesc(Shop::getId);
        }

        Page<Shop> page = shopMapper.selectPage(
                new Page<>(query.getPageNum(), clampPageSize(query.getPageSize())), qw);
        PageVO<ShopBrowseVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        pageVO.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return pageVO;
    }

    /**
     * 店铺详情缓存：key = shopDetail::{id}。
     *
     * <p>详情属"读多写少"场景，命中后不再查库。缓存与修改的联动见商家侧
     * {@link com.citygo.merchant.service.impl.ShopServiceImpl#update} 上的 {@code @CacheEvict}：
     * 一旦店铺被修改，对应 {@code shopDetail::{id}} 立即失效，下次访问重新查库回填，保证一致性。</p>
     */
    @Override
    @Cacheable(cacheNames = "shopDetail", key = "#id")
    public ShopBrowseVO getDetail(Long id) {
        Shop shop = shopMapper.selectById(id);
        // 不存在或已禁用都视为不存在
        if (shop == null || shop.getStatus() == 0) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        return toVO(shop);
    }

    /**
     * 校验店铺存在且正常营业（供"店铺内商品"等组合查询使用），不满足抛 404。
     */
    @Override
    public void requireEnabledShop(Long shopId) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || shop.getStatus() == 0) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
    }

    /**
     * 失效店铺详情缓存（评价改分后主动同步失效，见 ReviewServiceImpl 评分联动）。
     */
    @Override
    @CacheEvict(cacheNames = "shopDetail", key = "#shopId")
    public void evictDetailCache(Long shopId) {
        // 方法体为空：仅靠 @CacheEvict 失效 shopDetail::{shopId}
    }

    /**
     * 限制每页条数上限（最大 50），防止一次拉取过多数据。
     */
    private long clampPageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), 50);
    }

    /**
     * 店铺实体 → 浏览视图对象。
     */
    private ShopBrowseVO toVO(Shop shop) {
        ShopBrowseVO vo = new ShopBrowseVO();
        vo.setId(shop.getId());
        vo.setShopName(shop.getShopName());
        vo.setLogo(shop.getLogo());
        vo.setDescription(shop.getDescription());
        vo.setProvince(shop.getProvince());
        vo.setCity(shop.getCity());
        vo.setDistrict(shop.getDistrict());
        vo.setAddress(shop.getAddress());
        vo.setLongitude(shop.getLongitude());
        vo.setLatitude(shop.getLatitude());
        vo.setScore(shop.getScore());
        vo.setMonthlySales(shop.getMonthlySales());
        vo.setOpenStatus(shop.getOpenStatus());
        vo.setOpenTime(shop.getOpenTime());
        vo.setCloseTime(shop.getCloseTime());
        vo.setNotice(shop.getNotice());
        vo.setStatus(shop.getStatus());
        vo.setCreateTime(shop.getCreateTime());
        return vo;
    }

}