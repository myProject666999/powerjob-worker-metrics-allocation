package tech.powerjob.server.remote.worker.filter;

import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.common.module.WorkerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * filter disconnected worker
 *
 * @author tjq
 * @since 2021/2/19
 */
@Slf4j
@Component
public class DisconnectedWorkerFilter implements WorkerFilter {

    @Override
    public boolean filter(WorkerInfo workerInfo, JobInfoDO jobInfo) {
        boolean timeout = workerInfo.timeout();
        if (timeout) {
            // 逐台明细走 debug，派发级别汇总由 WorkerClusterQueryService 输出
            log.debug("[Job-{}] filter worker[{}] due to timeout(lastActiveTime={},lastActiveTimeWorkerTime={})", jobInfo.getId(), workerInfo.getAddress(), workerInfo.getLastActiveTime(), workerInfo.getLastActiveWorkerTime());
        }
        return timeout;
    }
}
