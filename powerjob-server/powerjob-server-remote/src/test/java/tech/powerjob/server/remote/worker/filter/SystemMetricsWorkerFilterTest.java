package tech.powerjob.server.remote.worker.filter;

import org.junit.jupiter.api.Test;
import tech.powerjob.common.model.SystemMetrics;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SystemMetricsWorkerFilter}. Workers and metrics are constructed directly;
 * no database or worker runtime is involved.
 *
 * @author PowerJob
 */
class SystemMetricsWorkerFilterTest {

    private final SystemMetricsWorkerFilter filter = new SystemMetricsWorkerFilter();

    private static SystemMetrics metrics(int cpuProcessors, double cpuLoad, double jvmMax, double jvmUsed, double diskTotal, double diskUsed) {
        SystemMetrics m = new SystemMetrics();
        m.setCpuProcessors(cpuProcessors);
        m.setCpuLoad(cpuLoad);
        m.setJvmMaxMemory(jvmMax);
        m.setJvmUsedMemory(jvmUsed);
        m.setDiskTotal(diskTotal);
        m.setDiskUsed(diskUsed);
        return m;
    }

    private static WorkerInfo worker(SystemMetrics metrics) {
        WorkerInfo workerInfo = new WorkerInfo();
        workerInfo.setAddress("192.168.1.1:27777");
        workerInfo.setSystemMetrics(metrics);
        return workerInfo;
    }

    private static JobInfoDO job(double cpu, double memory, double disk) {
        JobInfoDO jobInfo = new JobInfoDO();
        jobInfo.setId(1L);
        jobInfo.setMinCpuCores(cpu);
        jobInfo.setMinMemorySpace(memory);
        jobInfo.setMinDiskSpace(disk);
        return jobInfo;
    }

    @Test
    void exactlyMeetingEveryRequirementPasses() {
        WorkerInfo w = worker(metrics(8, 2.0, 4.0, 3.0, 100, 90));
        // mock: remaining cpu = 6, memory = 1, disk = 10
        assertFalse(filter.filter(w, job(6, 1, 10)));
    }

    @Test
    void slightlyBelowAnyRequirementIsFiltered() {
        WorkerInfo w = worker(metrics(8, 2.0, 4.0, 3.0, 100, 90));
        assertTrue(filter.filter(w, job(6.01, 0, 0)));
        assertTrue(filter.filter(w, job(0, 1.01, 0)));
        assertTrue(filter.filter(w, job(0, 0, 10.01)));
    }

    @Test
    void slightlyAboveAnyRequirementPasses() {
        WorkerInfo w = worker(metrics(8, 2.0, 4.0, 3.0, 100, 90));
        assertFalse(filter.filter(w, job(5.99, 0, 0)));
        assertFalse(filter.filter(w, job(0, 0.99, 0)));
        assertFalse(filter.filter(w, job(0, 0, 9.99)));
    }

    @Test
    void cpuLoadZeroOrNegativePasses() {
        WorkerInfo zero = worker(metrics(8, 0, 4.0, 1.0, 100, 1));
        WorkerInfo negative = worker(metrics(8, -1, 4.0, 1.0, 100, 1));
        assertFalse(filter.filter(zero, job(100, 0, 0)));
        assertFalse(filter.filter(negative, job(100, 0, 0)));
    }

    @Test
    void unavailableReasonsCountsEachResource() {
        WorkerInfo w = worker(metrics(8, 2.0, 4.0, 3.0, 100, 90));
        int reasons = SystemMetricsWorkerFilter.unavailableReasons(w, job(6.01, 1.01, 10.01));
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_CPU) != 0);
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_MEMORY) != 0);
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_DISK) != 0);
    }

    @Test
    void missingMetricsDoesNotRejectOnResources() {
        WorkerInfo w = new WorkerInfo();
        w.setAddress("192.168.1.1:27777");
        assertEquals(0, SystemMetricsWorkerFilter.unavailableReasons(w, job(8, 8, 8)));
        assertFalse(filter.filter(w, job(8, 8, 8)));
    }
}
