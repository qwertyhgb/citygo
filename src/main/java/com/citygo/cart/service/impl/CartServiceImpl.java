package com.citygo.cart.service.impl;

import com.citygo.cart.service.CartService;
import com.citygo.cart.vo.CartItemVO;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务实现 - 面向新手的详细说明版（Redis Hash 存储）。
 *
 * <h3>这个类是干什么的？</h3>
 * 购物车是"用户想买但还没买"的临时收藏夹：加购、改数量、删条目、清空、查看。
 * 它是交易链路的"第一站"——用户从商品页把东西放进购物车，结算时才生成订单。
 *
 * <h3>核心设计思想</h3>
 * <ol>
 *   <li><b>Redis 存储，不落库</b>：购物车是临时数据（丢了最多重新加），
 *       不值得建表 + 清理任务。Redis 的 TTL 天然帮忙兜底（7 天自动消失）。</li>
 *   <li><b>Hash 结构贴合购物车模型</b>：一个用户一个 Hash
 *       （key={@code citygo:cart:{userId}}），field=商品ID、value=数量。
 *       Hash 的 increment 命令支持<b>原子累加</b>——加购不用"读-改-写"三步
 *       （有竞态），一条命令搞定。</li>
 *   <li><b>数量上限与库存校验</b>：加购/改数量都要和实时库存比对，
 *       超了就抛 {@code INSUFFICIENT_STOCK}（409）——把病挡在结算之前。</li>
 *   <li><b>失效商品标记不删除</b>：商品被删/下架的条目不悄悄消失，
 *       而是返回 offSale=true 让前端提示"已失效"，用户感知透明。</li>
 *   <li><b>TTL 滑动续期</b>：每次写操作刷新 7 天 TTL——
 *       "常买的用户购物车一直在，7 天没动的临时数据自动废弃"。</li>
 * </ol>
 *
 * <h3>存储结构速记</h3>
 * <pre>
 * Redis Hash:
 *   key   = citygo:cart:10001          ← 一个用户一个 key
 *   field = "1234" → value = "2"       ← 商品 1234 买了 2 件
 *   field = "5678" → value = "1"       ← 商品 5678 买了 1 件
 *   TTL   = 7 天（每次写操作刷新）
 * </pre>
 *
 * <h3>数据流转路径</h3>
 * <pre>
 * 加购：  Controller → addItem → 校验商品上架+拿库存 → HINCRBY 原子累加
 *         → 超库存则回滚 → 刷新 TTL → 返回当前商品种数
 * 查看：  Controller → viewCart → HGETALL 取出全部条目 → 批量查商品（防 N+1）
 *         → 组装 VO（下架/删除标记 offSale，单价实时查库）→ 返回
 * 结算后：Order 模块 → removeItems → HDEL 批量删除已购商品（清出已买的东西）
 * </pre>
 *
 * @see CartService 接口契约（各方法的语义定义处）
 * @see CartItemVO  购物车条目视图（含 offSale 失效标记）
 */
@Service
public class CartServiceImpl implements CartService {

    /** 日志对象：记录 Redis 脏数据等不影响主流程的异常 */
    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

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

    /**
     * 加入购物车（数量累加）。
     *
     * <p><b>流程</b>：校验商品有效 → 原子累加数量 → 超库存则回滚并抛异常 → 刷新 TTL。</p>
     *
     * <p><b>为什么返回"种数"？</b>前端加购后要显示购物车角标（有几种商品），
     * Hash 的 size 就是 field 数量，一条命令拿到底。</p>
     *
     * @param userId    当前登录用户 ID（由 Controller 从登录态解析，不信任前端传参）
     * @param productId 要加购的商品 ID
     * @param quantity  本次加购数量（>=1，Controller 已用 @Min 校验）
     * @return 加购后购物车里的商品种数（供前端角标展示）
     * @throws BizException PRODUCT_NOT_FOUND / PRODUCT_OFF_SHELF / INSUFFICIENT_STOCK
     */
    @Override
    public int addItem(Long userId, Long productId, int quantity) {
        // 商品必须存在且上架，顺便拿到 stock 用于加购上限校验
        Product product = requireOnSale(productId);
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        String field = String.valueOf(productId);
        // 先原子累加；increment 返回值 = 累加后该商品在购物车里的总数量。
        // 为什么"先加后查"而不是"先查后加"？HGET 查 + 判断 + HINCRBY 加是三步非原子操作，
        // 并发下两个请求可能同时读到 3 件而都加成功导致超库存；先加后判断则写入与判断原子衔接。
        Long total = hash.increment(key, field, quantity);
        // 总数量超过库存 → 回滚本次累加并抛异常（库存为实时快照，足够拦截"极端加购"场景）
        if (total > product.getStock()) {
            Long rolledBack = hash.increment(key, field, -quantity);
            if (rolledBack <= 0) {
                hash.delete(key, field);
            }
            throw new BizException(ErrorCode.INSUFFICIENT_STOCK);
        }
        // 每次写操作刷新 TTL
        refreshTtl(key);
        return hash.size(key).intValue();
    }

    /**
     * 更新购物车条目数量（PATCH 语义：只改数量一个字段）。
     *
     * <p><b>quantity=0 即"删除"</b>：复用同一个接口，前端把数量改到 0
     * 就删掉该条目，不必单独发删除请求；删除分支<b>不校验商品存在</b>
     * （已失效/已下架的商品也能顺利移除）。</p>
     *
     * @param userId    当前登录用户 ID
     * @param productId 要更新的商品 ID
     * @param quantity  目标数量（>=0，0 表示删除该条目）
     * @throws BizException PRODUCT_NOT_FOUND / PRODUCT_OFF_SHELF / INSUFFICIENT_STOCK（目标数量超库存）
     */
    @Override
    public void updateItem(Long userId, Long productId, int quantity) {
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        if (quantity == 0) {
            // 数量为 0 表示删除该条目（删除不校验商品存在，已失效商品也能删）
            hash.delete(key, String.valueOf(productId));
        } else {
            // 直接覆盖数量（非累加）：先校验商品有效，再校验目标数量不超过库存
            Product product = requireOnSale(productId);
            if (quantity > product.getStock()) {
                throw new BizException(ErrorCode.INSUFFICIENT_STOCK);
            }
            hash.put(key, String.valueOf(productId), String.valueOf(quantity));
        }
        refreshTtl(key);
    }

