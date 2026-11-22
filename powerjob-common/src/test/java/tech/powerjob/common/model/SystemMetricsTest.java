package tech.powerjob.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary tests for {@link SystemMetrics#available(double, double, double)} and
 * {@link SystemMetrics#calculateScore()}. These construct {@link SystemMetrics} directly and do
 * not touch any database or worker runtime.
 *
 * @author PowerJob
 */
class SystemMetricsTest {

    private static SystemMetrics metrics(int cpuProcessors, double cpuLoad,
                                         double jvmMaxMemory, double jvmUsedMemory,
                                         double diskTotal, double diskUsed) {
        SystemMetrics m = new SystemMetrics();
        m.setCpuProcessors(cpuProcessors);
        m.setCpuLoad(cpuLoad);
        m.setJvmMaxMemory(jvmMaxMemory);
        m.setJvmUsedMemory(jvmUsedMemory);
        m.setDiskTotal(diskTotal);
        m.setDiskUsed(diskUsed);
        return m;
    }

    @Test
    void memoryBoundaryUsesJvmRemainingHeap() {
        // 8 cores, load 1.0 -> remaining 7.0; heap remaining = 4.0 - 3.0 = 1.0 GB
        SystemMetrics m = metrics(8, 1.0, 4.0, 3.0, 100, 10);

        // exactly equal requirement passes
        assertTrue(m.available(1.0, 1.0, 1.0));
        // slightly larger requirement fails
        assertFalse(m.available(1.0, 1.01, 1.0));
        // slightly smaller requirement passes
        assertTrue(m.available(1.0, 0.99, 1.0));
    }

    @Test
    void diskBoundary() {
        // remaining disk = 100 - 90 = 10
        SystemMetrics m = metrics(8, 1.0, 4.0, 1.0, 100, 90);

        assertTrue(m.available(0, 0, 10));
        assertFalse(m.available(0, 0, 10.01));
        assertTrue(m.available(0, 0, 9.99));
    }

    @Test
    void cpuBoundaryEqualsRequirementPasses() {
        // 8 cores, load 2.5 -> remaining 5.5 cores
        SystemMetrics m = metrics(8, 2.5, 4.0, 1.0, 100, 1);

        // equal is satisfied (unified ">=" rule)
        assertTrue(m.available(5.5, 0, 0));
        // slightly more is rejected
        assertFalse(m.available(5.51, 0, 0));
        // slightly less is accepted
        assertTrue(m.available(5.49, 0, 0));
    }

    @Test
    void cpuLoadZeroMeansIdleAndPasses() {
        SystemMetrics m = metrics(8, 0, 4.0, 1.0, 100, 1);
        assertTrue(m.available(7.9, 0, 0));
        assertTrue(m.available(100, 0, 0));
    }

    @Test
    void cpuLoadNegativeMeansUnknownAndPasses() {
        // Windows: getSystemLoadAverage() == -1
        SystemMetrics m = metrics(8, -1.0, 4.0, 1.0, 100, 1);
        assertTrue(m.available(100, 0, 0));
    }

    @Test
    void nonPositiveRequirementMeansNoRestriction() {
        SystemMetrics m = metrics(2, 1.5, 1.0, 0.9, 10, 9);
        assertTrue(m.available(0, 0, 0));
        // negative requirement (legacy rows) is treated as "no restriction", not a rejection
        assertTrue(m.available(-1, -1, -1));
    }

    @Test
    void unavailableReasonsReportsEveryUnmetResource() {
        SystemMetrics m = metrics(8, 2.5, 2.0, 1.5, 100, 99);

        assertEquals(0, m.unavailableReasons(5.5, 0.5, 1));
        int reasons = m.unavailableReasons(5.6, 0.6, 1.1);
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_CPU) != 0);
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_MEMORY) != 0);
        assertTrue((reasons & SystemMetrics.UNAVAILABLE_DISK) != 0);
        // consistency with available(...)
        assertFalse(m.available(5.6, 0.6, 1.1));
    }

    @Test
    void unavailableReasonsSkipsCpuWhenLoadUnknown() {
        SystemMetrics m = metrics(8, -1.0, 4.0, 1.0, 100, 1);
        // only CPU-looking requirement; load unknown so it cannot reject
        assertEquals(0, m.unavailableReasons(100, 0, 0));
    }

    @Test
    void scoreUsesCpuAndJvmMemoryOnlyAndIsCached() {
        SystemMetrics m = metrics(8, 2.0, 4.0, 1.0, 100, 99);
        // memScore = (4 - 1) * 2 = 6, cpuScore = 8 - 2 = 6 -> 12
        assertEquals(12, m.calculateScore());
        // disk change must not affect score
        m.setDiskUsed(0);
        assertEquals(12, m.calculateScore());
        // score is cached and honored when positive
        m.setScore(777);
        assertEquals(777, m.calculateScore());
    }

    @Test
    void scoreClampsUnknownCpuLoadToOne() {
        SystemMetrics m = metrics(8, -1.0, 4.0, 1.0, 100, 1);
        // cpuProcessors - cpuLoad = 9 > 8 -> clamped to 1; memScore = 3 * 2 = 6 -> 7
        assertEquals(7, m.calculateScore());
    }
}
