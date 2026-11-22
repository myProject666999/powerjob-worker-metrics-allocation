package tech.powerjob.server.core;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import tech.powerjob.common.SystemInstanceResult;
import tech.powerjob.common.enums.InstanceStatus;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.server.common.Holder;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.core.instance.InstanceManager;
import tech.powerjob.server.core.instance.InstanceMetadataService;
import tech.powerjob.server.persistence.remote.model.InstanceInfoDO;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.persistence.remote.repository.InstanceInfoRepository;
import tech.powerjob.server.remote.transporter.TransportService;
import tech.powerjob.server.remote.worker.WorkerClusterQueryService;
import tech.powerjob.server.remote.worker.selector.TaskTrackerSelectorService;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link DispatchService} reports distinct instance results when candidates disappear,
 * without touching a database or a real worker cluster.
 *
 * @author PowerJob
 */
class DispatchServiceNoWorkerReasonTest {

    private final TransportService transport = mock(TransportService.class);
    private final WorkerClusterQueryService workers = mock(WorkerClusterQueryService.class);
    private final InstanceManager instanceManager = mock(InstanceManager.class);
    private final InstanceMetadataService metadata = mock(InstanceMetadataService.class);
    private final InstanceInfoRepository instances = mock(InstanceInfoRepository.class);
    private final TaskTrackerSelectorService selector = mock(TaskTrackerSelectorService.class);

    private final DispatchService dispatchService = new DispatchService(transport, workers, instanceManager, metadata, instances, selector);

    private InstanceInfoDO waitingInstance() {
        InstanceInfoDO instance = new InstanceInfoDO();
        instance.setInstanceId(100L);
        instance.setId(200L);
        instance.setJobId(7L);
        instance.setAppId(1L);
        instance.setStatus(InstanceStatus.WAITING_DISPATCH.getV());
        when(instances.findByInstanceId(100L)).thenReturn(instance);
        return instance;
    }

    private JobInfoDO job() {
        JobInfoDO job = new JobInfoDO();
        job.setId(7L);
        job.setAppId(1L);
        job.setMaxInstanceNum(0);
        job.setTimeExpressionType(TimeExpressionType.API.getV());
        job.setMaxWorkerCount(0);
        return job;
    }

    private void stubCandidates(int totalOnline, List<WorkerInfo> workers) {
        when(this.workers.geAvailableWorkersWithDetail(any()))
                .thenReturn(new WorkerClusterQueryService.AvailableWorkers(workers, totalOnline));
    }

    @Test
    void noOnlineWorkerReportsDedicatedResult() {
        waitingInstance();
        stubCandidates(0, Lists.newArrayList());

        dispatchService.dispatch(job(), 100L, Optional.empty(), Optional.empty());

        verify(instances).update4TriggerFailed(eq(100L), eq(InstanceStatus.FAILED.getV()), anyLong(), anyLong(),
                anyString(), eq(SystemInstanceResult.NO_ONLINE_WORKER), any(Date.class));
        verify(instanceManager).processFinishedInstance(100L, null, InstanceStatus.FAILED, SystemInstanceResult.NO_ONLINE_WORKER);
    }

    @Test
    void allWorkersFilteredReportsRequirementsResult() {
        waitingInstance();
        stubCandidates(3, Lists.newArrayList());

        dispatchService.dispatch(job(), 100L, Optional.empty(), Optional.empty());

        verify(instances).update4TriggerFailed(eq(100L), eq(InstanceStatus.FAILED.getV()), anyLong(), anyLong(),
                anyString(), eq(SystemInstanceResult.NO_WORKER_MEETS_REQUIREMENTS), any(Date.class));
        verify(instanceManager).processFinishedInstance(100L, null, InstanceStatus.FAILED, SystemInstanceResult.NO_WORKER_MEETS_REQUIREMENTS);
    }

    @Test
    void allOverloadCancelsWithoutFailing() {
        waitingInstance();
        WorkerInfo overloaded = new WorkerInfo();
        overloaded.setAddress("10.0.0.1:1");
        overloaded.setOverloading(true);
        stubCandidates(1, Lists.newArrayList(overloaded));

        Holder<Boolean> overloadFlag = new Holder<>(false);
        dispatchService.dispatch(job(), 100L, Optional.empty(), Optional.of(overloadFlag));

        assertTrue(overloadFlag.get());
        verify(instances, never()).update4TriggerFailed(anyLong(), any(Integer.class), anyLong(), anyLong(), anyString(), anyString(), any(Date.class));
        verify(instanceManager, never()).processFinishedInstance(anyLong(), any(), any(), anyString());
        verify(transport, never()).tell(any(), any(), any());
    }
}
