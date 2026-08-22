package com.citygo.search.repository;

import com.citygo.search.doc.ProductDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 商品搜索仓库。
 *
 * <p>同 {@link ShopSearchRepository}：ES 管索引，MySQL 管主数据与事务。</p>
 */
@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDoc, Long> {

}