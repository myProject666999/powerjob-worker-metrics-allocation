package tech.powerjob.server.remote.worker;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.powerjob.common.model.DeployedContainerInfo;
import tech.powerjob.common.model.SystemMetrics;
import tech.powerjob.server.common.PowerJobServerConfigKey;
import tech.powerjob.server.common.SJ;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.remote.server.redirector.DesignateServer;
import tech.powerjob.server.remote.worker.filter.SystemMetricsWorkerFilter;
import tech.powerjob.server.remote.worker.filter.WorkerFilter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 获取 worker 集群信息
 *
 * @author tjq
 * @since 2021/2/19
 */
@Slf4j
@Service
public class WorkerClusterQueryService {

    /**
     * 过滤日志中放入 debug 的被滤地址样本数量上限（完整计数在 info 汇总里）。
     */
    private static final int FILTERED_SAMPLE_SIZE = 5;

    private final List<WorkerFilter> workerFilters;

    /**
     * 指标快照最大年龄（毫秒），小于等于 0 表示不启用年龄影响。
     */
    private final long systemMetricsMaxAgeMs;

    @Autowired
    public WorkerClusterQueryService(List<WorkerFilter> workerFilters,
                                     @Value("${" + PowerJobServerConfigKey.WORKER_SYSTEM_METRICS_MAX_AGE_MS + ":0}") long systemMetricsMaxAgeMs) {
        this.workerFilters = workerFilters;
        this.systemMetricsMaxAgeMs = systemMetricsMaxAgeMs;
    }

    public WorkerClusterQueryService(List<WorkerFilter> workerFilters) {
        this(workerFilters, 0L);
    }

    /**
     * get worker for job
     *
     * @param jobInfo job
     * @return worker cluster info, sorted by metrics desc
     */
    public List<WorkerInfo> geAvailableWorkers(JobInfoDO jobInfo) {
        return geAvailableWorkersWithDetail(jobInfo).getWorkers();
    }

    /**
     * get worker for job, together with the information needed to explain an empty result.
     *
     * <p>Ordering is deterministic for the same candidate set: designated workers first, then
     * workers whose metrics snapshot is not older than the configured age, then score desc,
     * then address asc. The result is truncated to {@code maxWorkerCount} only after sorting, so a
     * designated worker can never be truncated away just because of a lower score.
     *
     * @param jobInfo job
     * @return available workers and the online worker count
     */
    public AvailableWorkers geAvailableWorkersWithDetail(JobInfoDO jobInfo) {

        Map<String, WorkerInfo> onlineWorkers = getWorkerInfosByAppId(jobInfo.getAppId());
        int totalOnline = onlineWorkers.size();

        List<WorkerInfo> candidates = Lists.newLinkedList();
        // TreeMap 让汇总日志中的过滤器计数顺序稳定，便于比对与告警
        Map<String, Integer> filteredByFilter = Maps.newTreeMap();
        List<String> filteredSamples = Lists.newArrayList();
        int cpuRejected = 0;
        int memoryRejected = 0;
        int diskRejected = 0;

        // 过滤不符合要求的机器，并按过滤器类名统计归因（执行顺序决定归因归属）
        for (WorkerInfo workerInfo : onlineWorkers.values()) {
            WorkerFilter rejectingFilter = null;
            for (WorkerFilter workerFilter : workerFilters) {
                if (workerFilter.filter(workerInfo, jobInfo)) {
                    rejectingFilter = workerFilter;
                    break;
                }
            }
            if (rejectingFilter == null) {
                candidates.add(workerInfo);
                continue;
            }
            filteredByFilter.merge(rejectingFilter.getClass().getSimpleName(), 1, Integer::sum);
            if (filteredSamples.size() < FILTERED_SAMPLE_SIZE) {
                filteredSamples.add(workerInfo.getAddress());
            }
            if (rejectingFilter instanceof SystemMetricsWorkerFilter) {
                int reasons = SystemMetricsWorkerFilter.unavailableReasons(workerInfo, jobInfo);
                if ((reasons & SystemMetrics.UNAVAILABLE_CPU) != 0) {
                    cpuRejected++;
                }
                if ((reasons & SystemMetrics.UNAVAILABLE_MEMORY) != 0) {
                    memoryRejected++;
                }
                if ((reasons & SystemMetrics.UNAVAILABLE_DISK) != 0) {
                    diskRejected++;
                }
            }
        }

        // 先排序（指定机器 > 快照新鲜 > 评分降序 > 地址字典序），再截断
        sortCandidates(candidates, parseDesignatedWorkers(jobInfo.getDesignatedWorkers()), systemMetricsMaxAgeMs, System.currentTimeMillis());
        int availableBeforeTruncate = candidates.size();

        // 限定集群大小（0/null 代表不限制）
        Integer maxWorkerCount = jobInfo.getMaxWorkerCount();
        if (maxWorkerCount != null && maxWorkerCount > 0 && candidates.size() > maxWorkerCount) {
            candidates = Lists.newArrayList(candidates.subList(0, maxWorkerCount));
        }

        // 每次派发只打一条汇总，避免每台 worker 一条 info
        log.info("[WorkerClusterQueryService] job[{}] app[{}] dispatch candidate summary: online={}, filtered={}, available={}, returned={}, filteredByFilter={}, cpuRejected={}, memoryRejected={}, diskRejected={}, maxWorkerCount={}",
                jobInfo.getId(), jobInfo.getAppId(), totalOnline, totalOnline - availableBeforeTruncate, availableBeforeTruncate,
                candidates.size(), filteredByFilter, cpuRejected, memoryRejected, diskRejected, maxWorkerCount);
        if (!filteredSamples.isEmpty()) {
            log.debug("[WorkerClusterQueryService] job[{}] first {} filtered workers: {}", jobInfo.getId(), filteredSamples.size(), filteredSamples);
        }

        return new AvailableWorkers(candidates, totalOnline);
    }

