package com.citygo.search.config;

import com.citygo.search.doc.ProductDoc;
import com.citygo.search.doc.ShopDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * ES 索引初始化（应用启动时执行）。
 *
 * <p>检查 {@code citygo_shop}/{@code citygo_product} 索引是否存在，不存在则
 * <b>先 create 空索引、再 putMapping 写入映射</b>。分两步的原因：ES 9 中 index mapping
 * 的写入通过 {@code indexOps().putMapping()} 完成，而创建索引本体用 {@code create()}；
 * 映射里含 IK 分析器（@Field 注解配置）与 geo_point 字段映射，必须显式建立，避免用默认映射。</p>
 *
 * <p>索引名统一 {@code citygo_} 前缀，绝不触碰 knowflow_* 索引。</p>
 */
@Component
public class ESIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ESIndexInitializer.class);

    private final ElasticsearchOperations operations;

    public ESIndexInitializer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(ApplicationArguments args) {
        initIndex(ShopDoc.class);
        initIndex(ProductDoc.class);
        log.info("Elasticsearch CityGo 索引初始化完成（citygo_shop / citygo_product）");
    }

    /**
     * 若索引不存在则创建并写映射（IK + geo）。
     */
    private void initIndex(Class<?> clazz) {
        try {
            IndexOperations idx = operations.indexOps(clazz);
            if (Boolean.TRUE.equals(idx.exists())) {
                log.info("ES 索引已存在，跳过创建: {}", idx.getIndexCoordinates().getIndexName());
                return;
            }
            boolean created = idx.create();
            if (!created) {
                log.warn("ES 索引 create 返回 false，可能已存在: {}", idx.getIndexCoordinates().getIndexName());
            }
            boolean mapping = idx.putMapping();
            log.info("ES 索引初始化: {} created={} putMapping={}",
                    idx.getIndexCoordinates().getIndexName(), created, mapping);
        } catch (Exception e) {
            // 索引初始化失败不阻断应用启动（后续写入仍会尝试）
            log.error("ES 索引初始化异常: {}", clazz.getSimpleName(), e);
        }
    }

}