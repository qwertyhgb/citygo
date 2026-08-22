package com.citygo.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.util.List;

/**
 * 通用分页视图对象。
 *
 * <p>把 MyBatis-Plus 的 {@link Page} 转成不含任何 MP 依赖的轻量结构返回给前端，
 * 避免把 MP 的内部实现暴露给调用方，也更易序列化。
 * 仅返回 records / total / pageNum / pageSize 四个稳定字段。</p>
 *
 * @param <T> 分页记录类型
 */
@Data
public class PageVO<T> {

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private long pageNum;

    /** 每页条数 */
    private long pageSize;

    public PageVO() {
    }

    /**
     * 由 MP 分页结果构造视图对象。
     */
    public PageVO(Page<T> page) {
        this.records = page.getRecords();
        this.total = page.getTotal();
        this.pageNum = page.getCurrent();
        this.pageSize = page.getSize();
    }

}