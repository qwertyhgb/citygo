package com.citygo.merchant.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.merchant.dto.ShopCreateRequest;
import com.citygo.merchant.dto.ShopOpenStatusRequest;
import com.citygo.merchant.dto.ShopUpdateRequest;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import com.citygo.merchant.vo.ShopVO;
import com.citygo.search.service.ShopSearchService;
import com.citygo.search.support.SearchSync;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 店铺域服务实现 - 面向新手的详细说明版。
 *
 * <h3>这个类是干什么的？</h3>
 * 这是店铺管理的核心业务逻辑层，负责处理商家对店铺的增删改查操作。
 * 可以理解为"店铺管家"，商家想开店、改店铺信息、查看自己的店铺，都要通过它。
 *
 * <h3>核心设计思想</h3>
 * <ol>
 *   <li><b>归属校验</b>：商家只能操作自己的店铺，不能改别人的店铺（防越权）</li>
 *   <li><b>双写策略</b>：店铺数据既存 MySQL（主存储），也同步到 Elasticsearch（搜索引擎）</li>
 *   <li><b>缓存联动</b>：店铺信息改了，立即清除对应的缓存，下次查询会重新加载最新数据</li>
 *   <li><b>事务保护</b>：涉及多个表操作的方法用 @Transactional，要么全成功，要么全回滚</li>
 * </ol>
 *
 * <h3>数据流转路径</h3>
 * <pre>
 * 商家创建店铺：
 *   Controller → Service.create → 校验商家身份 → 插入 MySQL → 同步到 ES → 返回结果
 *
 * 商家修改店铺：
 *   Controller → Service.update → 校验归属权 → 更新 MySQL → 清缓存 → 同步 ES → 返回结果
 * </pre>
 *
 * <h3>为什么要同步到 Elasticsearch？</h3>
 * 用户在首页搜索"火锅"时，需要全文搜索店铺名称、描述等字段。
 * MySQL 的 LIKE 查询在大数据量下很慢，Elasticsearch 专门针对搜索优化，速度快。
 * 所以采用"MySQL 存原始数据，ES 提供搜索能力"的双写模式。
 *
 * <h3>类内方法布局约定</h3>
 * - 上半部分：公共方法（实现接口），对外暴露的业务能力
 * - 下半部分：私有方法（辅助方法），内部复用的工具逻辑
 * 这样阅读代码时先看到"能做什么"，再看"怎么做的"
 */
@Service  // Spring 组件注解：标记这是一个服务层 Bean，由 Spring 容器管理
public class ShopServiceImpl implements ShopService {

    // ============================================================
    // 依赖注入的组件（通过构造器注入，这是 Spring 推荐的方式）
    // ============================================================

    /** 店铺数据访问层：负责 shop 表的 CRUD 操作 */
    private final ShopMapper shopMapper;

    /** 商家服务：用于查询当前用户的商家身份（商家才能开店） */
    private final MerchantService merchantService;

    /** 店铺搜索服务：负责把店铺数据同步到 Elasticsearch */
    private final ShopSearchService shopSearchService;

    /**
     * 搜索同步工具：确保 ES 同步在事务提交后执行。
     * 为什么要事务提交后？如果事务回滚了，ES 里却已经有了数据，就会造成数据不一致。
     */
    private final SearchSync searchSync;

    /**
     * 构造器注入（Spring 推荐方式）。
     *
     * <p><b>为什么用构造器注入而不是 @Autowired？</b></p>
     * <ol>
     *   <li>依赖明确：所有依赖在构造时就确定，对象创建后状态完整</li>
     *   <li>便于测试：写单元测试时可以直接 new 这个类，手动传入 mock 对象</li>
     *   <li>防止空指针：如果依赖缺失，启动时就会报错，而不是运行时才发现</li>
     * </ol>
     */
    public ShopServiceImpl(ShopMapper shopMapper, MerchantService merchantService,
                           ShopSearchService shopSearchService, SearchSync searchSync) {
        this.shopMapper = shopMapper;
        this.merchantService = merchantService;
        this.shopSearchService = shopSearchService;
        this.searchSync = searchSync;
    }

    // ============================================================
    // 公共方法（实现 ShopService 接口）
    // ============================================================

