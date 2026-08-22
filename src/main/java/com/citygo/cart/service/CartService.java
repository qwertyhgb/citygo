package com.citygo.cart.service;

import com.citygo.cart.vo.CartItemVO;

import java.util.List;

/**
 * 购物车服务接口（Redis Hash 实现）。
 */
public interface CartService {

    /**
     * 加入购物车：商品有效时数量累加，返回当前购物车商品种数。
     */
    int addItem(Long userId, Long productId, int quantity);

    /**
     * 更新购物车条目数量；quantity=0 时删除该条目。商品需有效。
     */
    void updateItem(Long userId, Long productId, int quantity);

    /**
     * 删除购物车中指定商品条目（幂等）。
     */
    void removeItem(Long userId, Long productId);

    /**
     * 删除一批商品条目（下单后清理已购商品）。
     */
    void removeItems(Long userId, List<Long> productIds);

    /**
     * 清空整个购物车。
     */
    void clear(Long userId);

    /**
     * 查看购物车明细（含实时价格与小计；失效商品标记 offSale）。
     */
    List<CartItemVO> viewCart(Long userId);

}