    /**
     * 删除购物车中指定商品条目（幂等）。
     *
     * <p>商品不在购物车里时 HDEL 什么都不删也不报错——"想删就删，删不掉也无妨"，
     * 天然幂等，前端重复点击也不会出问题。</p>
     */
    @Override
    public void removeItem(Long userId, Long productId) {
        stringRedisTemplate.opsForHash().delete(cartKey(userId), String.valueOf(productId));
    }

    /**
     * 批量删除条目（下单结算后清理已购商品，由订单模块调用）。
     *
     * <p>一次 HDEL 批量删除多个 field，替代循环逐条删——下单清理是高频路径，
     * N 次网络往返压成 1 次。</p>
     */
    @Override
    public void removeItems(Long userId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        // HDEL 支持多个 field：一次网络往返替代循环逐条删除（下单后清理是高频路径）
        Object[] fields = productIds.stream().map(String::valueOf).toArray();
        hash.delete(cartKey(userId), fields);
    }

    /**
     * 清空购物车：直接删除整个 key（整个 Redis Hash 一起消失）。
     */
    @Override
    public void clear(Long userId) {
        stringRedisTemplate.delete(cartKey(userId));
    }

    /**
     * 查看购物车明细。
     *
     * <p><b>三个要点</b>（面试常聊）：
     * <ul>
     *   <li><b>价格实时查库</b>：Redis 里只存"商品ID + 数量"，名称/图/单价
     *       展示时现查 product 表——商品改价后购物车立刻显示新价格，
     *       不会出现"加购时的旧价"残留；</li>
     *   <li><b>防 N+1</b>：全部条目一次 selectByIds 批量查回，组装时用 Map 查表，
     *       不逐条查库；</li>
     *   <li><b>失效标记</b>：已删除（查不到）或已下架的商品返回 offSale=true，
     *       而不是悄悄消失——已下架的仍展示名称/图/价（数据还在），
     *       已删除的无数据可展示（只标记失效）。</li>
     * </ul>
     *
     * @return 购物车条目列表（空购物车返回空列表）
     */
    @Override
    public List<CartItemVO> viewCart(Long userId) {
        HashOperations<String, String, String> hash = stringRedisTemplate.opsForHash();
        String key = cartKey(userId);
        Map<String, String> entries = hash.entries(key);
        if (entries.isEmpty()) {
            return List.of();
        }
        // 防御解析：field/value 正常都由本服务写入（数字字符串），但 Redis 是共享基础组件，
        // 若被外部污染成非数字，解析失败仅跳过该条目并记日志，不拖垮整个购物车接口。
        Map<Long, Integer> quantityMap = new HashMap<>();
        entries.forEach((field, value) -> {
            try {
                quantityMap.put(Long.parseLong(field), Integer.parseInt(value));
            } catch (NumberFormatException e) {
                log.warn("购物车存在脏数据，已跳过该条目 key={}, field={}, value={}", key, field, value);
            }
        });
        if (quantityMap.isEmpty()) {
            return List.of();
        }
        // 批量查询商品，组装名称/图/实时价格（防 N+1，一次 selectByIds 代替逐条查询）
        Map<Long, Product> productMap = productMapper.selectByIds(new ArrayList<>(quantityMap.keySet())).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CartItemVO> result = new ArrayList<>();
        quantityMap.forEach((productId, quantity) -> {
            CartItemVO vo = new CartItemVO();
            vo.setProductId(productId);
            vo.setQuantity(quantity);
            Product product = productMap.get(productId);
            // 失效 = 商品已删除（逻辑删除，查不到）或已下架。原三分支合并后：
            // 已下架（且存在）仍展示名称/图/价供前端提示；已删除则查无商品，无数据可展示。
            boolean offSale = product == null || Integer.valueOf(0).equals(product.getStatus());
            vo.setOffSale(offSale);
            if (product != null) {
                vo.setProductName(product.getProductName());
                vo.setCoverImage(product.getCoverImage());
                vo.setPrice(product.getPrice());
            }
            if (vo.getPrice() != null) {
                vo.setSubtotal(vo.getPrice().multiply(BigDecimal.valueOf(quantity)));
            }
            result.add(vo);
        });
        return result;
    }

    /**
     * 校验商品存在且上架，返回商品实体（调用方可拿到 stock 做数量上限校验）。
     *
     * @throws BizException PRODUCT_NOT_FOUND（商品不存在）/ PRODUCT_OFF_SHELF（已下架）
     */
    private Product requireOnSale(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (Integer.valueOf(0).equals(product.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_OFF_SHELF);
        }
        return product;
    }

    /** 拼接当前用户的购物车 key：citygo:cart:{userId}（citygo: 是项目 Redis key 规范前缀） */
    private String cartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }

    /**
     * 刷新购物车 key 的 TTL（滑动过期）。
     *
     * <p>只在写操作后调用：常活跃的购物车一直续 7 天，长期不动的自然过期——
     * 相当于"活跃用户购物车永不过期，僵尸购物车自动清理"，无需后台定时任务。</p>
     */
    private void refreshTtl(String key) {
        stringRedisTemplate.expire(key, CART_TTL);
    }

}