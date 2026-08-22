package com.citygo.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.category.entity.Category;
import com.citygo.category.mapper.CategoryMapper;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import com.citygo.product.dto.ProductCreateRequest;
import com.citygo.product.dto.ProductStatusRequest;
import com.citygo.product.dto.ProductStockRequest;
import com.citygo.product.dto.ProductUpdateRequest;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品域服务实现。
 *
 * <p>所有商品写操作均做"店铺归属 → 商家"的两级校验：
 * 商品属于某个店铺，店铺属于当前商家，否则抛越权 403。
 * "我的商品"分页默认只返回当前商家名下店铺的商品，避免数据越权。</p>
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final ShopMapper shopMapper;
    private final ShopService shopService;
    private final MerchantService merchantService;

    public ProductServiceImpl(ProductMapper productMapper,
                              CategoryMapper categoryMapper,
                              ShopMapper shopMapper,
                              ShopService shopService,
                              MerchantService merchantService) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.shopMapper = shopMapper;
        this.shopService = shopService;
        this.merchantService = merchantService;
    }

    /**
     * 按当前登录用户ID解析其商家身份；非商家抛 MERCHANT_NOT_FOUND。
     */
    private Merchant requireMerchant(Long currentUserId) {
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    /**
     * 校验店铺归属：店铺必须存在且属于当前商家，否则抛 403/404。
     */
    private void requireOwnedShop(Long shopId, Long merchantId) {
        Long owner = shopService.getMerchantIdOfShop(shopId);
        if (owner == null) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        if (!owner.equals(merchantId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
    }

    @Override
    public ProductVO create(ProductCreateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        // 分类存在性校验
        if (categoryMapper.selectById(request.getCategoryId()) == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        // 店铺归属校验
        requireOwnedShop(request.getShopId(), merchant.getId());

        Product product = new Product();
        product.setShopId(request.getShopId());
        product.setCategoryId(request.getCategoryId());
        product.setProductName(request.getProductName());
        product.setDescription(request.getDescription());
        product.setCoverImage(request.getCoverImage());
        product.setImages(request.getImages());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        // 新商品：上架、销量 0
        product.setStatus(1);
        product.setSales(0);
        productMapper.insert(product);
        return toVO(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductVO update(Long id, ProductUpdateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        if (request.getCategoryId() != null) {
            if (categoryMapper.selectById(request.getCategoryId()) == null) {
                throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            product.setCategoryId(request.getCategoryId());
        }
        product.setProductName(request.getProductName());
        product.setDescription(request.getDescription());
        product.setCoverImage(request.getCoverImage());
        product.setImages(request.getImages());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        productMapper.updateById(product);
        return toVO(product);
    }

    @Override
    public void updateStatus(Long id, ProductStatusRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        product.setStatus(request.getStatus());
        productMapper.updateById(product);
    }

    @Override
    public void updateStock(Long id, ProductStockRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        product.setStock(request.getStock());
        productMapper.updateById(product);
    }

    @Override
    public PageVO<ProductVO> pageMy(Long shopId, String keyword, long pageNum, long pageSize, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Long merchantId = merchant.getId();

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        if (shopId != null) {
            requireOwnedShop(shopId, merchantId);
            qw.eq(Product::getShopId, shopId);
        } else {
            // 未指定店铺时，限定为当前商家名下所有店铺的商品
            List<Long> myShopIds = shopMapper.selectList(
                            Wrappers.<Shop>lambdaQuery().eq(Shop::getMerchantId, merchantId))
                    .stream()
                    .map(Shop::getId)
                    .toList();
            if (myShopIds.isEmpty()) {
                PageVO<ProductVO> empty = new PageVO<>();
                empty.setRecords(List.of());
                empty.setTotal(0);
                empty.setPageNum(pageNum);
                empty.setPageSize(pageSize);
                return empty;
            }
            qw.in(Product::getShopId, myShopIds);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Product::getProductName, keyword);
        }
        qw.orderByDesc(Product::getId);

        Page<Product> page = productMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> toVO(p, categoryIdNameMap))
                .toList());
        return pageVO;
    }

    @Override
    public PageVO<ProductVO> pagePublic(Long shopId, Long categoryId, String keyword,
                                        java.math.BigDecimal minPrice, java.math.BigDecimal maxPrice,
                                        String sort, long pageNum, long pageSize) {
        // 指定店铺时校验店铺存在且正常营业
        if (shopId != null) {
            Shop shop = shopMapper.selectById(shopId);
            if (shop == null || shop.getStatus() == 0) {
                throw new BizException(ErrorCode.SHOP_NOT_FOUND);
            }
        }

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        // 只查上架商品
        qw.eq(Product::getStatus, 1);
        if (shopId != null) {
            qw.eq(Product::getShopId, shopId);
        }
        if (categoryId != null) {
            qw.eq(Product::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Product::getProductName, keyword);
        }
        if (minPrice != null) {
            qw.ge(Product::getPrice, minPrice);
        }
        if (maxPrice != null) {
            qw.le(Product::getPrice, maxPrice);
        }
        // 排序白名单映射：排序字段必须白名单映射，防止 SQL 注入
        String sortKey = sort == null ? "default" : sort;
        switch (sortKey) {
            case "sales" -> qw.orderByDesc(Product::getSales).orderByDesc(Product::getId);
            case "price_asc" -> qw.orderByAsc(Product::getPrice).orderByDesc(Product::getId);
            case "price_desc" -> qw.orderByDesc(Product::getPrice).orderByDesc(Product::getId);
            default -> qw.orderByDesc(Product::getId);
        }

        Page<Product> page = productMapper.selectPage(
                new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 50)), qw);
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        // 公开接口脱敏：库存属商家内部经营数据，不向用户端暴露
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> {
                    ProductVO vo = toVO(p, categoryIdNameMap);
                    vo.setStock(null);
                    return vo;
                })
                .toList());
        return pageVO;
    }

    @Override
    public ProductVO getPublicDetail(Long id) {
        Product product = productMapper.selectById(id);
        // 不存在或已下架都视为不存在
        if (product == null || product.getStatus() == 0) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        // 公开接口脱敏：不返回库存
        ProductVO vo = toVO(product, loadCategoryNames(List.of(product)));
        vo.setStock(null);
        return vo;
    }

    /**
     * 查询商品所属分类的名称映射，避免逐条查库（N+1）。
     */
    private Map<Long, String> loadCategoryNames(List<Product> products) {
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .distinct()
                .toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
    }

    /**
     * 查询商品并做归属校验：不存在抛 PRODUCT_NOT_FOUND，非自己店铺的商品抛 403。
     */
    private Product requireOwnedProduct(Long productId, Long merchantId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        requireOwnedShop(product.getShopId(), merchantId);
        return product;
    }

    /**
     * 商品 → 视图对象（无分类名映射，用于单条场景）。
     */
    private ProductVO toVO(Product product) {
        return toVO(product, Map.of());
    }

    /**
     * 商品 → 视图对象（附带分类名映射）。
     */
    private ProductVO toVO(Product product, Map<Long, String> categoryIdNameMap) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setShopId(product.getShopId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryIdNameMap.getOrDefault(product.getCategoryId(), null));
        vo.setProductName(product.getProductName());
        vo.setDescription(product.getDescription());
        vo.setCoverImage(product.getCoverImage());
        vo.setImages(product.getImages());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }

}