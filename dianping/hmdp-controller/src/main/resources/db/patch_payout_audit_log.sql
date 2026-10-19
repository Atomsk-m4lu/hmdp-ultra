-- 批次2：无忧闪赔赔付审计日志表
-- 执行方式（VM 或本机均可）：mysql -uroot -p dingping < patch_payout_audit_log.sql
CREATE TABLE IF NOT EXISTS tb_payout_audit_log (
  id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  claim_no varchar(64) NOT NULL COMMENT '申诉单号（幂等键）',
  shop_id bigint DEFAULT NULL COMMENT '店铺ID',
  user_id bigint DEFAULT NULL COMMENT '用户ID',
  plan_code varchar(32) DEFAULT NULL COMMENT '闪赔计划编码',
  payout_amount bigint NOT NULL DEFAULT '0' COMMENT '赔付金额，单位分',
  payment_tx_id varchar(128) DEFAULT NULL COMMENT '支付网关交易流水号',
  status varchar(32) NOT NULL COMMENT '赔付状态：COMPLETED / MANUAL_REVIEW',
  message varchar(512) DEFAULT NULL COMMENT '赔付结果说明',
  create_time datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id) USING BTREE,
  UNIQUE KEY uk_claim_no (claim_no) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='无忧闪赔赔付审计';