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
 */
@Component
public class SearchSync {

    private static final Logger log = LoggerFactory.getLogger(SearchSync.class);

    /**
     * 在事务提交后执行同步任务；异常仅记 ERROR，不向上抛。
     */
    public void afterCommit(Runnable syncTask) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务上下文（理论上不到，兜底）：直接执行
            runSafely(syncTask);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(syncTask);
            }
        });
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // 主库为准，ES 同步失败打 ERROR 不阻断主流程
            log.error("ES 索引同步失败（主库为准，忽略）: ", e);
        }
    }

}