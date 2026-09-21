package com.citygo.coupon.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.common.redis.RedisLockUtil;
import com.citygo.coupon.dto.CouponCreateRequest;
import com.citygo.coupon.entity.Coupon;
import com.citygo.coupon.entity.UserCoupon;
import com.citygo.coupon.mapper.CouponMapper;
import com.citygo.coupon.mapper.UserCouponMapper;
import com.citygo.coupon.service.CouponService;
import com.citygo.coupon.vo.CouponVO;
import com.citygo.coupon.vo.UserCouponVO;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券域服务实现。
 *
 * <p>核心学习点（并发安全）：
 * <ul>
 *   <li><b>领券防超发</b>：用 {@code UPDATE ... WHERE received_count < total_count} 条件更新，
 *       靠数据库原子性保证不会多发；</li>
 *   <li><b>防重复与限领</b>：Redis 分布式锁 + 业务查重，兼容 per_user_limit 限制；</li>
 *   <li><b>下单核销</b>：条件更新 status=1→2 保幂等；</li>
 *   <li><b>取消退券</b>：条件更新 status=2→1 回退。</li>
 * </ul>
 * </p>
 */
@Service
public class CouponServiceImpl implements CouponService {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final MerchantService merchantService;
    private final ShopService shopService;
    private final RedisLockUtil redisLockUtil;
    private final TransactionTemplate transactionTemplate;