    /**
     * 创建店铺 - 完整流程详解。
     *
     * <h3>业务场景</h3>
     * 商家注册成功后，第一步是开店。一个商家可以开多家店铺（比如海底捞有很多分店）。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li><b>校验商家身份</b>：当前登录用户必须是商家（有 merchant 记录），否则不能开店</li>
     *   <li><b>构建店铺对象</b>：把前端传来的数据（店名、地址等）填充到 Shop 实体</li>
     *   <li><b>设置默认值</b>：新店铺默认是"营业中"、评分0、月销量0、状态正常</li>
     *   <li><b>插入数据库</b>：调用 MyBatis-Plus 的 insert 方法，插入后会自动回填主键 ID</li>
     *   <li><b>同步到 ES</b>：让用户可以立即搜索到这家新店铺</li>
     *   <li><b>返回结果</b>：把实体转成 VO（视图对象）返回给前端</li>
     * </ol>
     *
     * <h3>为什么要加 @Transactional？</h3>
     * 虽然这里只有一个 insert 操作，但加事务是好习惯：
     * - 如果后续扩展了逻辑（比如创建店铺时自动创建默认商品分类），多个操作要保证原子性
     * - rollbackFor = Exception.class 确保任何异常（包括受检异常）都会回滚
     *
     * <h3>常见问题</h3>
     * <b>Q: 为什么不在 Controller 里直接调用 Mapper？</b><br>
     * A: Service 层负责业务逻辑（校验、默认值、同步等），Controller 只负责接收参数和返回响应。
     * 这样分层清晰，业务逻辑可以在多个 Controller 里复用。
     *
     * <b>Q: ES 同步失败会影响创建店铺吗？</b><br>
     * A: 不会。ES 同步在事务提交后异步执行，失败只会记录日志，不影响主流程。
     * 后续可以通过定时任务或手动补偿来修复不一致的数据。
     *
     * @param request       店铺创建请求体（包含店名、地址、营业时间等）
     * @param currentUserId 当前登录用户 ID（从 JWT token 中解析出来，由 Security 框架注入）
     * @return 创建的店铺视图对象（包含自动生成的店铺 ID）
     * @throws BizException 如果当前用户不是商家，抛出 MERCHANT_NOT_FOUND 异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)  // 开启事务，任何异常都回滚
    public ShopVO create(ShopCreateRequest request, Long currentUserId) {
        // 步骤 1: 校验商家身份（内部会查 merchant 表，找不到就抛异常）
        Merchant merchant = requireMerchant(currentUserId);

        // 步骤 2: 构建店铺实体对象
        Shop shop = new Shop();
        shop.setMerchantId(merchant.getId());  // 关联到商家
        shop.setShopName(request.getShopName());
        shop.setCity(request.getCity());
        shop.setDistrict(request.getDistrict());
        shop.setAddress(request.getAddress());
        shop.setLongitude(request.getLongitude());  // 经纬度，用于地图定位和附近的店
        shop.setLatitude(request.getLatitude());
        shop.setOpenTime(request.getOpenTime());    // 营业开始时间，如 09:00
        shop.setCloseTime(request.getCloseTime());  // 营业结束时间，如 22:00
        shop.setNotice(request.getNotice());        // 店铺公告，如"本店支持外卖配送"

        // 步骤 3: 设置默认值（新店铺的初始状态）
        shop.setOpenStatus(1);                      // 1=营业中，0=打烊
        shop.setScore(BigDecimal.ZERO);             // 评分初始为 0（还没人评价）
        shop.setMonthlySales(0);                    // 月销量初始为 0
        shop.setStatus(1);                          // 1=正常，0=禁用（被平台下架）

        // 步骤 4: 插入数据库
        // MyBatis-Plus 会自动生成雪花 ID 填充到 shop.id，所以 insert 后可以直接用 shop.getId()
        shopMapper.insert(shop);

        // 步骤 5: 同步到 Elasticsearch（在事务提交后执行，避免事务回滚却已同步脏数据）
        syncShopAfterCommit(shop.getId());

        // 步骤 6: 转成 VO 返回（VO 是给前端看的，剥离了一些内部字段如 deleted）
        return toVO(shop);
    }

    /**
     * 更新店铺信息 - 支持部分字段更新。
     *
     * <h3>业务场景</h3>
     * 商家想修改店铺信息，比如改个店名、更新营业时间、修改公告等。
     * 不是每次都要填写全部字段，只填想改的字段即可（部分更新）。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li><b>校验商家身份</b>：确保当前用户是商家</li>
     *   <li><b>归属校验</b>：确保要修改的店铺属于这个商家（防止越权修改别人的店铺）</li>
     *   <li><b>部分更新</b>：只更新请求中有值的字段，空值字段保持不变</li>
     *   <li><b>更新数据库</b>：调用 MyBatis-Plus 的 updateById</li>
     *   <li><b>清除缓存</b>：让用户端下次查询时拿到最新数据</li>
     *   <li><b>同步到 ES</b>：更新搜索引擎中的店铺信息</li>
     * </ol>
     *
     * <h3>为什么要做归属校验？</h3>
     * 防止恶意用户伪造请求，修改别人的店铺。比如：
     * - 用户 A 是商家，店铺 ID 是 100
     * - 用户 A 发请求修改店铺 200（属于商家 B）
     * - 如果不校验归属，用户 A 就能改别人的店铺，这是严重的越权漏洞
     *
     * <h3>@CacheEvict 是什么？</h3>
     * 这个注解会在方法执行后，删除指定的缓存。
     * - cacheNames = "shopDetail"：缓存的名字（和用户端查询详情的缓存名对应）
     * - key = "#id"：缓存的 key 就是店铺 ID
     * 店铺信息改了，对应的缓存就失效了，下次查询会重新从数据库加载。
     *
     * <h3>为什么用 StringUtils.hasText？</h3>
     * 前端可能传来空字符串 "" 或 null，用 hasText 可以同时判断：
     * - 不是 null
     * - 不是空字符串
     * - 不是只有空格的字符串
     * 这样才算"有值"，才去更新数据库。
     *
     * @param id            店铺 ID（要修改哪个店铺）
     * @param request       更新请求体（只传需要修改的字段，其他字段可以为 null）
     * @param currentUserId 当前登录用户 ID
     * @return 更新后的店铺视图对象
     * @throws BizException MERCHANT_NOT_FOUND（不是商家）或 UNAUTHORIZED_OPERATION（店铺不属于自己）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    // 店铺信息被修改后，立即删除对应的缓存（key 是店铺 ID）
    // 这样用户端下次查询店铺详情时，会重新从数据库加载最新数据，避免读到旧数据
    @CacheEvict(cacheNames = "shopDetail", key = "#id")
    public ShopVO update(Long id, ShopUpdateRequest request, Long currentUserId) {
        // 步骤 1: 校验商家身份
        Merchant merchant = requireMerchant(currentUserId);

        // 步骤 2: 归属校验 - 确保这个店铺属于当前商家，否则抛异常
        Shop shop = requireOwnedShop(id, merchant.getId());

        // 步骤 3: 只更新非空字段（部分更新）
        // 如果请求中某个字段是 null 或空字符串，就不去改数据库里的值，保持原样
        if (StringUtils.hasText(request.getShopName())) {
            shop.setShopName(request.getShopName());
        }
        if (StringUtils.hasText(request.getDescription())) {
            shop.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getCity())) {
            shop.setCity(request.getCity());
        }
        if (StringUtils.hasText(request.getDistrict())) {
            shop.setDistrict(request.getDistrict());
        }
        if (StringUtils.hasText(request.getAddress())) {
            shop.setAddress(request.getAddress());
        }
        if (request.getLongitude() != null) {  // 数字类型只判断 null
            shop.setLongitude(request.getLongitude());
        }
        if (request.getLatitude() != null) {
            shop.setLatitude(request.getLatitude());
        }
        if (request.getOpenTime() != null) {  // 时间类型也只判断 null
            shop.setOpenTime(request.getOpenTime());
        }
        if (request.getCloseTime() != null) {
            shop.setCloseTime(request.getCloseTime());
        }
        if (StringUtils.hasText(request.getNotice())) {
            shop.setNotice(request.getNotice());
        }

        // 步骤 4: 更新数据库
        // MyBatis-Plus 的 updateById 会根据主键 ID 更新，只更新上面 set 过的字段
        shopMapper.updateById(shop);

        // 步骤 5: 同步到 ES（重新读取店铺数据，确保评分、销量等最新字段也同步）
        syncShopAfterCommit(id);

        // 步骤 6: 返回更新后的店铺信息
        return toVO(shop);
    }

    /**
     * 更新店铺营业状态 - 营业中 / 打烊切换。
     *
     * <h3>业务场景</h3>
     * 商家临时有事需要关店（打烊），或者打烊后重新开门（营业中）。
     * 用户端会根据这个状态过滤店铺：打烊的店铺在列表中会标记"休息中"。
     *
     * <h3>为什么单独做一个方法？</h3>
     * 营业状态切换很频繁，单独一个接口方便商家快速操作，不需要填写其他字段。
     * 而且这个状态影响搜索结果，必须同步到 ES。
     *
     * @param id            店铺 ID
     * @param request       营业状态请求体（openStatus: 1=营业中, 0=打烊）
     * @param currentUserId 当前登录用户 ID
     * @throws BizException 归属校验失败时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOpenStatus(Long id, ShopOpenStatusRequest request, Long currentUserId) {
        // 校验商家身份和店铺归属
        Merchant merchant = requireMerchant(currentUserId);
        Shop shop = requireOwnedShop(id, merchant.getId());

        // 更新营业状态
        shop.setOpenStatus(request.getOpenStatus());
        shopMapper.updateById(shop);

        // 营业状态变化需要同步到 ES，用户搜索时会过滤打烊的店铺
        syncShopAfterCommit(id);
    }

    /**
     * 查询当前商家名下的全部店铺。
     *
     * <h3>业务场景</h3>
     * 商家后台"我的店铺"列表，展示自己开的所有店铺。
     * 一个商家可能有多家店（比如连锁店），这里一次查出来。
     *
     * <h3>查询逻辑</h3>
     * <ol>
     *   <li>根据当前用户 ID 查出商家身份（merchantId）</li>
     *   <li>查询 shop 表中 merchant_id = 当前商家 ID 的所有记录</li>
     *   <li>按创建时间倒序（最新创建的店铺排在最前面）</li>
     *   <li>转成 VO 列表返回</li>
     * </ol>
     *
     * <h3>为什么不需要归属校验？</h3>
     * 因为查询条件已经限定了 merchantId，只会返回当前商家的店铺，不会泄露别人的数据。
     *
     * <h3>Wrappers.lambdaQuery() 是什么？</h3>
     * MyBatis-Plus 提供的链式查询构造器，可以用 lambda 表达式避免写字段名字符串。
     * - eq(Shop::getMerchantId, merchant.getId())：等价于 SQL 的 WHERE merchant_id = ?
     * - orderByDesc(Shop::getId)：等价于 SQL 的 ORDER BY id DESC
     *
     * @param currentUserId 当前登录用户 ID
     * @return 店铺视图对象列表（可能为空，表示还没开店）
     */
    @Override
    public List<ShopVO> listMy(Long currentUserId) {
        // 先查出商家身份
        Merchant merchant = requireMerchant(currentUserId);

        // 查询当前商家的所有店铺，按 ID 倒序
        return shopMapper.selectList(
                        Wrappers.<Shop>lambdaQuery()
                                .eq(Shop::getMerchantId, merchant.getId())  // WHERE merchant_id = ?
                                .orderByDesc(Shop::getId))                   // ORDER BY id DESC
                .stream()         // 转成 Java Stream 流式处理
                .map(this::toVO)  // 把每个 Shop 实体转成 ShopVO
                .toList();        // 收集成 List 返回
    }

