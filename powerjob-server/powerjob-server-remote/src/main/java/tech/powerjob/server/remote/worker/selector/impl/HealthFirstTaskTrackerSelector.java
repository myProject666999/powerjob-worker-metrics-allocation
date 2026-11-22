package tech.powerjob.server.remote.worker.selector.impl;

import com.google.common.collect.Lists;
import org.springframework.stereotype.Component;
import tech.powerjob.common.enums.DispatchStrategy;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.persistence.remote.model.InstanceInfoDO;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.remote.worker.WorkerClusterQueryService;
import tech.powerjob.server.remote.worker.selector.TaskTrackerSelector;

import java.util.List;

/**
 * HealthFirst
 *
 * @author （疑似）新冠帕鲁
 * @since 2024/2/24
 */
@Component
public class HealthFirstTaskTrackerSelector implements TaskTrackerSelector {

    @Override
    public DispatchStrategy strategy() {
        return DispatchStrategy.HEALTH_FIRST;
    }

    @Override
    public WorkerInfo select(JobInfoDO jobInfoDO, InstanceInfoDO instanceInfoDO, List<WorkerInfo> availableWorkers) {
        // 上游（WorkerClusterQueryService）过滤后已保证非空；这里再防御一次，返回明确错误而非 IndexOutOfBounds
        if (availableWorkers == null || availableWorkers.isEmpty()) {
            throw new IllegalStateException("no available worker to select for job(id=" + jobInfoDO.getId() + ")");
        }
        // 语义不变（健康度/评分优先），复用统一的评分降序 + 地址平局比较器，避免重复实现与 NPE
        List<WorkerInfo> workers = Lists.newArrayList(availableWorkers);
        workers.sort(WorkerClusterQueryService::compareByScoreThenAddress);
        return workers.get(0);
    }
}
