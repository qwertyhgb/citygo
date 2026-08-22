package com.citygo.search.service.impl;

import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Shop;
import com.citygo.search.doc.ShopDoc;
import com.citygo.search.repository.ShopSearchRepository;
import com.citygo.search.service.ShopSearchService;
import com.citygo.shop.vo.ShopBrowseVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 店铺搜索服务实现。
 *
 * <p>为什么用 NativeQuery 而非 Repository 派生方法：店铺搜索条件多（关键词 + 城市 +
 * 距离过滤 + 多种排序），组合逻辑用 NativeQuery 拼 QueryBuilder/SortBuilder 更灵活。</p>
 */
@Service
public class ShopSearchServiceImpl implements ShopSearchService {

    private static final Logger log = LoggerFactory.getLogger(ShopSearchServiceImpl.class);

    private final ShopSearchRepository shopSearchRepository;
    private final ElasticsearchOperations operations;

    public ShopSearchServiceImpl(ShopSearchRepository shopSearchRepository,
                                 ElasticsearchOperations operations) {
        this.shopSearchRepository = shopSearchRepository;
        this.operations = operations;
    }

    @Override
    public void syncShop(Shop shop) {
        ShopDoc doc = toDoc(shop);
        shopSearchRepository.save(doc);
    }

    @Override
    public PageVO<ShopBrowseVO> searchShops(String keyword, String city, Double nearLat, Double nearLng,
                                            Double distanceKm, String sort, long pageNum, long pageSize) {
        try {
            // 基础过滤 + 可选过滤
            List<Query> filters = new ArrayList<>();
            filters.add(Query.of(q -> q.term(t -> t.field("status").value(1)))); // 只搜正常店铺
            if (StringUtils.hasText(city)) {
                filters.add(Query.of(q -> q.term(t -> t.field("city").value(city)))); // 城市精确匹配
            }
            if (nearLat != null && nearLng != null && distanceKm != null) {
                // 附近距离过滤（X km 内），与排序解耦——可只过滤不按距离排序
                GeoLocation loc = GeoLocation.of(gl -> gl.latlon(l -> l.lat(nearLat).lon(nearLng)));
                filters.add(Query.of(q -> q.geoDistance(g -> g.field("location")
                        .distance(distanceKm + "km").location(loc))));
            }
            // 关键词全文检索（IK 分词，match shopName）
            List<Query> musts = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                musts.add(Query.of(q -> q.match(m -> m.field("shopName").query(keyword))));
            }

            Query query;
            if (musts.isEmpty()) {
                query = Query.of(q -> q.bool(b -> b.filter(filters)));
            } else {
                query = Query.of(q -> q.bool(b -> b.must(musts).filter(filters)));
            }

            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(query)
                    .withSort(buildSort(sort, nearLat, nearLng))
                    .withPageable(PageRequest.of((int) (pageNum - 1), (int) pageSize))
                    .build();

            SearchHits<ShopDoc> hits = operations.search(nativeQuery, ShopDoc.class);
            PageVO<ShopBrowseVO> pageVO = new PageVO<>();
            pageVO.setTotal(hits.getTotalHits());
            pageVO.setPageNum(pageNum);
            pageVO.setPageSize(pageSize);
            pageVO.setRecords(hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toVO)
                    .toList());
            return pageVO;
        } catch (Exception e) {
            // 容错降级：ES 异常（连接失败等）返回空列表，不阻塞接口
            log.error("店铺搜索失败，降级返回空列表: ", e);
            PageVO<ShopBrowseVO> empty = new PageVO<>();
            empty.setRecords(List.of());
            empty.setTotal(0);
            empty.setPageNum(pageNum);
            empty.setPageSize(pageSize);
            return empty;
        }
    }

    private List<SortOptions> buildSort(String sort, Double nearLat, Double nearLng) {
        String key = sort == null ? "default" : sort;
        switch (key) {
            case "score" -> {
                return List.of(SortOptions.of(o -> o.field(f -> f.field("score").order(SortOrder.Desc))));
            }
            case "sales" -> {
                return List.of(SortOptions.of(o -> o.field(f -> f.field("monthlySales").order(SortOrder.Desc))));
            }
            case "distance" -> {
                if (nearLat != null && nearLng != null) {
                    // geo 排序必须提供原点坐标
                    GeoLocation loc = GeoLocation.of(gl -> gl.latlon(l -> l.lat(nearLat).lon(nearLng)));
                    return List.of(SortOptions.of(o -> o.geoDistance(g -> g.field("location")
                            .location(loc).unit(co.elastic.clients.elasticsearch._types.DistanceUnit.Kilometers)
                            .order(SortOrder.Asc))));
                }
                // 未传经纬度则回退默认相关度
                return defaultSort();
            }
            default -> {
                return defaultSort();
            }
        }
    }

    /** 默认：相关度（_score）降序 */
    private List<SortOptions> defaultSort() {
        return List.of(SortOptions.of(o -> o.score(s -> s.order(SortOrder.Desc))));
    }

    private ShopDoc toDoc(Shop shop) {
        ShopDoc doc = new ShopDoc();
        doc.setId(shop.getId());
        doc.setShopName(shop.getShopName());
        doc.setCity(shop.getCity());
        doc.setDistrict(shop.getDistrict());
        doc.setAddress(shop.getAddress());
        doc.setScore(shop.getScore() == null ? null : shop.getScore().doubleValue());
        doc.setMonthlySales(shop.getMonthlySales());
        doc.setOpenStatus(shop.getOpenStatus());
        doc.setStatus(shop.getStatus());
        if (shop.getLongitude() != null && shop.getLatitude() != null) {
            // GeoPoint(lat, lon)：索引里 geo_point 以 [经度, 纬度] 存储
            doc.setLocation(new GeoPoint(shop.getLatitude().doubleValue(), shop.getLongitude().doubleValue()));
        }
        doc.setCreateTime(shop.getCreateTime() == null ? null
                : shop.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return doc;
    }

    private ShopBrowseVO toVO(ShopDoc doc) {
        ShopBrowseVO vo = new ShopBrowseVO();
        vo.setId(doc.getId());
        vo.setShopName(doc.getShopName());
        vo.setCity(doc.getCity());
        vo.setDistrict(doc.getDistrict());
        vo.setAddress(doc.getAddress());
        vo.setScore(doc.getScore() == null ? null : BigDecimal.valueOf(doc.getScore()));
        vo.setMonthlySales(doc.getMonthlySales());
        vo.setOpenStatus(doc.getOpenStatus());
        vo.setStatus(doc.getStatus());
        return vo;
    }

}