    /**
     * 根据店铺 ID 查询其所属商家 ID - 供其他模块复用。
     *
     * <h3>使用场景</h3>
     * 其他业务模块（比如商品、订单）需要做归属校验时，只需要知道店铺归属哪个商家，
     * 不需要查出店铺的全部字段。这个方法就是为了避免重复查询，提高性能。
     *
     * <h3>为什么返回 null 而不是抛异常？</h3>
     * 调用方可能只是"试探性"地查询，店铺不存在时希望自己决定如何处理（返回 404 或其他逻辑）。
     * 如果这里直接抛异常，调用方就没有选择权了。
     *
     * @param shopId 店铺 ID
     * @return 所属商家 ID，店铺不存在时返回 null
     */
    @Override
    public Long getMerchantIdOfShop(Long shopId) {
        Shop shop = shopMapper.selectById(shopId);  // 根据主键查询店铺
        return shop == null ? null : shop.getMerchantId();
    }

    // ============================================================
    // 私有辅助方法 - 内部复用的工具逻辑
    // ============================================================

    /**
     * 校验当前用户是否是商家 - 如果不是就抛异常。
     *
     * <h3>为什么要单独抽一个方法？</h3>
     * 创建店铺、修改店铺、查询店铺等很多操作都需要先校验商家身份，
     * 如果每个方法都写一遍，代码会很冗余。抽成私有方法后，内部统一调用。
     *
     * <h3>工作原理</h3>
     * 调用 merchantService.getByUserId(currentUserId) 查询 merchant 表：
     * - 如果 user_id 对应的 merchant 记录存在 → 返回商家实体
     * - 如果不存在 → merchant 为 null → 抛出 MERCHANT_NOT_FOUND 异常
     *
     * <h3>异常会怎么处理？</h3>
     * BizException 会被全局异常处理器 GlobalExceptionHandler 捕获，
     * 转成统一的 Result 返回，HTTP 状态码是 200，但 code 是业务错误码。
     *
     * @param currentUserId 当前登录用户 ID
     * @return 商家实体（保证非 null）
     * @throws BizException 如果当前用户不是商家，抛出 MERCHANT_NOT_FOUND
     */
    private Merchant requireMerchant(Long currentUserId) {
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            // 抛业务异常，全局异常处理器会捕获并转成统一格式返回
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    /**
     * 查询店铺并校验归属权 - 确保店铺属于指定商家。
     *
     * <h3>为什么需要这个方法？</h3>
     * 商家修改店铺、更新营业状态等操作，都必须确保"操作的店铺属于自己"。
     * 这是一个典型的权限校验逻辑，抽成私有方法复用。
     *
     * <h3>两步校验</h3>
     * <ol>
     *   <li><b>店铺存在性校验</b>：根据 shopId 查询，如果查不到，说明店铺不存在 → 404</li>
     *   <li><b>归属校验</b>：查到的店铺的 merchantId 必须等于传入的 merchantId → 否则 403（越权）</li>
     * </ol>
     *
     * <h3>为什么用 equals 而不是 ==？</h3>
     * shop.getMerchantId() 和 merchantId 都是 Long 对象，== 比较的是对象引用（地址），
     * 而我们要比较的是数值。equals 会比较实际的数值，更安全。
     *
     * <h3>403 vs 404 的区别</h3>
     * - 404 NOT_FOUND：资源不存在（店铺 ID 错了）
     * - 403 FORBIDDEN：资源存在，但你没权限操作（店铺不是你的）
     *
     * @param shopId     店铺 ID
     * @param merchantId 期望归属的商家 ID（当前登录商家的 ID）
     * @return 通过校验的店铺实体（保证非 null 且归属正确）
     * @throws BizException SHOP_NOT_FOUND（店铺不存在）或 UNAUTHORIZED_OPERATION（越权）
     */
    private Shop requireOwnedShop(Long shopId, Long merchantId) {
        // 第一步：查询店铺
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            // 店铺不存在 → 404
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }

        // 第二步：归属校验
        if (!shop.getMerchantId().equals(merchantId)) {
            // 店铺存在，但不属于当前商家 → 403（越权操作）
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }

        return shop;
    }

