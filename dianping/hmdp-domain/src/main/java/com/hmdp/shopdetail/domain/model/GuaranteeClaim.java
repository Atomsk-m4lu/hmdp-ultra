package com.hmdp.shopdetail.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保障申诉实体
 * <p>
 * 用户针对到店体验异常发起的申诉单，对应"无忧闪赔"计划。
 * 申诉提交后由 {@link com.hmdp.shared.domain.compensation.service.FlashPayStateMachineFacade}
 * 驱动状态机流转，自动完成或转人工审核。
 * </p>
 */
@Data
public class GuaranteeClaim {

    /** 数据库主键，自增 */
    private Long id;

    /** 店铺ID，同时作为状态机的 bizKey 用于同店铺任务隔离 */
    private Long shopId;

    /** 发起申诉的用户ID */
    private Long userId;

    /** 申诉单号，业务唯一标识，同时作为状态机的 taskId（tb_state_task.uk_task_id） */
    private String claimNo;

    /** 保障计划编码，例如 "FLASH_PAY"（无忧闪赔），决定进入哪台状态机 */
    private String planCode;

    /** 申诉原因，用户填写或前端枚举值，随 payload 传递给状态机节点 */
    private String reason;

    /** 当前申诉状态，与状态机 currentState 同步，例如 SUBMITTED / COMPLETED / MANUAL_REVIEW */
    private String status;

    /** 最近一次状态流转产生的结果说明，用于向用户展示申诉进度与最终结论 */
    private String resultMessage;

    /** 申诉创建时间 */
    private LocalDateTime createTime;
}