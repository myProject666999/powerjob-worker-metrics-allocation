package tech.powerjob.server.remote.worker.filter;

import tech.powerjob.common.model.SystemMetrics;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.common.module.WorkerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * filter worker by system metric
 *
 * <p>Memory here is the remaining JVM heap ({@code jvmMaxMemory - jvmUsedMemory}), not the free
 * memory of the physical machine. See {@link SystemMetrics#available(double, double, double)}.
 *
 * <p>Per-worker detail is logged at debug level only; the per-dispatch summary is emitted by
 * {@code WorkerClusterQueryService}.
 *
 * @author tjq
 * @since 2021/2/19
 */
@Slf4j
@Component
public class SystemMetricsWorkerFilter implements WorkerFilter {

    @Override
    public boolean filter(WorkerInfo workerInfo, JobInfoDO jobInfo) {
        int reasons = unavailableReasons(workerInfo, jobInfo);
        if (reasons == 0) {
            return false;
        }
        log.debug("[Job-{}] filter worker[{}] because reasons={} do not meet the requirements, metrics={}",
                jobInfo.getId(), workerInfo.getAddress(), reasons, workerInfo.getSystemMetrics());
        return true;
    }

    /**
     * The unmet requirements of a worker, as a bit mask of
     * {@link SystemMetrics#UNAVAILABLE_CPU}, {@link SystemMetrics#UNAVAILABLE_MEMORY} and
     * {@link SystemMetrics#UNAVAILABLE_DISK}. Returns {@code 0} when the worker can be used.
     *
     * @param workerInfo worker info
     * @param jobInfo    job info
     * @return {@code 0} when available, otherwise the OR of the unmet resource flags
     */
    public static int unavailableReasons(WorkerInfo workerInfo, JobInfoDO jobInfo) {
        SystemMetrics metrics = workerInfo.getSystemMetrics();
        if (metrics == null) {
            // no metrics reported yet: do not reject on resource grounds
            return 0;
        }
        return metrics.unavailableReasons(jobInfo.getMinCpuCores(), jobInfo.getMinMemorySpace(), jobInfo.getMinDiskSpace());
    }
}
