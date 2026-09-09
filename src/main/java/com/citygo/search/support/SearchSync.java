package com.citygo.search.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ES 双写同步的 afterCommit 调度助手。
 *
 * <p><b>为什么必须 afterCommit</b>：ES 不参与本地 MySQL 事务——若在事务内同步 ES，
 * 一旦事务回滚，ES 里会留下"幽灵文档"；而 ES 写入失败又会导致本地事务异常。
 * 用 afterCommit 保证"MySQL 提交成功后才去同步 ES"，且 ES 失败只记 ERROR、不影响主流程。</p>
 *
 * <p><b>一致性取舍</b>：主库为准，允许索引短暂不一致；生产可升级为 MQ/Canal 异步同步，
 * 学习项目用 afterCommit + 日志兜底即可。</p>
 *
 * <p><b>面向新手的背景说明</b>：本项目同时把数据存在两个地方——MySQL（主库，最权威、最完整）
 * 和 Elasticsearch（搜索引擎，用于商品/店铺搜索）。为了让两者保持一致，业务在写库之后，
 * 也要把数据同步给 ES。本类就是这个"同步调度器"的核心，负责让同步动作在正确的时间点（事务提交后）
 * 安全地执行。</p>
 */
@Component
public class SearchSync {

    private static final Logger log = LoggerFactory.getLogger(SearchSync.class);

    /**
     * 在事务提交后执行同步任务；异常仅记 ERROR，不向上抛。
     *
     * <p><b>这个方法在干什么？</b><br>
     * 业务代码（如 ShopServiceImpl.create）会在写完 MySQL 后调用：
     * <pre>
     *     searchSync.afterCommit(() -&gt; shopSearchService.syncShop(...));
     * </pre>
     * 这里传入的 {@code syncTask}（一个 Runnable 任务）就是"把数据同步到 ES"这件事本身。
     * 本方法不立即执行它，而是把它"挂号"到当前事务上，等事务提交成功后，Spring 再帮我们调用。</p>
     *
     * <p><b>为什么不能立刻执行？</b><br>
     * 如果立刻执行：假设后面业务抛出异常导致 MySQL 事务回滚，MySQL 里的数据没了，
     * 但 ES 里已经写入了这条"幽灵数据"，两边就不一致了。所以必须等到事务真正 commit 之后
     * 才允许同步。</p>
     *
     * <p><b>两种情况：</b>
     * <ol>
     *   <li><b>当前有事务</b>：把任务注册进事务同步器，事务提交后由 {@code afterCommit()} 回调触发。</li>
     *   <li><b>当前没有事务</b>（理论上不会出现，属于兜底防御）：因为没有事务可等，
     *       直接安全地执行任务即可。</li>
     * </ol>
     * </p>
     *
     * @param syncTask 要执行的同步任务（一个 Runnable），通常是一个 lambda 表达式
     */
    public void afterCommit(Runnable syncTask) {
        // 判断当前线程是否存在"活跃的事务同步上下文"。
        // 有事务时，Spring 会在事务的各个阶段（提交前/提交后/回滚后）执行我们注册的回调。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 走到这里说明当前没有事务同步器在运作（无事务）。
            // 既然没有事务可等，也就谈不上"提交后"执行，直接安全执行即可。
            // 这是一个兜底分支，正常情况下业务都在 @Transactional 方法里调用，不会走到这。
            runSafely(syncTask);
            return;
        }

        // 当前存在事务：把我们的同步任务注册为一个"事务同步器"。
        // 之后当事务成功提交（commit）时，Spring 会自动调用下面 afterCommit() 里我们写的逻辑。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 事务已成功提交，此时才真正去执行"同步 ES"这个任务。
                // 这样保证了 MySQL 提交成功 → 才写 ES，两边数据一致。
                runSafely(syncTask);
            }
        });
    }

    /**
     * 安全地执行同步任务：任何异常都不会向上抛出，只记一条 ERROR 日志。
     *
     * <p><b>为什么异常不能往上抛？</b><br>
     * 假设 ES 暂时不可用，导致同步失败。此时 MySQL 已经提交成功了，主库数据没问题。
     * 如果这里把异常抛出去，会打扰到原本成功的业务请求，甚至让调用方误以为操作失败。
     * 项目采用"主库为准"的取舍：ES 同步失败，只打日志记录下来，业务照常成功。
     * （生产环境可以再配合 MQ 重试或定时任务补偿，学习项目用日志兜底即可。）</p>
     *
     * @param task 要执行的同步任务
     */
    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // 主库为准，ES 同步失败打 ERROR 不阻断主流程
            log.error("ES 索引同步失败（主库为准，忽略）: ", e);
        }
    }

}