    @DesignateServer
    public List<WorkerInfo> getAllWorkers(Long appId) {
        List<WorkerInfo> workers = Lists.newLinkedList(getWorkerInfosByAppId(appId).values());
        // 评分降序，地址字典序打破平局，保证同一集合输出稳定
        workers.sort(WorkerClusterQueryService::compareByScoreThenAddress);
        return workers;
    }

    /**
     * get all alive workers
     *
     * @param appId appId
     * @return alive workers
     */
    @DesignateServer
    public List<WorkerInfo> getAllAliveWorkers(Long appId) {
        List<WorkerInfo> workers = Lists.newLinkedList(getWorkerInfosByAppId(appId).values());
        workers.removeIf(WorkerInfo::timeout);
        return workers;
    }

    /**
     * Gets worker info by address.
     *
     * @param appId   the app id
     * @param address the address
     * @return the worker info by address
     */
    public Optional<WorkerInfo> getWorkerInfoByAddress(Long appId, String address) {
        // this may cause NPE while address value is null .
        final Map<String, WorkerInfo> workerInfosByAppId = getWorkerInfosByAppId(appId);
        //add null check for both workerInfos Map and  address
        if (null != workerInfosByAppId && null != address) {
            return Optional.ofNullable(workerInfosByAppId.get(address));
        }
        return Optional.empty();
    }

    public Map<Long, ClusterStatusHolder> getAppId2ClusterStatus() {
        return WorkerClusterManagerService.getAppId2ClusterStatus();
    }

    /**
     * 获取某个应用容器的部署情况
     *
     * @param appId       应用ID
     * @param containerId 容器ID
     * @return 部署情况
     */
    public List<DeployedContainerInfo> getDeployedContainerInfos(Long appId, Long containerId) {
        ClusterStatusHolder clusterStatusHolder = getAppId2ClusterStatus().get(appId);
        if (clusterStatusHolder == null) {
            return Collections.emptyList();
        }
        return clusterStatusHolder.getDeployedContainerInfos(containerId);
    }