    public CouponServiceImpl(CouponMapper couponMapper,
                             UserCouponMapper userCouponMapper,
                             MerchantService merchantService,
                             ShopService shopService,
                             RedisLockUtil redisLockUtil,
                             PlatformTransactionManager transactionManager) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.merchantService = merchantService;
        this.shopService = shopService;
        this.redisLockUtil = redisLockUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponVO create(CouponCreateRequest request, Long currentUserId) {
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        // 店铺归属校验：商家只能为自家店铺创建商家券
        Long owner = shopService.getMerchantIdOfShop(request.getShopId());
        if (owner == null) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        if (!owner.equals(merchant.getId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
        // 商家只能创建商家券
        if (request.getScope() == null || request.getScope() != 2) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }

        Coupon coupon = new Coupon();
        coupon.setCouponName(request.getCouponName());
        coupon.setType(request.getType());
        coupon.setThresholdAmount(request.getThresholdAmount());
        coupon.setTotalCount(request.getTotalCount());
        coupon.setPerUserLimit(request.getPerUserLimit() == null ? 1 : request.getPerUserLimit());
        coupon.setValidStart(request.getValidStart());
        coupon.setValidEnd(request.getValidEnd());
        coupon.setScope(request.getScope());
        coupon.setShopId(request.getShopId());
        // 类型与优惠字段互斥校验
        if (request.getType() == 1) {
            // 满减券：必须传满减金额，不设折扣率
            if (request.getDiscountAmount() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST);
            }
            coupon.setDiscountAmount(request.getDiscountAmount());
            coupon.setDiscountRate(null);
        } else if (request.getType() == 2) {
            // 折扣券：必须传折扣率，不设满减金额
            if (request.getDiscountRate() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST);
            }
            coupon.setDiscountAmount(null);
            coupon.setDiscountRate(request.getDiscountRate());
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST);
        }
        coupon.setReceivedCount(0);
        coupon.setStatus(1);
        couponMapper.insert(coupon);
        return toCouponVO(coupon);
    }

    @Override
    public List<CouponVO> listPublic(Integer scope, Long shopId) {
        LocalDateTime now = LocalDateTime.now();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Coupon> qw =
                Wrappers.lambdaQuery();
        // 状态可对公众领取 + 在有效期内
        qw.eq(Coupon::getStatus, 1)
                .le(Coupon::getValidStart, now)   // valid_start <= now
                .ge(Coupon::getValidEnd, now);    // valid_end >= now
        if (scope != null) {
            qw.eq(Coupon::getScope, scope);
        }
        if (shopId != null) {
            qw.eq(Coupon::getShopId, shopId);
        }
        qw.orderByDesc(Coupon::getId);
        return couponMapper.selectList(qw).stream().map(this::toCouponVO).toList();
    }

    @Override
    public UserCouponVO claim(Long couponId, Long currentUserId) {
        String lockKey = "citygo:lock:coupon:claim:" + couponId + ":" + currentUserId;
        String token = UUID.randomUUID().toString();
        boolean locked = redisLockUtil.tryLock(lockKey, 5, token);
        if (!locked) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT);
        }
        try {
            return transactionTemplate.execute(txStatus -> {
                LocalDateTime now = LocalDateTime.now();

                // a. 查券模板
                Coupon coupon = couponMapper.selectById(couponId);
                if (coupon == null) {
                    throw new BizException(ErrorCode.COUPON_NOT_FOUND);
                }
                // b. 有效期
                if (now.isBefore(coupon.getValidStart())) {
                    throw new BizException(ErrorCode.COUPON_NOT_STARTED);
                }
                if (now.isAfter(coupon.getValidEnd())) {
                    throw new BizException(ErrorCode.COUPON_EXPIRED);
                }
                // c. 状态
                if (coupon.getStatus() != 1) {
                    throw new BizException(ErrorCode.COUPON_INVALID);
                }
                // d. 业务防重复与限领控制（兼容 per_user_limit >= 1 场景）
                int limit = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
                Long claimedCount = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                        .eq(UserCoupon::getUserId, currentUserId)
                        .eq(UserCoupon::getCouponId, couponId));
                if (claimedCount != null && claimedCount >= limit) {
                    throw new BizException(ErrorCode.COUPON_ALREADY_CLAIMED);
                }
                // e. 超发控制（并发核心）：条件更新，received_count < total_count 才 +1
                int rows = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                        .eq(Coupon::getId, couponId)
                        .apply("received_count < total_count")
                        .setSql("received_count = received_count + 1"));
                if (rows == 0) {
                    throw new BizException(ErrorCode.COUPON_EXHAUSTED);
                }
                // f. 插入用户券
                UserCoupon userCoupon = new UserCoupon();
                userCoupon.setUserId(currentUserId);
                userCoupon.setCouponId(couponId);
                userCoupon.setStatus(1);
                userCoupon.setReceivedTime(now);
                userCoupon.setExpireTime(coupon.getValidEnd());
                userCouponMapper.insert(userCoupon);

                // g. 返回
                return toUserCouponVO(userCoupon, coupon);
            });
        } finally {
            redisLockUtil.unlock(lockKey, token);
        }
    }

    @Override
    public PageVO<UserCouponVO> pageMy(Integer status, long pageNum, long pageSize, Long currentUserId) {
        LocalDateTime now = LocalDateTime.now();
        // 惰性过期：先把已过期未使用的券一次性标记为 status=3
        userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .eq(UserCoupon::getUserId, currentUserId)
                .eq(UserCoupon::getStatus, 1)
                .lt(UserCoupon::getExpireTime, now)
                .set(UserCoupon::getStatus, 3));

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserCoupon> qw =
                Wrappers.lambdaQuery();
        qw.eq(UserCoupon::getUserId, currentUserId);
        if (status != null) {
            qw.eq(UserCoupon::getStatus, status);
        }
        qw.orderByDesc(UserCoupon::getId);
        Page<UserCoupon> page = userCouponMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // 批量关联券模板，避免 N+1
        List<Long> couponIds = page.getRecords().stream()
                .map(UserCoupon::getCouponId).distinct().toList();
        Map<Long, Coupon> couponMap = couponIds.isEmpty() ? Map.of()
                : couponMapper.selectByIds(couponIds).stream()
                        .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        PageVO<UserCouponVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        pageVO.setRecords(page.getRecords().stream()
                .map(uc -> toUserCouponVO(uc, couponMap.get(uc.getCouponId())))
                .toList());
        return pageVO;
    }

    private CouponVO toCouponVO(Coupon c) {
        CouponVO vo = new CouponVO();
        vo.setId(c.getId());
        vo.setCouponName(c.getCouponName());
        vo.setType(c.getType());
        vo.setThresholdAmount(c.getThresholdAmount());
        vo.setDiscountAmount(c.getDiscountAmount());
        vo.setDiscountRate(c.getDiscountRate());
        vo.setTotalCount(c.getTotalCount());
        vo.setReceivedCount(c.getReceivedCount());
        vo.setRemainingCount(c.getTotalCount() - c.getReceivedCount());
        vo.setPerUserLimit(c.getPerUserLimit());
        vo.setValidStart(c.getValidStart());
        vo.setValidEnd(c.getValidEnd());
        vo.setScope(c.getScope());
        vo.setShopId(c.getShopId());
        vo.setStatus(c.getStatus());
        return vo;
    }

    private UserCouponVO toUserCouponVO(UserCoupon uc, Coupon coupon) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(uc.getId());
        vo.setCouponId(uc.getCouponId());
        if (coupon != null) {
            vo.setCouponName(coupon.getCouponName());
            vo.setType(coupon.getType());
            vo.setThresholdAmount(coupon.getThresholdAmount());
            vo.setDiscountAmount(coupon.getDiscountAmount());
            vo.setDiscountRate(coupon.getDiscountRate());
        }
        vo.setExpireTime(uc.getExpireTime());
        vo.setStatus(uc.getStatus());
        return vo;
    }

}