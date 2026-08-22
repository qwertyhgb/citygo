package com.citygo.review.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.order.entity.Orders;
import com.citygo.order.enums.OrderStatus;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.review.dto.ReviewCreateRequest;
import com.citygo.review.dto.ReviewReplyRequest;
import com.citygo.review.entity.Review;
import com.citygo.review.mapper.ReviewMapper;
import com.citygo.review.service.ReviewService;
import com.citygo.review.vo.ReviewVO;
import com.citygo.shop.service.ShopBrowseService;
import com.citygo.user.entity.User;
import com.citygo.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评价域服务实现。
 *
 * <p>核心学习点：
 * <ul>
 *   <li><b>评价时机</b>：订单必须为已完成（status=50）才可评价；</li>
 *   <li><b>一单一评防重复</b>：业务先查重 + 唯一索引 uk_order_id 兜底（与领券同套路）；</li>
 *   <li><b>店铺平均分"重算"而非"增量维护"</b>：平均分无法通过"加减一条"维护（会破坏均值），
 *       评价量小时每次 AVG 重算成本可接受；对比销量/月销这类可累加字段用增量（见 Phase 6 的 addMonthlySales）。</li>
 * </ul>
 * </p>
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;
    private final OrdersMapper ordersMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final MerchantService merchantService;
    private final ShopBrowseService shopBrowseService;

    public ReviewServiceImpl(ReviewMapper reviewMapper,
                             OrdersMapper ordersMapper,
                             ShopMapper shopMapper,
                             UserMapper userMapper,
                             MerchantService merchantService,
                             ShopBrowseService shopBrowseService) {
        this.reviewMapper = reviewMapper;
        this.ordersMapper = ordersMapper;
        this.shopMapper = shopMapper;
        this.userMapper = userMapper;
        this.merchantService = merchantService;
        this.shopBrowseService = shopBrowseService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO create(ReviewCreateRequest request, Long currentUserId) {
        // a. 查订单：不存在或非本人 → 404 防探测（同订单详情语义）
        Orders order = ordersMapper.selectById(request.getOrderId());
        if (order == null || !order.getUserId().equals(currentUserId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        // b. 评价时机：仅已完成订单可评价
        if (order.getStatus() != OrderStatus.COMPLETED.getCode()) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        // c. 防重复评价：业务先查重；并发兜底撞唯一索引 uk_order_id
        Long exist = reviewMapper.selectCount(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderId, request.getOrderId()));
        if (exist != null && exist > 0) {
            throw new BizException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        // d. 插入评价：shopId 冗余自订单
        Review review = new Review();
        review.setOrderId(order.getId());
        review.setUserId(currentUserId);
        review.setShopId(order.getShopId());
        review.setRating(request.getRating());
        review.setContent(request.getContent());
        review.setImages(request.getImages());
        review.setStatus(1);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException ex) {
            // 并发下两条同时插入同一订单，唯一索引兜底保证只有一条成功
            throw new BizException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        // e. 店铺评分重算：AVG(可见评价) → 回写 shop.score
        BigDecimal avg = reviewMapper.avgRatingByShop(order.getShopId());
        if (avg != null) {
            shopMapper.update(null, Wrappers.<Shop>lambdaUpdate()
                    .eq(Shop::getId, order.getShopId())
                    .set(Shop::getScore, avg));
        }
        // f. 评分变了 → 失效店铺详情缓存（shopDetail 含 score，缓存必须联动）
        shopBrowseService.evictDetailCache(order.getShopId());
        // g. 返回
        return toVO(review);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO reply(Long reviewId, ReviewReplyRequest request, Long currentUserId) {
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException(ErrorCode.REVIEW_NOT_FOUND);
        }
        // 归属校验：评价所属店铺必须属于当前商家
        Merchant merchant = merchantService.getByUserId(currentUserId);
        Shop shop = shopMapper.selectById(review.getShopId());
        if (merchant == null || shop == null || !shop.getMerchantId().equals(merchant.getId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
        // 更新回复（允许覆盖旧回复）
        review.setMerchantReply(request.getContent());
        review.setReplyTime(LocalDateTime.now());
        reviewMapper.updateById(review);
        return toVO(review);
    }

    @Override
    public PageVO<ReviewVO> listShop(Long shopId, long pageNum, long pageSize) {
        // 校验店铺存在
        if (shopMapper.selectById(shopId) == null) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Review> qw =
                Wrappers.lambdaQuery();
        qw.eq(Review::getShopId, shopId)
                .eq(Review::getStatus, 1)
                .orderByDesc(Review::getId);
        Page<Review> page = reviewMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        return toPageVO(page);
    }

    @Override
    public PageVO<ReviewVO> pageMy(long pageNum, long pageSize, Long currentUserId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Review> qw =
                Wrappers.lambdaQuery();
        qw.eq(Review::getUserId, currentUserId)
                .orderByDesc(Review::getId);
        Page<Review> page = reviewMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        return toPageVO(page);
    }

    // ---------------- 组装 ----------------

    /**
     * 分页转视图：批量补店铺名 + 评价人昵称/头像（避免 N+1）。
     */
    private PageVO<ReviewVO> toPageVO(Page<Review> page) {
        List<Review> records = page.getRecords();
        List<Long> shopIds = records.stream().map(Review::getShopId).distinct().toList();
        List<Long> userIds = records.stream().map(Review::getUserId).distinct().toList();
        Map<Long, Shop> shopMap = shopIds.isEmpty() ? Map.of()
                : shopMapper.selectByIds(shopIds).stream()
                        .collect(Collectors.toMap(Shop::getId, Function.identity()));
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        PageVO<ReviewVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        pageVO.setRecords(records.stream().map(r -> toVO(r, shopMap, userMap)).toList());
        return pageVO;
    }

    private ReviewVO toVO(Review review) {
        Shop shop = review.getShopId() == null ? null : shopMapper.selectById(review.getShopId());
        User user = userMapper.selectById(review.getUserId());
        return toVO(review,
                shop == null ? Map.of() : Map.of(shop.getId(), shop),
                user == null ? Map.of() : Map.of(user.getId(), user));
    }

    private ReviewVO toVO(Review r, Map<Long, Shop> shopMap, Map<Long, User> userMap) {
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setOrderId(r.getOrderId());
        vo.setShopId(r.getShopId());
        Shop shop = shopMap.get(r.getShopId());
        vo.setShopName(shop == null ? null : shop.getShopName());
        vo.setUserId(r.getUserId());
        User user = userMap.get(r.getUserId());
        vo.setNickname(user == null ? null : user.getNickname());
        vo.setAvatar(user == null ? null : user.getAvatar());
        vo.setRating(r.getRating());
        vo.setContent(r.getContent());
        vo.setImages(r.getImages());
        vo.setMerchantReply(r.getMerchantReply());
        vo.setReplyTime(r.getReplyTime());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }

}