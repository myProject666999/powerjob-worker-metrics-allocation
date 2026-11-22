package tech.powerjob.common.model;

import tech.powerjob.common.PowerSerializable;
import lombok.Data;

/**
 * Class for system metrics.
 *
 * @author tjq
 * @since 2020/3/25
 */
@Data
public class SystemMetrics implements PowerSerializable, Comparable<SystemMetrics> {

    /**
     * Bit flag returned by {@link #unavailableReasons(double, double, double)}
     * indicating that the remaining CPU cores do not meet the requirement.
     */
    public static final int UNAVAILABLE_CPU = 1;
    /**
     * Bit flag returned by {@link #unavailableReasons(double, double, double)}
     * indicating that the remaining JVM heap does not meet the requirement.
     */
    public static final int UNAVAILABLE_MEMORY = 1 << 1;
    /**
     * Bit flag returned by {@link #unavailableReasons(double, double, double)}
     * indicating that the remaining disk space does not meet the requirement.
     */
    public static final int UNAVAILABLE_DISK = 1 << 2;

    /**
     * CPU processor num.
     */
    private int cpuProcessors;
    /**
     * System load average reported by {@link java.lang.management.OperatingSystemMXBean#getSystemLoadAverage()},
     * i.e. the number of runnable entities (roughly the number of busy cores), NOT a percentage.
     *
     * <p>Contract used by {@link #available(double, double, double)}:
     * <ul>
     *     <li>{@code > 0}: a valid load value;</li>
     *     <li>{@code == 0}: treated as idle / no load;</li>
     *     <li>{@code < 0}: the JVM could not obtain the load (on Windows this method returns {@code -1}).</li>
     * </ul>
     * In both {@code <= 0} cases the CPU requirement is treated as satisfied, because the metric
     * cannot be used to reject the worker. See {@link #calculateScore()} for how the same value
     * is consumed when scoring.
     */
    private double cpuLoad;

    /**
     * Memory that is used by JVM, in GB.
     */
    private double jvmUsedMemory;
    /**
     * Max memory that JVM can use, in GB.
     */
    private double jvmMaxMemory;
    /**
     * Ratio of memory that JVM uses to total memory, 0.X,
     * the value is between 0 and 1.
     */
    private double jvmMemoryUsage;

    /**
     * Total used disk space, in GB.
     */
    private double diskUsed;
    /**
     * Total disk space, in GB.
     */
    private double diskTotal;
    /**
     * Used disk ratio.
     */
    private double diskUsage;
    /**
     * user-customized system metrics collector, eg. GPU usage
     * implement SystemMetricsCollector to set the value in worker side
     * implement WorkerFilter to filter the worker in server side
     */
    private String extra;
    /**
     * Score of cache.
     */
    private int score;

    /**
     * Remaining JVM heap in GB, i.e. {@code jvmMaxMemory - jvmUsedMemory}.
     *
     * <p>This is the heap the JVM may still allocate from {@code -Xmx}, NOT the free memory of
     * the physical machine. It is the value used both by the {@code minMemorySpace} requirement
     * and by {@link #calculateScore()}.
     *
     * @return remaining JVM heap in GB
     */
    public double availableMemory() {
        return jvmMaxMemory - jvmUsedMemory;
    }

    /**
     * Remaining disk space in GB, i.e. {@code diskTotal - diskUsed}.
     *
     * @return remaining disk space in GB
     */
    public double availableDisk() {
        return diskTotal - diskUsed;
    }

    /**
     * Remaining CPU cores, i.e. {@code cpuProcessors - cpuLoad}.
     *
     * @return a positive number when a valid load was reported, otherwise a negative number
     */
    public double availableCpuCores() {
        return cpuProcessors - cpuLoad;
    }

    /**
     * Override compareTo.
     *
     * @param that the metrics that is to be compared with current.
     * @return {@code int}
     */
    @Override
    public int compareTo(SystemMetrics that) {
        // Sort by metrics in descending order.
        return that.calculateScore() - this.calculateScore();
    }