    /**
     * 在事务提交后同步店铺数据到 Elasticsearch。
     *
     * <h3>为什么要"事务提交后"？</h3>
     * 假设流程是：插入 MySQL → 同步 ES → 事务回滚
     * - 如果同步 ES 在事务内，回滚后 MySQL 没数据，但 ES 有数据 → 数据不一致
     * - 如果同步 ES 在事务提交后，回滚时 ES 还没执行同步 → 数据一致
     *
     * <h3>SearchSync.afterCommit 是怎么实现的？</h3>
     * 它会注册一个事务同步器（TransactionSynchronization），
     * 监听事务的 afterCommit 事件，在事务提交后才执行传入的逻辑。
     *
     * <h3>ES 同步失败怎么办？</h3>
     * 这个方法内部会 try-catch，失败只记录 ERROR 日志，不影响主流程。
     * 生产环境通常有以下补偿机制：
     * - 定时任务扫描 MySQL，发现不一致的数据重新同步
     * - 监控告警：ES 同步失败超过阈值时发邮件/短信通知
     * - 手动修复：提供后台管理页面，运营可以手动触发重新同步
     *
     * @param shopId 店铺 ID
     */
    private void syncShopAfterCommit(Long shopId) {
        // afterCommit 接收一个 Runnable，在事务提交后执行
        searchSync.afterCommit(() -> {
            // 重新查询店铺数据（确保拿到最新的评分、销量等字段）
            Shop shop = shopMapper.selectById(shopId);
            // 调用搜索服务同步到 ES
            shopSearchService.syncShop(shop);
        });
    }

