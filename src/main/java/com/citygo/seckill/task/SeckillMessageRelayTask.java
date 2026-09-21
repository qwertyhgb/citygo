package com.citygo.seckill.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.mq.config.RabbitConfig;
import com.citygo.seckill.entity.SeckillMessageRecord;
import com.citygo.seckill.mapper.SeckillMessageMapper;
import com.citygo.seckill.message.SeckillMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 秒杀可靠消息补偿任务。
 *
 * <p>职责：扫描 {@code seckill_message} 表中 status=0（待发送）且已到重试时间的消息，
 * 重新投递 MQ；投递成功置 status=1，失败则递增重试次数并按指数退避顺延下次重试，
 * 重试超限置 status=2 并告警（需人工介入排查 MQ 故障）。</p>
 *
 * <p>为何需要它：秒杀链路中"Redis 预扣 → MQ 投递"存在可靠性缝隙，投递失败若无人补偿，
 * 会造成"库存已扣、用户已占位、但订单永不创建"。本地消息表 + 本任务形成闭环：
 * <ul>
 *   <li>落库即视为抢购成功（消息必达）；</li>
 *   <li>MQ 故障期间消息停在待发送状态，MQ 恢复后由本任务自动补投；</li>
 *   <li>重复投递由消费者 {@code hasPurchasedInDB} 幂等复查兜底。</li>
 * </ul>
 * </p>
 */
@Component
public class SeckillMessageRelayTask {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageRelayTask.class);

    /** 单轮扫描上限（防止瞬时大量积压拖垮本次调度） */
    private static final int BATCH_LIMIT = 100;

    /** 最大补偿重试次数：超过后标记失败（status=2），需人工介入 */
    private static final int MAX_RETRY = 10;

    private final SeckillMessageMapper seckillMessageMapper;
    private final RabbitTemplate rabbitTemplate;

    public SeckillMessageRelayTask(SeckillMessageMapper seckillMessageMapper, RabbitTemplate rabbitTemplate) {
        this.seckillMessageMapper = seckillMessageMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 每 30 秒补偿扫描一次（fixedDelay：上次执行完成后再隔 30 秒）。
     *
     * <p>只捞 status=0 且 next_retry_time 已到、且重试未超限的消息；
     * searchCount=false 跳过 COUNT 统计，仅取一页数据。</p>
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void relayPending() {
        IPage<SeckillMessageRecord> page = seckillMessageMapper.selectPage(
                new Page<>(1, BATCH_LIMIT, false),
                Wrappers.<SeckillMessageRecord>lambdaQuery()
                        .eq(SeckillMessageRecord::getStatus, SeckillMessageRecord.STATUS_PENDING)
                        .le(SeckillMessageRecord::getNextRetryTime, LocalDateTime.now())
                        .lt(SeckillMessageRecord::getRetryCount, MAX_RETRY)
                        .orderByAsc(SeckillMessageRecord::getCreateTime));
        if (page.getRecords().isEmpty()) {
            return;
        }
        log.info("秒杀消息补偿任务扫描到 {} 条待发送消息", page.getRecords().size());
        page.getRecords().forEach(this::relay);
    }

    /**
     * 补偿重发单条消息。
     *
     * <p>重试间隔随次数拉大（30s → 60s → 90s …），避免 MQ 短暂故障恢复前高频空转；
     * 重试超过 {@link #MAX_RETRY} 次仍失败，置 status=2 并记录 ERROR 日志告警。</p>
     */
    private void relay(SeckillMessageRecord record) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_SECKILL_ORDER, RabbitConfig.RK_SECKILL_ORDER,
                    new SeckillMessage(record.getUserId(), record.getProductId()));
            SeckillMessageRecord update = new SeckillMessageRecord();
            update.setId(record.getId());
            update.setStatus(SeckillMessageRecord.STATUS_SENT);
            seckillMessageMapper.updateById(update);
            log.info("秒杀消息补偿发送成功: msgId={}, userId={}, productId={}",
                    record.getId(), record.getUserId(), record.getProductId());
        } catch (Exception e) {
            int retry = (record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1;
            SeckillMessageRecord update = new SeckillMessageRecord();
            update.setId(record.getId());
            update.setRetryCount(retry);
            if (retry >= MAX_RETRY) {
                update.setStatus(SeckillMessageRecord.STATUS_FAILED);
                log.error("秒杀消息补偿重试超限，需人工介入: msgId={}, userId={}, productId={}",
                        record.getId(), record.getUserId(), record.getProductId(), e);
            } else {
                update.setNextRetryTime(LocalDateTime.now().plusSeconds(30L * retry));
                log.warn("秒杀消息补偿发送失败，{} 秒后重试: msgId={}, userId={}, productId={}, err={}",
                        30L * retry, record.getId(), record.getUserId(), record.getProductId(), e.getMessage());
            }
            seckillMessageMapper.updateById(update);
        }
    }

}
