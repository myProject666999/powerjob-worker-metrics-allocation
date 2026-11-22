package tech.powerjob.worker.common.utils;

import org.junit.jupiter.api.Test;
import tech.powerjob.common.model.SystemMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the value {@link SystemInfoUtils} reports as {@code cpuLoad} is the raw
 * {@code OperatingSystemMXBean#getSystemLoadAverage()} (busy-core count, not a percentage), and
 * that the platform's "unknown" sentinel is consistently a negative value.
 *
 * <p>This asserts the contract rather than a fixed number: on Linux the value is a real load
 * average, on Windows it is {@code -1}.
 *
 * @author PowerJob
 */
class SystemInfoUtilsCpuLoadTest {

    private static final OperatingSystemMXBean OS_MX_BEAN = ManagementFactory.getOperatingSystemMXBean();

    @Test
    void cpuLoadIsRawSystemLoadAverageNotPercent() {
        SystemMetrics metrics = SystemInfoUtils.getSystemMetrics();

        assertEquals(OS_MX_BEAN.getAvailableProcessors(), metrics.getCpuProcessors());

        double rawLoad = OS_MX_BEAN.getSystemLoadAverage();
        if (rawLoad < 0) {
            // unknown (e.g. Windows): must be reported as a negative value so filters can skip CPU
            assertTrue(metrics.getCpuLoad() < 0,
                    "unknown load should stay negative, but was " + metrics.getCpuLoad());
        } else {
            // Linux/Unix: reported value matches the raw load average within rounding (4 decimals)
            assertEquals(rawLoad, metrics.getCpuLoad(), 0.001);
            // load average is a busy-core count, so a non-idle value is far below 100
            assertTrue(metrics.getCpuLoad() < 1000);
        }
    }
}
