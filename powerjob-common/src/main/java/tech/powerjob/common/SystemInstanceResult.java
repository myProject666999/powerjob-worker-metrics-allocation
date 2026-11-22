package tech.powerjob.common;

/**
 * 系统生成的任务实例运行结果
 *
 * @author tjq
 * @since 2020/4/11
 */
public class SystemInstanceResult {

    private SystemInstanceResult() {

    }

    /* *********** 普通instance 专用 *********** */

    /**
     * 同时运行的任务实例数过多
     */
    public static final String TOO_MANY_INSTANCES = "too many instances(%d>%d)";
    /**
     *  无可用worker
     */
    public static final String NO_WORKER_AVAILABLE = "no worker available";
    /**
     * 应用下没有任何在线 worker（没有上报过心跳，或全部已超过心跳超时）。
     * 与 {@link #NO_WORKER_AVAILABLE} 一样会写入实例结果并触发失败告警。
     */
    public static final String NO_ONLINE_WORKER = "no online worker";
    /**
     * 存在在线 worker，但没有任何一台满足任务的 CPU / 内存 / 磁盘（或指定机器）要求。
     * 与 {@link #NO_WORKER_AVAILABLE} 一样会写入实例结果并触发失败告警。
     */
    public static final String NO_WORKER_MEETS_REQUIREMENTS = "no worker meets the job requirements";
    /**
     * 存在满足资源要求的 worker，但全部处于超载状态。
     * 该场景保持原语义：取消本次派发、等待下一轮重新派发，不会把实例置为 FAILED，
     * 因此不会产生失败告警。仅作为日志中的稳定标记。
     */
    public static final String ALL_WORKERS_OVERLOAD = "all workers are overloaded";
    /**
     * 任务执行超时
     */
    public static final String INSTANCE_EXECUTE_TIMEOUT = "instance execute timeout";
    /**
     * 任务执行超时，成功打断任务
     */
    public static final String INSTANCE_EXECUTE_TIMEOUT_INTERRUPTED = "instance execute timeout,interrupted success";
    /**
     * 任务执行超时，强制终止任务
     */
    public static final String INSTANCE_EXECUTE_TIMEOUT_FORCE_STOP= "instance execute timeout,force stop success";

    /**
     * 用户手动停止任务，成功打断任务
     */
    public static final String USER_STOP_INSTANCE_INTERRUPTED= "user stop instance,interrupted success";
    /**
     * 用户手动停止任务，被系统强制终止
     */
    public static final String USER_STOP_INSTANCE_FORCE_STOP= "user stop instance,force stop success";
    /**
     * 创建根任务失败
     */
    public static final String TASK_INIT_FAILED = "create root task failed";
    /**
     * 未知错误
     */
    public static final String UNKNOWN_BUG = "unknown bug";
    /**
     * TaskTracker 长时间未上报
     */
    public static final String REPORT_TIMEOUT = "worker report timeout, maybe TaskTracker down";
    public static final String CAN_NOT_FIND_JOB_INFO = "can't find job info";

    /* *********** workflow 专用 *********** */

    public static final String MIDDLE_JOB_FAILED = "middle job failed";
    public static final String MIDDLE_JOB_STOPPED = "middle job stopped by user";
    public static final String CAN_NOT_FIND_JOB = "can't find some job";
    public static final String CAN_NOT_FIND_NODE = "can't find some node";
    public static final String ILLEGAL_NODE = "illegal node info";

    /**
     * 没有启用的节点
     */
    public static final String NO_ENABLED_NODES = "no enabled nodes";
    /**
     * 被用户手动停止
     */
    public static final String STOPPED_BY_USER = "stopped by user";
    public static final String CANCELED_BY_USER = "canceled by user";

    /**
     * 无效 DAG
     */
    public static final String INVALID_DAG = "invalid dag";

    /**
     * 被禁用的节点
     */
    public static final String DISABLE_NODE = "disable node";
    /**
     * 标记为成功的节点
     */
    public static final String MARK_AS_SUCCESSFUL_NODE = "mark as successful node";
}