    /**
     * Calculate score, based on CPU and memory info.
     *
     * <p>Only CPU and remaining JVM heap contribute to the score. Disk is intentionally kept as a
     * hard filtering condition (see {@link #available}) and is not part of the score: disk space
     * barely changes between heartbeats, so including it would mostly add noise instead of helping
     * to spread tasks across workers.
     *
     * @return score
     */
    public int calculateScore() {
        if (score > 0) {
            return score;
        }
        // Memory is vital to TaskTracker, so we set the multiplier factor as 2.
        double memScore = availableMemory() * 2;
        // Calculate the remaining load of CPU. Multiplier is set as 1.
        double cpuScore = availableCpuCores();
        // When the JVM cannot fetch CPU load (OperatingSystemMXBean#getSystemLoadAverage returns a
        // negative value on e.g. Windows), availableCpuCores() becomes larger than cpuProcessors.
        // Clamp it to 1 so an unknown CPU state is neither rewarded with a huge score nor with 0.
        if (cpuScore > cpuProcessors) {
            cpuScore = 1;
        }
        score = (int) (memScore + cpuScore);
        return score;
    }

    /**
     * Judge if the machine is available.
     *
     * <p>All three requirements use the same rule: the remaining amount must be greater than or
     * equal to the required amount. Remaining amounts are:
     * <ul>
     *     <li>memory: remaining JVM heap, {@code jvmMaxMemory - jvmUsedMemory} (in GB) — this is
     *     NOT the free memory of the physical machine;</li>
     *     <li>disk: {@code diskTotal - diskUsed} (in GB);</li>
     *     <li>CPU: {@code cpuProcessors - cpuLoad} (in cores).</li>
     * </ul>
     *
     * <p>CPU specifics: a requirement {@code <= 0} means "no restriction" and a {@code cpuLoad <= 0}
     * (idle or unreadable, e.g. {@code -1} on Windows) means the CPU requirement cannot be used to
     * reject the worker, so the worker passes. A negative requirement is therefore treated as
     * "no restriction" for every resource; negative values are rejected at the job save entry.
     *
     * @param minCPUCores Minimum available CPU cores.
     * @param minMemorySpace Minimum available memory size
     * @param minDiskSpace Minimum disk space
     * @return {@code boolean} whether the machine is available.
     */
    public boolean available(double minCPUCores, double minMemorySpace, double minDiskSpace) {
        return unavailableReasons(minCPUCores, minMemorySpace, minDiskSpace) == 0;
    }

    /**
     * Explain why this machine does not meet the requirements, as a bit mask of
     * {@link #UNAVAILABLE_CPU}, {@link #UNAVAILABLE_MEMORY} and {@link #UNAVAILABLE_DISK}.
     *
     * <p>The flags follow exactly the same rules as {@link #available(double, double, double)},
     * so {@code unavailableReasons(...) == 0} is equivalent to {@code available(...) == true}.
     * Multiple flags may be set for a single worker.
     *
     * @param minCPUCores Minimum available CPU cores.
     * @param minMemorySpace Minimum available memory size
     * @param minDiskSpace Minimum disk space
     * @return {@code 0} when all requirements are met, otherwise the OR of the unmet resource flags
     */
    public int unavailableReasons(double minCPUCores, double minMemorySpace, double minDiskSpace) {
        int reasons = 0;

        if (availableMemory() < minMemorySpace) {
            reasons |= UNAVAILABLE_MEMORY;
        }
        if (availableDisk() < minDiskSpace) {
            reasons |= UNAVAILABLE_DISK;
        }
        // 0 indicates the CPU is free, which is the optimal condition.
        // Negative number means being unable to fetch CPU info, so the CPU requirement is skipped.
        if (minCPUCores > 0 && cpuLoad > 0 && availableCpuCores() < minCPUCores) {
            reasons |= UNAVAILABLE_CPU;
        }
        return reasons;
    }
}
