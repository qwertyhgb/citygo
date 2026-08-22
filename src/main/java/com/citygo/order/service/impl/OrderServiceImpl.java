package com.citygo.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.address.entity.Address;
import com.citygo.address.service.AddressService;
import com.citygo.cart.service.CartService;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.order.dto.OrderCreateRequest;
import com.citygo.order.dto.OrderItemRequest;
import com.citygo.order.entity.OrderItem;
import com.citygo.order.entity.Orders;
import com.citygo.order.entity.Payment;
import com.citygo.order.enums.OrderStatus;
import com.citygo.order.mapper.OrderItemMapper;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.order.mapper.PaymentMapper;
import com.citygo.order.service.OrderService;
import com.citygo.order.vo.OrderItemVO;
import com.citygo.order.vo.OrderVO;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单域服务实现——全项目最核心的类。
 *
 * <p>设计要点：库存扣减与状态流转都使用"条件原子更新 + 影响行数判断"，
 * 依靠数据库行锁保证并发安全（见各方法注释）。</p>
 */
@Service
public class OrderServiceImpl implements OrderService {

    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final AddressService addressService;
    private final CartService cartService;
    private final MerchantService merchantService;

    public OrderServiceImpl(OrdersMapper ordersMapper,
                            OrderItemMapper orderItemMapper,
                            PaymentMapper paymentMapper,
                            ProductMapper productMapper,
                            ShopMapper shopMapper,
                            AddressService addressService,
                            CartService cartService,
                            MerchantService merchantService) {
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.paymentMapper = paymentMapper;
        this.productMapper = productMapper;
        this.shopMapper = shopMapper;
        this.addressService = addressService;
        this.cartService = cartService;
        this.merchantService = merchantService;
    }

    // ---------------- 下单 ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(OrderCreateRequest request, Long currentUserId) {
        // a. 校验地址存在且属于当前用户
        Address address = addressService.getById(request.getAddressId());
        if (address == null) {
            throw new BizException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(currentUserId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }

        // b. 批量取出商品并校验存在/上架/同一店铺
        List<OrderItemRequest> itemRequests = request.getItems();
        List<Long> productIds = itemRequests.stream().map(OrderItemRequest::getProductId).toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Long shopId = null;
        for (OrderItemRequest item : itemRequests) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            if (product.getStatus() == 0) {
                throw new BizException(ErrorCode.PRODUCT_OFF_SHELF);
            }
            // 一个订单只能属于一个店铺，跨店铺下单直接拒绝
            if (shopId == null) {
                shopId = product.getShopId();
            } else if (!shopId.equals(product.getShopId())) {
                throw new BizException(ErrorCode.ORDER_CROSS_SHOP);
            }
        }

