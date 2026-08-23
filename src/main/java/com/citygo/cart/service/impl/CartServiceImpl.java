package com.citygo.cart.service.impl;

import com.citygo.cart.service.CartService;
import com.citygo.cart.vo.CartItemVO;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务实现（Redis Hash）。
 *
 * <p>存储设计：
 * <ul>
 *   <li>key = {@code citygo:cart:{userId}}，类型为 Redis Hash；</li>
 *   <li>field = 商品ID 字符串，value = 数量字符串；</li>
 *   <li>key 设置 TTL 7 天，每次写操作刷新过期时间——购物车是临时数据，
 *       Redis 天然适合，不落库（避免额外数据库表与清理逻辑）。</li>
 * </ul>
 * 使用 {@link StringRedisTemplate} 的 {@code opsForHash()}（HashOperations{@code <String,String,String>}），
 * 零自定义序列化。</p>
 */
@Service
public class CartServiceImpl implements CartService {

    /** 购物车 key 前缀 */
    private static final String CART_KEY_PREFIX = "citygo:cart:";

    /** 购物车 TTL：7 天 */
    private static final Duration CART_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductMapper productMapper;

    public CartServiceImpl(StringRedisTemplate stringRedisTemplate, ProductMapper productMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.productMapper = productMapper;
    }

    @Override
    public int addItem(Long userId, Long productId, int quantity) {
        // 商品必须存在且上架
        requireOnSale(productId);
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        // 数量累加；field/productId/数量都用字符串承载
        hash.increment(key, String.valueOf(productId), quantity);
        // 每次写操作刷新 TTL
        refreshTtl(key);
        return hash.size(key).intValue();
    }

    @Override
    public void updateItem(Long userId, Long productId, int quantity) {
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        if (quantity == 0) {
            // 数量为 0 表示删除该条目
            hash.delete(key, String.valueOf(productId));
        } else {
            requireOnSale(productId);
            hash.put(key, String.valueOf(productId), String.valueOf(quantity));
        }
        refreshTtl(key);
    }

    @Override
    public void removeItem(Long userId, Long productId) {
        stringRedisTemplate.opsForHash().delete(cartKey(userId), String.valueOf(productId));
    }

    @Override
    public void removeItems(Long userId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        for (Long productId : productIds) {
            hash.delete(key, String.valueOf(productId));
        }
    }

    @Override
    public void clear(Long userId) {
        stringRedisTemplate.delete(cartKey(userId));
    }

    @Override
    public List<CartItemVO> viewCart(Long userId) {
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        Map<String, String> entries = hash.entries(key);
        if (entries.isEmpty()) {
            return List.of();
        }
        // 批量查询商品，组装名称/图/实时价格
        List<Long> productIds = entries.keySet().stream().map(Long::valueOf).toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CartItemVO> result = new ArrayList<>();
        entries.forEach((field, value) -> {
            Long productId = Long.valueOf(field);
            int quantity = Integer.parseInt(value);
            CartItemVO vo = new CartItemVO();
            vo.setProductId(productId);
            vo.setQuantity(quantity);
            Product product = productMap.get(productId);
            if (product == null) {
                // 商品已删除（逻辑删除不返回）
                vo.setOffSale(true);
            } else if (product.getStatus() == 0) {
                // 商品已下架：标记失效但仍展示
                vo.setProductName(product.getProductName());
                vo.setCoverImage(product.getCoverImage());
                vo.setPrice(product.getPrice());
                vo.setOffSale(true);
            } else {
                vo.setProductName(product.getProductName());
                vo.setCoverImage(product.getCoverImage());
                vo.setPrice(product.getPrice());
                vo.setOffSale(false);
            }
            if (vo.getPrice() != null) {
                vo.setSubtotal(vo.getPrice().multiply(BigDecimal.valueOf(quantity)));
            }
            result.add(vo);
        });
        return result;
    }

    /**
     * 校验商品存在且上架，否则抛业务异常。
     */
    private void requireOnSale(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == 0) {
            throw new BizException(ErrorCode.PRODUCT_OFF_SHELF);
        }
    }

    private String cartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }

    private void refreshTtl(String key) {
        stringRedisTemplate.expire(key, CART_TTL);
    }

}