    private Map<String, WorkerInfo> getWorkerInfosByAppId(Long appId) {
        ClusterStatusHolder clusterStatusHolder = getAppId2ClusterStatus().get(appId);
        if (clusterStatusHolder == null) {
            log.warn("[WorkerManagerService] can't find any worker for app(appId={}) yet.", appId);
            return Collections.emptyMap();
        }
        return clusterStatusHolder.getAllWorkers();
    }

    /**
     * 按“指定机器 → 快照新鲜 → 评分降序 → 地址字典序”排序。package-private 便于测试。
     *
     * @param workers           候选 worker，原地排序
     * @param designatedWorkers 指定机器/标签集合
     * @param maxAgeMs          指标快照最大年龄，小于等于 0 时不启用年龄影响
     * @param now               当前时间戳
     */
    static void sortCandidates(List<WorkerInfo> workers, Set<String> designatedWorkers, long maxAgeMs, long now) {
        workers.sort((o1, o2) -> {
            boolean designated1 = isDesignated(o1, designatedWorkers);
            boolean designated2 = isDesignated(o2, designatedWorkers);
            if (designated1 != designated2) {
                return designated1 ? -1 : 1;
            }
            if (maxAgeMs > 0) {
                boolean stale1 = isStale(o1, maxAgeMs, now);
                boolean stale2 = isStale(o2, maxAgeMs, now);
                if (stale1 != stale2) {
                    // 过旧快照后置但不剔除，避免心跳抖动清空候选
                    return stale1 ? 1 : -1;
                }
            }
            return compareByScoreThenAddress(o1, o2);
        });
    }

    static Set<String> parseDesignatedWorkers(String designatedWorkers) {
        if (StringUtils.isEmpty(designatedWorkers)) {
            return Collections.emptySet();
        }
        return Sets.newHashSet(SJ.COMMA_SPLITTER.splitToList(designatedWorkers));
    }

    private static boolean isDesignated(WorkerInfo workerInfo, Set<String> designatedWorkers) {
        if (designatedWorkers.isEmpty()) {
            return false;
        }
        return designatedWorkers.contains(workerInfo.getAddress()) || designatedWorkers.contains(workerInfo.getTag());
    }

    /**
     * 指标快照是否过旧。{@code lastActiveTime <= 0} 视为未知年龄，按过旧处理。
     */
    static boolean isStale(WorkerInfo workerInfo, long maxAgeMs, long now) {
        long lastActiveTime = workerInfo.getLastActiveTime();
        return lastActiveTime <= 0 || now - lastActiveTime > maxAgeMs;
    }

    /**
     * 评分降序，地址字典序打破平局（null 指标视为最低分），保证同一候选集合输出与输入顺序无关。
     */
    public static int compareByScoreThenAddress(WorkerInfo o1, WorkerInfo o2) {
        int scoreCompare = Integer.compare(scoreOf(o2), scoreOf(o1));
        return scoreCompare != 0 ? scoreCompare : compareAddress(o1, o2);
    }

    private static int compareAddress(WorkerInfo o1, WorkerInfo o2) {
        return Comparator.nullsLast(String::compareTo).compare(o1.getAddress(), o2.getAddress());
    }

    /**
     * 评分为 null 指标时取最小值，保证有指标的 worker 始终排在前面。
     */
    private static int scoreOf(WorkerInfo workerInfo) {
        SystemMetrics metrics = workerInfo.getSystemMetrics();
        return metrics == null ? Integer.MIN_VALUE : metrics.calculateScore();
    }

    /**
     * 一次派发查询的结果，用于区分“无在线 worker”与“有 worker 但全部不满足要求”。
     */
    public static class AvailableWorkers {

        private final List<WorkerInfo> workers;
        private final int totalOnline;

        public AvailableWorkers(List<WorkerInfo> workers, int totalOnline) {
            this.workers = workers;
            this.totalOnline = totalOnline;
        }

        public List<WorkerInfo> getWorkers() {
            return workers;
        }

        public int getTotalOnline() {
            return totalOnline;
        }

        public boolean isNoOnlineWorker() {
            return totalOnline == 0;
        }

        public boolean isAllFilteredOut() {
            return totalOnline > 0 && workers.isEmpty();
        }
    }
}
