package com.citygo.order.service;

import com.citygo.common.page.PageVO;
import com.citygo.order.dto.OrderCreateRequest;
import com.citygo.order.vo.OrderVO;

/**
 * 订单域服务接口。
 */
public interface OrderService {

    /**
     * 下单（含库存 CAS 扣减、店铺月销累加、购物车清理）。
     */
    OrderVO create(OrderCreateRequest request, Long currentUserId);

    /**
     * 订单详情（含明细；权限：本人或订单店铺的商家，否则 404 防探测）。
     */
    OrderVO getDetail(Long id, Long currentUserId);

    /**
     * 我的订单分页（可选状态筛选，按 id 倒序）。
     */
    PageVO<OrderVO> pageMy(Integer status, long pageNum, long pageSize, Long currentUserId);

    /**
     * 取消订单（仅待支付可取消；回补库存、回减店铺月销）。
     */
    void cancel(Long id, String cancelReason, Long currentUserId);

    /**
     * 模拟支付（幂等：已支付重复支付仍成功，不重复插入 payment）。
     */
    void pay(Long id, Long currentUserId);

    /**
     * 商家订单分页（可选店铺【必校验归属】/状态筛选）。
     */
    PageVO<OrderVO> pageMerchant(Long shopId, Integer status, long pageNum, long pageSize, Long currentUserId);

    /**
     * 商家接单（20 → 30）。
     */
    void accept(Long id, Long currentUserId);

    /**
     * 商家配送（30 → 40）。
     */
    void deliver(Long id, Long currentUserId);

    /**
     * 商家完成（40 → 50，写 completed_time）。
     */
    void complete(Long id, Long currentUserId);

}