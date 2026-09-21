package com.citygo.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.order.entity.OrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单明细 Mapper，对应 {@code order_item} 表。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 批量插入订单明细：一条多值 INSERT 代替循环单条 insert，减少 N 次网络往返。
     *
     * <p>说明：
     * <ul>
     *   <li>主键为雪花 ID（无 AUTO_INCREMENT），{@code BaseMapper} 的 ID 自动填充仅对
     *       单条 insert 生效，故由调用方通过 {@code IdWorker.getId()} 预先填充 {@code id}；</li>
     *   <li>{@code create_time} / {@code update_time} / {@code deleted} 依赖数据库默认值
     *       （见 V1 建表），SQL 中不显式插入。</li>
     * </ul>
     *
     * @param items 明细列表（须已填充雪花 id）
     * @return 插入行数
     */
    @Insert("<script>" +
            "INSERT INTO order_item (id, order_id, product_id, product_name, product_image, price, quantity, total_amount) VALUES " +
            "<foreach collection='items' item='it' separator=','>" +
            "(#{it.id}, #{it.orderId}, #{it.productId}, #{it.productName}, #{it.productImage}, #{it.price}, #{it.quantity}, #{it.totalAmount})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("items") List<OrderItem> items);

}