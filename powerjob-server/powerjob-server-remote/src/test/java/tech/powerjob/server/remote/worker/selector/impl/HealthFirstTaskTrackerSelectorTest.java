package tech.powerjob.server.remote.worker.selector.impl;

import org.junit.jupiter.api.Test;
import tech.powerjob.common.model.SystemMetrics;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link HealthFirstTaskTrackerSelector} defensive non-empty handling.
 *
 * @author PowerJob
 */
class HealthFirstTaskTrackerSelectorTest {

    private final HealthFirstTaskTrackerSelector selector = new HealthFirstTaskTrackerSelector();

    private static WorkerInfo worker(String address, int cpuProcessors, double cpuLoad, double jvmMax, double jvmUsed) {
        SystemMetrics metrics = new SystemMetrics();
        metrics.setCpuProcessors(cpuProcessors);
        metrics.setCpuLoad(cpuLoad);
        metrics.setJvmMaxMemory(jvmMax);
        metrics.setJvmUsedMemory(jvmUsed);
        WorkerInfo workerInfo = new WorkerInfo();
        workerInfo.setAddress(address);
        workerInfo.setSystemMetrics(metrics);
        return workerInfo;
    }

    @Test
    void picksHighestScore() {
        JobInfoDO job = new JobInfoDO();
        job.setId(1L);
        WorkerInfo best = worker("10.0.0.2:1", 8, 1, 4, 1);
        WorkerInfo worse = worker("10.0.0.1:1", 8, 1, 4, 3);
        assertEquals(best, selector.select(job, null, java.util.Arrays.asList(worse, best)));
    }

    @Test
    void emptyCandidatesFailFastWithClearMessage() {
        JobInfoDO job = new JobInfoDO();
        job.setId(9L);
        assertThrows(IllegalStateException.class, () -> selector.select(job, null, Collections.emptyList()));
        assertThrows(IllegalStateException.class, () -> selector.select(job, null, null));
    }
}
