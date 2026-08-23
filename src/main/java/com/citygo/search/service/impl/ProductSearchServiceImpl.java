package com.citygo.search.service.impl;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.citygo.category.entity.Category;
import com.citygo.category.mapper.CategoryMapper;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.vo.ProductVO;
import com.citygo.search.doc.ProductDoc;
import com.citygo.search.repository.ProductSearchRepository;
import com.citygo.search.service.ProductSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品搜索服务实现。
 */
@Service
public class ProductSearchServiceImpl implements ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchServiceImpl.class);

    private final ProductSearchRepository productSearchRepository;
    private final ElasticsearchOperations operations;
    private final ShopMapper shopMapper;
    private final CategoryMapper categoryMapper;

    public ProductSearchServiceImpl(ProductSearchRepository productSearchRepository,
                                    ElasticsearchOperations operations,
                                    ShopMapper shopMapper,
                                    CategoryMapper categoryMapper) {
        this.productSearchRepository = productSearchRepository;
        this.operations = operations;
        this.shopMapper = shopMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public void syncProduct(Product product) {
        productSearchRepository.save(toDoc(product));
    }

    @Override
    public void syncProducts(List<Product> products) {
        List<ProductDoc> docs = new ArrayList<>();
        // 批量预取店铺名/分类名，避免逐条查库（N+1）
        List<Long> shopIds = products.stream().map(Product::getShopId).distinct().toList();
        Map<Long, String> shopNames = shopIds.isEmpty() ? Map.of()
                : shopMapper.selectByIds(shopIds).stream()
                        .collect(Collectors.toMap(Shop::getId, Shop::getShopName));
        List<Long> categoryIds = products.stream().map(Product::getCategoryId).distinct().toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectByIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
        for (Product p : products) {
            docs.add(toDoc(p, shopNames.get(p.getShopId()), categoryNames.get(p.getCategoryId())));
        }
        productSearchRepository.saveAll(docs);
    }

    @Override
    public PageVO<ProductVO> searchProducts(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                            String sort, long pageNum, long pageSize) {
        try {
            List<Query> filters = new ArrayList<>();
            // 只搜上架商品；下架商品 status=0 保留文档但被过滤，便于重新上架
            filters.add(Query.of(q -> q.term(t -> t.field("status").value(1))));
            if (categoryId != null) {
                filters.add(Query.of(q -> q.term(t -> t.field("categoryId").value(categoryId))));
            }
            if (minPrice != null || maxPrice != null) {
                Double min = minPrice == null ? null : minPrice.doubleValue();
                Double max = maxPrice == null ? null : maxPrice.doubleValue();
                filters.add(Query.of(q -> q.range(r -> r.number(n -> n.field("price").gte(min).lte(max)))));
            }
            // 关键词：multiMatch 在 productName 与 description 两个 IK 字段上检索
            List<Query> musts = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                musts.add(Query.of(q -> q.multiMatch(m -> m
                        .fields("productName", "description").query(keyword))));
            }

            Query query;
            if (musts.isEmpty()) {
                query = Query.of(q -> q.bool(b -> b.filter(filters)));
            } else {
                query = Query.of(q -> q.bool(b -> b.must(musts).filter(filters)));
            }

            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(query)
                    .withSort(buildSort(sort))
                    .withPageable(PageRequest.of((int) (pageNum - 1), (int) pageSize))
                    .build();

            SearchHits<ProductDoc> hits = operations.search(nativeQuery, ProductDoc.class);
            PageVO<ProductVO> pageVO = new PageVO<>();
            pageVO.setTotal(hits.getTotalHits());
            pageVO.setPageNum(pageNum);
            pageVO.setPageSize(pageSize);
            // stock 置 null 脱敏（公开搜索不暴露商家内部库存）
            pageVO.setRecords(hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toVO)
                    .toList());
            return pageVO;
        } catch (Exception e) {
            // 容错降级：ES 异常返回空列表
            log.error("商品搜索失败，降级返回空列表: ", e);
            PageVO<ProductVO> empty = new PageVO<>();
            empty.setRecords(List.of());
            empty.setTotal(0);
            empty.setPageNum(pageNum);
            empty.setPageSize(pageSize);
            return empty;
        }
    }

    private List<SortOptions> buildSort(String sort) {
        String key = sort == null ? "default" : sort;
        switch (key) {
            case "sales" -> {
                return List.of(SortOptions.of(o -> o.field(f -> f.field("sales").order(SortOrder.Desc))));
            }
            case "price_asc" -> {
                return List.of(SortOptions.of(o -> o.field(f -> f.field("price").order(SortOrder.Asc))));
            }
            case "price_desc" -> {
                return List.of(SortOptions.of(o -> o.field(f -> f.field("price").order(SortOrder.Desc))));
            }
            default -> {
                return List.of(SortOptions.of(o -> o.score(s -> s.order(SortOrder.Desc))));
            }
        }
    }

    private ProductDoc toDoc(Product p) {
        return toDoc(p, shopName(p), categoryName(p));
    }

    private String shopName(Product p) {
        Shop shop = shopMapper.selectById(p.getShopId());
        return shop == null ? null : shop.getShopName();
    }

    private String categoryName(Product p) {
        Category c = categoryMapper.selectById(p.getCategoryId());
        return c == null ? null : c.getCategoryName();
    }

    private ProductDoc toDoc(Product p, String shopName, String categoryName) {
        ProductDoc doc = new ProductDoc();
        doc.setId(p.getId());
        doc.setShopId(p.getShopId());
        doc.setShopName(shopName);
        doc.setCategoryId(p.getCategoryId());
        doc.setCategoryName(categoryName);
        doc.setProductName(p.getProductName());
        doc.setDescription(p.getDescription());
        doc.setPrice(p.getPrice() == null ? null : p.getPrice().doubleValue());
        doc.setSales(p.getSales());
        doc.setStock(p.getStock());
        doc.setStatus(p.getStatus());
        return doc;
    }

    private ProductVO toVO(ProductDoc doc) {
        ProductVO vo = new ProductVO();
        vo.setId(doc.getId());
        vo.setShopId(doc.getShopId());
        vo.setCategoryId(doc.getCategoryId());
        vo.setCategoryName(doc.getCategoryName());
        vo.setProductName(doc.getProductName());
        vo.setDescription(doc.getDescription());
        vo.setPrice(doc.getPrice() == null ? null : BigDecimal.valueOf(doc.getPrice()));
        vo.setSales(doc.getSales());
        vo.setStatus(doc.getStatus());
        vo.setStock(null); // 脱敏
        return vo;
    }

}