    /**
     * 店铺实体转视图对象 - 数据脱敏与解耦。
     *
     * <h3>什么是 VO（View Object）？</h3>
     * VO 是专门给前端看的数据结构，与实体（Entity）的区别：
     * - Entity：对应数据库表结构，包含所有字段（含内部字段如 deleted）
     * - VO：只包含前端需要的字段，隐藏内部实现细节
     *
     * <h3>为什么要转 VO？</h3>
     * <ol>
     *   <li><b>数据脱敏</b>：deleted（逻辑删除标记）是内部字段，前端不需要知道</li>
     *   <li><b>字段重命名</b>：前端可能需要不同的字段名（虽然这里没有）</li>
     *   <li><b>防止循环引用</b>：实体间可能有关联（如店铺关联商家），直接返回可能序列化失败</li>
     *   <li><b>解耦</b>：数据库表结构改了，只需要改转换逻辑，接口返回格式不变</li>
     * </ol>
     *
     * <h3>为什么不用 BeanUtils.copyProperties？</h3>
     * BeanUtils 虽然方便，但：
     * - 性能略低（反射）
     * - 不够灵活（字段名必须完全一样）
     * - 不够明确（看代码不知道转了哪些字段）
     * 手动逐字段拷贝虽然啰嗦，但一目了然，性能也最优。
     *
     * @param shop 店铺实体（从数据库查出来的）
     * @return 店铺视图对象（给前端返回的）
     */
    private ShopVO toVO(Shop shop) {
        ShopVO vo = new ShopVO();
        // 逐字段拷贝：把 Entity 的字段值赋给 VO
        vo.setId(shop.getId());
        vo.setMerchantId(shop.getMerchantId());
        vo.setShopName(shop.getShopName());
        vo.setLogo(shop.getLogo());
        vo.setDescription(shop.getDescription());
        vo.setProvince(shop.getProvince());
        vo.setCity(shop.getCity());
        vo.setDistrict(shop.getDistrict());
        vo.setAddress(shop.getAddress());
        vo.setLongitude(shop.getLongitude());
        vo.setLatitude(shop.getLatitude());
        vo.setScore(shop.getScore());               // 评分（用户评价的平均分）
        vo.setReviewCount(shop.getReviewCount());   // 评价数（冗余字段，避免 COUNT 聚合）
        vo.setMonthlySales(shop.getMonthlySales()); // 月销量（冗余字段，展示在列表页）
        vo.setOpenStatus(shop.getOpenStatus());     // 营业状态（1=营业中, 0=打烊）
        vo.setOpenTime(shop.getOpenTime());         // 营业开始时间
        vo.setCloseTime(shop.getCloseTime());       // 营业结束时间
        vo.setNotice(shop.getNotice());             // 店铺公告
        vo.setStatus(shop.getStatus());             // 店铺状态（1=正常, 0=禁用）
        vo.setCreateTime(shop.getCreateTime());     // 创建时间
        // 注意：deleted 字段不返回给前端，这是内部逻辑删除标记
        return vo;
    }

}
