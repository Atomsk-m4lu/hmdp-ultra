package com.hmdp.shared.domain.taskchain.service;

import com.hmdp.shared.domain.taskchain.enums.ExecutionMode;
import com.hmdp.shared.domain.taskchain.model.PhaseConfig;
import com.hmdp.shared.domain.taskchain.model.PhaseExecutionResult;
import com.hmdp.shared.domain.taskchain.model.TaskContext;
import com.hmdp.shared.domain.taskchain.model.TaskNodeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PhaseExecutionEngine {

    /** 线程池路由器：根据节点 taskType（IO_BOUND/CPU_BOUND）把节点派发到对应的线程池 */
    private final TaskDispatcher taskDispatcher;

    public PhaseExecutionEngine(TaskDispatcher taskDispatcher) {
        this.taskDispatcher = taskDispatcher;
    }

    /**
     * 单阶段执行入口（被 TaskChainEngine 私有核心循环里反复调用）
     * <p>
     * 执行流程：空节点快速返回 → 按 ExecutionMode 选串行/并行 → 收集执行结果 → 异常兜底
     * </p>
     *
     * @param phaseConfig 当前阶段配置（包含执行模式、超时、failOnError 等）
     * @param nodes       当前阶段的节点有序列表（Registry 启动时已按 order 排好序）
     * @param context     贯穿全链的任务上下文
     * @return 阶段执行结果（包含阶段名、耗时、成功/失败状态、错误信息）
     */
    public <I, O> PhaseExecutionResult executePhase(PhaseConfig phaseConfig, List<TaskNodeHolder> nodes, TaskContext<I, O> context) {
        long startTime = System.currentTimeMillis();
        try {
            // ① 空节点快速返回：当前阶段没配置节点（Registry 该 Phase 分组为空），直接跳过
            if (nodes == null || nodes.isEmpty()) {
                return PhaseExecutionResult.success(phaseConfig.getPhase(), startTime, System.currentTimeMillis());
            }

            // ② 按阶段配置的 ExecutionMode 分流：并行 or 串行
            if (phaseConfig.getExecutionMode() == ExecutionMode.PARALLEL) {
                executeParallel(phaseConfig, nodes, context);
            } else {
                executeSerial(phaseConfig, nodes, context);
            }

            // ③ 阶段级错误判定：context 标记了失败 && phaseConfig 配置了 failOnError → 返回失败结果
            if (!context.isSuccess() && phaseConfig.isFailOnError()) {
                return PhaseExecutionResult.fail(phaseConfig.getPhase(), startTime, System.currentTimeMillis(), context.getErrorMessage(), context.getException());
            }
            return PhaseExecutionResult.success(phaseConfig.getPhase(), startTime, System.currentTimeMillis());
        } catch (Exception e) {
            // ④ 异常兜底：串行/并行过程中抛未捕获异常 → 标记 context 失败 + 返回失败结果
            context.markFailed("Phase execution failed: " + phaseConfig.getPhase().getDesc(), e);
            return PhaseExecutionResult.fail(phaseConfig.getPhase(), startTime, System.currentTimeMillis(), e.getMessage(), e);
        }
    }

    /**
     * 串行执行：按节点 order 顺序依次派发
     * <p>
     * 核心机制：
     * <ul>
     *   <li>每轮循环开头检查 context.terminateChain —— 上一个不可跳过的节点失败会置为 true，立即 break</li>
     *   <li>每个节点派发前重置 skipCurrentNode 标志，避免上一个节点的跳过状态残留</li>
     *   <li>节点执行失败时，需要同时满足「phaseConfig.failOnError」+「节点自身 skippable=false」才会触发链路终止</li>
     * </ul>
     * </p>
     */
    private <I, O> void executeSerial(PhaseConfig phaseConfig, List<TaskNodeHolder> nodes, TaskContext<I, O> context) {
        for (TaskNodeHolder node : nodes) {
            // 每轮开头检查链路生死开关，TaskChainEngine 层面也会在阶段之间检查，这里是阶段内部的细粒度检查
            if (context.isTerminateChain()) {
                break;
            }
            // 重置跳过标志：避免上一个节点如果被 skip 了，影响当前节点的正常执行
            context.setSkipCurrentNode(false);
            // 同步派发：TaskDispatcher 根据节点 taskType 选线程池，阻塞等节点 execute 返回
            taskDispatcher.dispatchNodeSync(node, context);
            // 节点失败 + 阶段要求 failOnError + 节点不可跳过 → 标记链路终止，后续节点不再执行
            if (!context.isSuccess() && phaseConfig.isFailOnError() && !node.getAnnotation().skippable()) {
                context.markTerminate();
                break;
            }
        }
    }

    /**
     * 并行执行：所有节点同时扔给线程池，全部完成后合并结果
     * <p>
     * 核心机制：
     * <ul>
     *   <li>每个节点执行时使用 context.copy() 副本 —— 并行节点间不能共享同一个 context，否则 setAttribute 会并发覆盖</li>
     *   <li>用 CompletableFuture.allOf 等所有节点完成（阻塞等待，配合 waitForPhase 的超时控制）</li>
     *   <li>等待结束后，逐个从 future.get() 拿节点副本，把错误 + 数据合并回主 context</li>
     * </ul>
     * </p>
     */
    private <I, O> void executeParallel(PhaseConfig phaseConfig, List<TaskNodeHolder> nodes, TaskContext<I, O> context) throws Exception {
        // ① 为每个节点提交异步任务，每个节点用 context.copy() 独立副本，避免并行写同一个 context 导致数据竞争
        List<CompletableFuture<TaskContext<I, O>>> futures = new ArrayList<>(nodes.size());
        for (TaskNodeHolder node : nodes) {
            futures.add(taskDispatcher.dispatchNodeAsync(node, context.copy()));
        }

        // ② 用 allOf 把 N 个 future 绑成一个，配合 waitForPhase 统一等待 + 超时控制
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        waitForPhase(phaseConfig, all);

        // ③ 逐个拿节点副本，把错误状态 + setAttribute 的数据合并回主 context（顺序和原 nodes 列表一致）
        for (CompletableFuture<TaskContext<I, O>> future : futures) {
            TaskContext<I, O> nodeContext = future.get();
            context.mergeError(nodeContext);   // 合并错误状态：节点失败了要让主 context 知道
            context.mergeData(nodeContext);   // 合并数据：节点 setAttribute 的中间结果要带回主 context
        }
    }

    /**
     * 等待阶段内所有并行节点完成，带超时控制
     * <p>
     * 超时来源是 PhaseConfig.timeout（0 表示不限制，一直等），超时后抛 IllegalStateException，
     * 被 executePhase 的 catch 兜底捕获 → context.markFailed + 返回 PhaseExecutionResult.fail
     * </p>
     */
    private void waitForPhase(PhaseConfig phaseConfig, CompletableFuture<Void> all) throws Exception {
        // timeout <= 0 表示不限制，直接无超时阻塞等待
        if (phaseConfig.getTimeout() <= 0L) {
            all.get();
            return;
        }
        try {
            // 带超时等待，超时后抛 TimeoutException → 包装成 IllegalStateException 往上抛
            all.get(phaseConfig.getTimeout(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Phase timed out: " + phaseConfig.getPhase().getDesc(), e);
        }
    }
}