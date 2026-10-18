package com.hmdp.shared.domain.taskchain.service;

import com.hmdp.shared.domain.taskchain.enums.TaskTypeEnum;
import com.hmdp.shared.domain.taskchain.model.TaskContext;
import com.hmdp.shared.domain.taskchain.model.TaskNodeHandler;
import com.hmdp.shared.domain.taskchain.model.TaskNodeHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * 任务分发器
 * <p>
 * 根据节点的 TaskTypeEnum 选择对应的线程池，将节点提交异步执行：
 * <ul>
 *   <li>IO_BOUND → ioExecutor（线程数较多，适合等待数据库/缓存/RPC）</li>
 *   <li>CPU_BOUND → cpuExecutor（线程数 = CPU 核数，避免过多上下文切换）</li>
 * </ul>
 * dispatchNodeSync 是同步版本（内部等待 Future 完成），用于串行阶段；
 * dispatchNodeAsync 是异步版本，返回 CompletableFuture，用于并行阶段。
 * </p>
 */
public class TaskDispatcher {

    /**
     * CPU 密集型线程池。
     * 线程数严格控制在 CPU 核数 + 1，避免过多上下文切换拖慢计算。
     * 由 SharedEngineAutoConfiguration 注入。
     */
    private final Executor cpuBoundExecutor;

    /**
     * IO 密集型线程池。
     * 线程数 >> CPU 核数（通常 32~128），让大量 IO 等待任务同时挂起互不阻塞。
     * 由 SharedEngineAutoConfiguration 注入。
     */
    private final Executor ioBoundExecutor;

    /**
     * 构造函数，注入两个独立线程池。
     *
     * @param cpuBoundExecutor CPU 密集型线程池
     * @param ioBoundExecutor  IO 密集型线程池
     */
    public TaskDispatcher(Executor cpuBoundExecutor, Executor ioBoundExecutor) {
        this.cpuBoundExecutor = cpuBoundExecutor;
        this.ioBoundExecutor = ioBoundExecutor;
    }

    /**
     * 按 TaskTypeEnum 路由到对应的线程池。
     * CPU_BOUND 走 cpuBoundExecutor，其余一律走 ioBoundExecutor。
     *
     * @param taskType 节点声明的任务类型（来自 @TaskNode.taskType()）
     * @return 匹配的 Executor
     */
    public Executor getExecutor(TaskTypeEnum taskType) {
        if (taskType == TaskTypeEnum.CPU_BOUND) {
            return cpuBoundExecutor;
        }
        return ioBoundExecutor;
    }

    /**
     * 同步分发节点执行（阻塞等待完成）。
     * 内部直接复用 dispatchNodeAsync，然后调 .get() 阻塞拿结果。
     * 用于串行阶段 —— PhaseExecutionEngine.executeSerial 就是这么调的。
     * <p>
     * InterruptedException 处理：恢复中断标志，markFailed 后正常返回（引擎后续会检查 success 决定是否终止链路）。
     * ExecutionException 处理：markFailed 后正常返回，异常根因会被合并到 context。
     * </p>
     *
     * @param holder  封装好的节点 Bean + @TaskNode 元数据
     * @param context 任务上下文（串行模式下直接用主 context，不需要副本）
     */
    public <I, O> void dispatchNodeSync(TaskNodeHolder holder, TaskContext<I, O> context) {
        // 复用异步版本，.get() 阻塞等节点执行完
        try {
            dispatchNodeAsync(holder, context).get();
        } catch (InterruptedException e) {
            // 线程被外部中断：恢复中断标志（让上层能感知到中断），再标记失败
            Thread.currentThread().interrupt();
            context.markFailed("Node interrupted: " + holder.getAnnotation().nodeId(), e);
        } catch (ExecutionException e) {
            // 节点内部抛了异常：getCause() 拿真实异常，标记失败
            context.markFailed("Node execution failed: " + holder.getAnnotation().nodeId(), e.getCause());
        }
    }

    /**
     * 异步分发节点执行（立即返回 CompletableFuture，不阻塞调用方）。
     * 用于并行阶段 —— PhaseExecutionEngine.executeParallel 为每个节点各调一次，收集成 List 后 allOf 统一等待。
     * <p>
     * 核心执行逻辑：
     * <ol>
     *   <li>根据 holder.getTaskType() 拿到匹配的线程池（IO / CPU）</li>
     *   <li>CompletableFuture.supplyAsync 把节点 execute 方法扔给线程池</li>
     *   <li>执行前设置 currentNodeId（日志追踪用）</li>
     *   <li>执行中抛 Throwable → markFailed 写错误 + 不可跳过节点自动 markTerminate</li>
     *   <li>无论成功还是失败，最终返回同一个 context（调用方拿到后 mergeError / mergeData）</li>
     * </ol>
     * </p>
     *
     * @param holder  封装好的节点 Bean + @TaskNode 元数据
     * @param context 任务上下文（并行模式下是 context.copy() 的副本，避免多线程写同一个对象）
     * @return CompletableFuture，执行完后里面装的是同一个 context（可能已被节点改了 attributes / output）
     */
    @SuppressWarnings("unchecked")
    public <I, O> CompletableFuture<TaskContext<I, O>> dispatchNodeAsync(TaskNodeHolder holder, TaskContext<I, O> context) {
        // 按节点声明的 taskType（IO_BOUND / CPU_BOUND）路由到对应线程池
        Executor executor = getExecutor(holder.getTaskType());
        // supplyAsync：把节点执行逻辑扔给线程池，立即返回 Future（不阻塞调用方）
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 执行前先设 currentNodeId，方便日志追踪当前哪个节点在跑
                context.setCurrentNodeId(holder.getAnnotation().nodeId());
                // ★ 真正调节点的 execute 方法：holder.getHandler() 是 Spring 扫描到的节点 Bean
                // unchecked cast 安全：Registry.wrapHandlers 时已匹配过泛型
                ((TaskNodeHandler<I, O>) holder.getHandler()).execute(context);
                // 节点跑完，返回被改过的 context（attributes / output 可能已写入数据）
                return context;
            } catch (Throwable throwable) {
                // 节点抛了异常：先 markFailed 写错误信息到 context
                context.markFailed("Node execution failed: " + holder.getAnnotation().nodeId(), throwable);
                // 不可跳过的节点失败 → 自动 markTerminate 熔断整条链路
                // 可跳过的节点失败 → 只记日志，不熔断，后续阶段继续跑
                if (!holder.getAnnotation().skippable()) {
                    context.markTerminate();
                }
                // 即使失败也返回 context，调用方拿到后能通过 mergeError 合并失败状态
                return context;
            }
        }, executor);
    }
}