package com.citygo.merchant.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.merchant.dto.ShopCreateRequest;
import com.citygo.merchant.dto.ShopOpenStatusRequest;
import com.citygo.merchant.dto.ShopUpdateRequest;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import com.citygo.merchant.vo.ShopVO;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 店铺域服务实现。
 *
 * <p>所有店铺写操作都以"当前用户即商家"为前提，并做归属校验：
 * 只能创建/修改属于自己（merchantId 匹配）的店铺，否则视为越权返回 403。</p>
 */
@Service
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;
    private final MerchantService merchantService;

    public ShopServiceImpl(ShopMapper shopMapper, MerchantService merchantService) {
        this.shopMapper = shopMapper;
        this.merchantService = merchantService;
    }

    /**
     * 按当前登录用户ID解析其商家身份；非商家抛 MERCHANT_NOT_FOUND。
     */
    private Merchant requireMerchant(Long currentUserId) {
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    /**
     * 查询店铺并做归属校验：不存在抛 SHOP_NOT_FOUND，非本人商店铺抛 UNAUTHORIZED_OPERATION。
     */
    private Shop requireOwnedShop(Long shopId, Long merchantId) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        if (!shop.getMerchantId().equals(merchantId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
        return shop;
    }

    @Override
    public ShopVO create(ShopCreateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Shop shop = new Shop();
        shop.setMerchantId(merchant.getId());
        shop.setShopName(request.getShopName());
        shop.setCity(request.getCity());
        shop.setDistrict(request.getDistrict());
        shop.setAddress(request.getAddress());
        shop.setLongitude(request.getLongitude());
        shop.setLatitude(request.getLatitude());
        shop.setOpenTime(request.getOpenTime());
        shop.setCloseTime(request.getCloseTime());
        shop.setNotice(request.getNotice());
        // 新店铺默认：营业中、评分 0、月销量 0、正常状态
        shop.setOpenStatus(1);
        shop.setScore(BigDecimal.ZERO);
        shop.setMonthlySales(0);
        shop.setStatus(1);
        shopMapper.insert(shop);
        return toVO(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 店铺信息被修改后，立即失效对应店铺详情缓存（与 ShopBrowseServiceImpl.getDetail 的 @Cacheable 联动），
    // 下次访问用户端详情会重新查库回填，避免读到旧数据。
    @CacheEvict(cacheNames = "shopDetail", key = "#id")
    public ShopVO update(Long id, ShopUpdateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Shop shop = requireOwnedShop(id, merchant.getId());

        if (StringUtils.hasText(request.getShopName())) {
            shop.setShopName(request.getShopName());
        }
        if (StringUtils.hasText(request.getDescription())) {
            shop.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getCity())) {
            shop.setCity(request.getCity());
        }
        if (StringUtils.hasText(request.getDistrict())) {
            shop.setDistrict(request.getDistrict());
        }
        if (StringUtils.hasText(request.getAddress())) {
            shop.setAddress(request.getAddress());
        }
        if (request.getLongitude() != null) {
            shop.setLongitude(request.getLongitude());
        }
        if (request.getLatitude() != null) {
            shop.setLatitude(request.getLatitude());
        }
        if (request.getOpenTime() != null) {
            shop.setOpenTime(request.getOpenTime());
        }
        if (request.getCloseTime() != null) {
            shop.setCloseTime(request.getCloseTime());
        }
        if (StringUtils.hasText(request.getNotice())) {
            shop.setNotice(request.getNotice());
        }
        shopMapper.updateById(shop);
        return toVO(shop);
    }

    @Override
    public void updateOpenStatus(Long id, ShopOpenStatusRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Shop shop = requireOwnedShop(id, merchant.getId());
        shop.setOpenStatus(request.getOpenStatus());
        shopMapper.updateById(shop);
    }

    @Override
    public List<ShopVO> listMy(Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        return shopMapper.selectList(
                        Wrappers.<Shop>lambdaQuery()
                                .eq(Shop::getMerchantId, merchant.getId())
                                .orderByDesc(Shop::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public Long getMerchantIdOfShop(Long shopId) {
        Shop shop = shopMapper.selectById(shopId);
        return shop == null ? null : shop.getMerchantId();
    }

    /**
     * 店铺实体 → 视图对象。
     */
    private ShopVO toVO(Shop shop) {
        ShopVO vo = new ShopVO();
        vo.setId(shop.getId());
        vo.setMerchantId(shop.getMerchantId());
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