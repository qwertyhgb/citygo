package com.citygo.search.repository;

import com.citygo.search.doc.ShopDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 店铺搜索仓库。
 *
 * <p>Spring Data ES 仓库负责<b>文档写索引</b>；事务与主数据读写在 MySQL（MyBatis-Plus）。
 * 二者分工：搜索走 ES，事务走 MySQL——ES 是搜索索引，MySQL 是唯一事实源。</p>
 */
@Repository
public interface ShopSearchRepository extends ElasticsearchRepository<ShopDoc, Long> {

}