        // c. 计算商品总额（price × quantity 累加，BigDecimal 精确运算）
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemRequest item : itemRequests) {
            Product product = productMap.get(item.getProductId());
            totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // d. 生成全局唯一订单号（雪花号，对外展示）
        String orderNo = IdWorker.getIdStr();

        // e. 插入订单主表：待支付、无优惠、实付=总额，收货信息从 address 快照拷贝
        Orders order = new Orders();
        order.setOrderNo(orderNo);
        order.setUserId(currentUserId);
        order.setShopId(shopId);
        order.setTotalAmount(totalAmount);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        // 快照意义：收货地址后续被用户修改，本订单仍展示下单时的地址
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(address.getProvince() + address.getCity() + address.getDistrict() + address.getDetailAddress());
        order.setRemark(request.getRemark());
        ordersMapper.insert(order);

        // f. 批量插入订单明细（商品名/图/单价均为快照）
        for (OrderItemRequest item : itemRequests) {
            Product product = productMap.get(item.getProductId());
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getProductName());
            orderItem.setProductImage(product.getCoverImage());
            orderItem.setPrice(product.getPrice());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            orderItemMapper.insert(orderItem);
        }

        // g. CAS 扣库存 + 加销量（防超卖核心）
        //    先查再改在并发下会超卖；此处 UPDATE ... WHERE stock >= ? 是数据库原子操作，
        //    行锁保证同一商品同一时刻只有一个下单能扣成功，返回 0 行即库存不足。
        for (OrderItemRequest item : itemRequests) {
            int rows = productMapper.deductStock(item.getProductId(), item.getQuantity());
            if (rows == 0) {
                throw new BizException(ErrorCode.INSUFFICIENT_STOCK);
            }
        }

        // h. 累加店铺月销量
        shopMapper.addMonthlySales(shopId, itemRequests.stream().mapToInt(OrderItemRequest::getQuantity).sum());

        // i. 清掉购物车中已下单的商品
        cartService.removeItems(currentUserId, productIds);

        // j. 返回订单视图
        return toVO(order, true);
    }

    // ---------------- 详情 ----------------

    @Override
    public OrderVO getDetail(Long id, Long currentUserId) {
        Orders order = ordersMapper.selectById(id);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        // 权限：本人，或当前用户是订单店铺的商家
        boolean isOwner = order.getUserId().equals(currentUserId);
        boolean isMerchant = isMerchantOfShop(currentUserId, order.getShopId());
        if (!isOwner && !isMerchant) {
            // 用 404 而非 403，避免暴露订单是否存在的问题（防订单号探测）
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return toVO(order, true);
    }

    // ---------------- 我的订单 ----------------

    @Override
    public PageVO<OrderVO> pageMy(Integer status, long pageNum, long pageSize, Long currentUserId) {
        LambdaQueryWrapper<Orders> qw = Wrappers.lambdaQuery();
        qw.eq(Orders::getUserId, currentUserId);
        if (status != null) {
            qw.eq(Orders::getStatus, status);
        }
        qw.orderByDesc(Orders::getId);
        Page<Orders> page = ordersMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        // 列表页不查明细
        PageVO<OrderVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        pageVO.setRecords(page.getRecords().stream().map(o -> toVO(o, false)).toList());
        return pageVO;
    }

    // ---------------- 取消 ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, String cancelReason, Long currentUserId) {
        // 归属校验：只能取消自己的订单
        Orders order = requireOwnerOrder(id, currentUserId);
        // 状态机：仅待支付可取消
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT.getCode()) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        String reason = cancelReason == null || cancelReason.isBlank() ? "用户取消" : cancelReason;
        // 状态条件更新（乐观锁思想）：WHERE status=10，0 行说明并发下状态已变化
        int rows = ordersMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .eq(Orders::getId, id)
                .eq(Orders::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                .set(Orders::getStatus, OrderStatus.CANCELLED.getCode())
                .set(Orders::getCancelTime, LocalDateTime.now())
                .set(Orders::getCancelReason, reason));
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        // 回补库存：取消回补是"只增不减"方向，直接加回不会超卖
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, id));
        for (OrderItem item : items) {
            productMapper.restoreStock(item.getProductId(), item.getQuantity());
        }
        // 店铺月销回减
        shopMapper.addMonthlySales(order.getShopId(), -items.stream().mapToInt(OrderItem::getQuantity).sum());
    }

    // ---------------- 支付 ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long id, Long currentUserId) {
        // 归属校验
        Orders order = requireOwnerOrder(id, currentUserId);
        // 幂等：状态条件更新，WHERE status=10
        int rows = ordersMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .eq(Orders::getId, id)
                .eq(Orders::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                .set(Orders::getStatus, OrderStatus.PAID.getCode())
                .set(Orders::getPaidTime, LocalDateTime.now()));
        if (rows == 0) {
            // 未走到 20：查当前状态，若已是 20 则视为重复支付请求，幂等成功；其他状态不允许支付
            Orders current = ordersMapper.selectById(id);
            if (current != null && current.getStatus() == OrderStatus.PAID.getCode()) {
                return;
            }
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }

        // 插入支付记录（模拟支付）——唯一索引 uk_payment_no + 本事务保证不重复插入
        Payment payment = new Payment();
        payment.setPaymentNo(IdWorker.getIdStr());
        payment.setOrderId(order.getId());
        payment.setUserId(currentUserId);
        payment.setAmount(order.getPayAmount());
        payment.setPayMethod(3); // 3 模拟支付
        payment.setStatus(2);    // 2 支付成功
        payment.setPaidTime(LocalDateTime.now());
        paymentMapper.insert(payment);
    }

    // ---------------- 商家订单列表 ----------------

    @Override
    public PageVO<OrderVO> pageMerchant(Long shopId, Integer status, long pageNum, long pageSize, Long currentUserId) {
        // 解析当前商家
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        LambdaQueryWrapper<Orders> qw = Wrappers.lambdaQuery();
        if (shopId != null) {
            // 指定店铺必须归属当前商家
            requireOwnedShop(shopId, merchant.getId());
            qw.eq(Orders::getShopId, shopId);
        } else {
            // 未指定店铺：限定为当前商家名下所有店铺的订单
            List<Long> myShopIds = shopMapper.selectList(
                            Wrappers.<Shop>lambdaQuery().eq(Shop::getMerchantId, merchant.getId()))
                    .stream().map(Shop::getId).toList();
            if (myShopIds.isEmpty()) {
                PageVO<OrderVO> empty = new PageVO<>();
                empty.setRecords(List.of());
                empty.setTotal(0);
                empty.setPageNum(pageNum);
                empty.setPageSize(pageSize);
                return empty;
            }
            qw.in(Orders::getShopId, myShopIds);
        }
        if (status != null) {
            qw.eq(Orders::getStatus, status);
        }
        qw.orderByDesc(Orders::getId);
        Page<Orders> page = ordersMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        PageVO<OrderVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        pageVO.setRecords(page.getRecords().stream().map(o -> toVO(o, false)).toList());
        return pageVO;
    }

    // ---------------- 商家状态流转 ----------------

    /**
     * 状态流转统一实现：
     * 查订单 → 归属校验（必须是自己店铺的订单）→ 条件更新（WHERE status=from）→
     * 0 行抛 ORDER_STATUS_INVALID。用条件更新保证并发下只允许唯一一次正确流转。
     */
    private void transition(Long orderId, Long currentUserId, OrderStatus from, OrderStatus to, Integer targetUserId) {
        Orders order = ordersMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        // 商家归属校验：当前用户必须是订单店铺的商家所有者
        Merchant merchant = merchantService.getByUserId(currentUserId);
        Shop shop = shopMapper.selectById(order.getShopId());
        if (merchant == null || shop == null || !shop.getMerchantId().equals(merchant.getId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
        int rows = ordersMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .eq(Orders::getId, orderId)
                .eq(Orders::getStatus, from.getCode())
                .set(Orders::getStatus, to.getCode())
                .set(targetUserId != null, Orders::getCompletedTime, LocalDateTime.now()));
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
    }

    @Override
    public void accept(Long id, Long currentUserId) {
        transition(id, currentUserId, OrderStatus.PAID, OrderStatus.ACCEPTED, null);
    }

    @Override
    public void deliver(Long id, Long currentUserId) {
        transition(id, currentUserId, OrderStatus.ACCEPTED, OrderStatus.DELIVERING, null);
    }

    @Override
    public void complete(Long id, Long currentUserId) {
        transition(id, currentUserId, OrderStatus.DELIVERING, OrderStatus.COMPLETED, 1);
    }

    // ---------------- 工具方法 ----------------

    /**
     * 按ID查询订单并校验归属（仅本人可操作），不存在或非本人抛 ORDER_NOT_FOUND。
     */
    private Orders requireOwnerOrder(Long id, Long currentUserId) {
        Orders order = ordersMapper.selectById(id);
        if (order == null || !order.getUserId().equals(currentUserId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 校验店铺归属当前商家，否则抛越权。
     */
    private void requireOwnedShop(Long shopId, Long merchantId) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || !shop.getMerchantId().equals(merchantId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
    }

    /**
     * 判断当前用户是否为某店铺的商家所有者。
     */
    private boolean isMerchantOfShop(Long userId, Long shopId) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return false;
        }
        Merchant merchant = merchantService.getByUserId(userId);
        return merchant != null && merchant.getId().equals(shop.getMerchantId());
    }

    /**
     * 订单 → 视图对象；includeItems=true 时附带明细。
     */
    private OrderVO toVO(Orders order, boolean includeItems) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setShopId(order.getShopId());
        // 补店铺名（用于列表/详情展示）
        Shop shop = shopMapper.selectById(order.getShopId());
        vo.setShopName(shop == null ? null : shop.getShopName());
        vo.setStatus(order.getStatus());
        OrderStatus status = OrderStatus.fromCode(order.getStatus());
        vo.setStatusDesc(status == null ? "未知状态" : status.getDesc());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddress(order.getReceiverAddress());
        vo.setRemark(order.getRemark());
        vo.setCreateTime(order.getCreateTime());
        vo.setPaidTime(order.getPaidTime());
        vo.setCompletedTime(order.getCompletedTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCancelReason(order.getCancelReason());
        if (includeItems) {
            vo.setItems(orderItemMapper.selectList(
                            Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()))
                    .stream().map(this::toItemVO).toList());
        }
        return vo;
    }

    /**
     * 明细 → 视图对象。
     */
    private OrderItemVO toItemVO(OrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        vo.setProductId(item.getProductId());
        vo.setProductName(item.getProductName());
        vo.setProductImage(item.getProductImage());
        vo.setPrice(item.getPrice());
        vo.setQuantity(item.getQuantity());
        vo.setTotalAmount(item.getTotalAmount());
        return vo;
    }

}