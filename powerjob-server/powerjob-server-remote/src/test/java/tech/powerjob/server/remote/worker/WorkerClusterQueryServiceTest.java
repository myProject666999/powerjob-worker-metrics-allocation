package tech.powerjob.server.remote.worker;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import tech.powerjob.common.model.SystemMetrics;
import tech.powerjob.common.request.WorkerHeartbeat;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.remote.worker.filter.DesignatedWorkerFilter;
import tech.powerjob.server.remote.worker.filter.DisconnectedWorkerFilter;
import tech.powerjob.server.remote.worker.filter.SystemMetricsWorkerFilter;
import tech.powerjob.server.remote.worker.filter.WorkerFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link WorkerClusterQueryService} candidate ordering, truncation and result explanation.
 * Workers are injected through {@link WorkerClusterManagerService} heartbeats and the metrics are
 * constructed directly; no database or worker runtime is involved.
 *
 * @author PowerJob
 */
class WorkerClusterQueryServiceTest {

    private static final AtomicLong APP_ID_SEQ = new AtomicLong(1000);

    private static List<WorkerFilter> filters() {
        return Lists.newArrayList(new DisconnectedWorkerFilter(), new DesignatedWorkerFilter(), new SystemMetricsWorkerFilter());
    }

    private static WorkerClusterQueryService service(long maxAgeMs) {
        return new WorkerClusterQueryService(filters(), maxAgeMs);
    }

    private static SystemMetrics metrics(int cpuProcessors, double cpuLoad, double jvmMax, double jvmUsed) {
        SystemMetrics m = new SystemMetrics();
        m.setCpuProcessors(cpuProcessors);
        m.setCpuLoad(cpuLoad);
        m.setJvmMaxMemory(jvmMax);
        m.setJvmUsedMemory(jvmUsed);
        m.setDiskTotal(100);
        m.setDiskUsed(1);
        m.calculateScore();
        return m;
    }

    private static void register(Long appId, String address, SystemMetrics metrics) {
        WorkerHeartbeat heartbeat = new WorkerHeartbeat();
        heartbeat.setAppId(appId);
        heartbeat.setAppName("app-" + appId);
        heartbeat.setWorkerAddress(address);
        heartbeat.setProtocol("HTTP");
        heartbeat.setHeartbeatTime(System.currentTimeMillis());
        heartbeat.setSystemMetrics(metrics);
        WorkerClusterManagerService.updateStatus(heartbeat);
    }

    private static JobInfoDO job(Long appId) {
        JobInfoDO jobInfo = new JobInfoDO();
        jobInfo.setId(1L);
        jobInfo.setAppId(appId);
        jobInfo.setMaxWorkerCount(0);
        return jobInfo;
    }

    private static List<String> addresses(List<WorkerInfo> workers) {
        List<String> res = new ArrayList<>();
        workers.forEach(w -> res.add(w.getAddress()));
        return res;
    }

    @Test
    void sortedByScoreDescThenAddressAscAndIndependentOfInputOrder() {
        Long appId = APP_ID_SEQ.incrementAndGet();
        // equal score for 10.0.0.1/3/4, higher score for 10.0.0.2
        register(appId, "10.0.0.2:1", metrics(8, 1, 4, 1)); // mem 3*2=6, cpu 7 -> 13
        register(appId, "10.0.0.4:1", metrics(8, 1, 4, 2)); // mem 2*2=4, cpu 7 -> 11
        register(appId, "10.0.0.3:1", metrics(8, 1, 4, 2)); // 11, tie broken by address
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 2)); // 11, tie broken by address

        List<String> once = addresses(service(0).geAvailableWorkers(job(appId)));
        assertEquals(Lists.newArrayList("10.0.0.2:1", "10.0.0.1:1", "10.0.0.3:1", "10.0.0.4:1"), once);

        // repeated queries (map traversal order may differ) must be identical
        for (int i = 0; i < 20; i++) {
            assertEquals(once, addresses(service(0).geAvailableWorkers(job(appId))));
        }
    }

    @Test
    void truncationHappensAfterSorting() {
        Long appId = APP_ID_SEQ.incrementAndGet();
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 3)); // mem (4-3)*2=2, cpu 7 -> 9
        register(appId, "10.0.0.2:1", metrics(8, 1, 4, 1)); // mem (4-1)*2=6, cpu 7 -> 13
        register(appId, "10.0.0.3:1", metrics(8, 1, 4, 2)); // mem (4-2)*2=4, cpu 7 -> 11

        JobInfoDO jobInfo = job(appId);
        jobInfo.setMaxWorkerCount(2);
        assertEquals(Lists.newArrayList("10.0.0.2:1", "10.0.0.3:1"), addresses(service(0).geAvailableWorkers(jobInfo)));

        jobInfo.setMaxWorkerCount(3);
        assertEquals(3, service(0).geAvailableWorkers(jobInfo).size());

        jobInfo.setMaxWorkerCount(10);
        assertEquals(3, service(0).geAvailableWorkers(jobInfo).size());
    }

    @Test
    void designatedWorkerIsNeverTruncatedAwayByLowerScore() {
        Long appId = APP_ID_SEQ.incrementAndGet();
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 1)); // highest score
        register(appId, "10.0.0.2:1", metrics(8, 1, 4, 3)); // lowest score, designated
        register(appId, "10.0.0.3:1", metrics(8, 1, 4, 2));

        JobInfoDO jobInfo = job(appId);
        jobInfo.setMaxWorkerCount(1);
        jobInfo.setDesignatedWorkers("10.0.0.2:1");
        assertEquals(Lists.newArrayList("10.0.0.2:1"), addresses(service(0).geAvailableWorkers(jobInfo)));
    }

    @Test
    void staleSnapshotIsDemotedToTailButNotRemoved() {
        Long appId = APP_ID_SEQ.incrementAndGet();
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 1)); // best score but will be made stale
        register(appId, "10.0.0.2:1", metrics(8, 1, 4, 3));

        // 45s old: below the 60s disconnect timeout, above the 30s snapshot-age threshold
        WorkerInfo stale = worker(appId, "10.0.0.1:1");
        stale.setLastActiveTime(System.currentTimeMillis() - 45_000);

        // disabled by default (maxAge <= 0): score order kept
        assertEquals(Lists.newArrayList("10.0.0.1:1", "10.0.0.2:1"), addresses(service(0).geAvailableWorkers(job(appId))));

        // enabled: stale worker demoted to the tail but still present
        List<WorkerInfo> withAge = service(30_000).geAvailableWorkers(job(appId));
        assertEquals(Lists.newArrayList("10.0.0.2:1", "10.0.0.1:1"), addresses(withAge));
        assertEquals(2, withAge.size());
    }

    @Test
    void stalenessDemotionIsAppliedBeforeTruncation() {
        Long appId = APP_ID_SEQ.incrementAndGet();
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 1)); // best but stale
        register(appId, "10.0.0.2:1", metrics(8, 1, 4, 3));
        worker(appId, "10.0.0.1:1").setLastActiveTime(System.currentTimeMillis() - 45_000);

        JobInfoDO jobInfo = job(appId);
        jobInfo.setMaxWorkerCount(1);
        assertEquals(Lists.newArrayList("10.0.0.2:1"), addresses(service(30_000).geAvailableWorkers(jobInfo)));
    }

    @Test
    void emptyCandidatesDistinguishNoOnlineWorkerFromAllFilteredOut() {
        // no online worker at all
        WorkerClusterQueryService.AvailableWorkers none = service(0).geAvailableWorkersWithDetail(job(APP_ID_SEQ.incrementAndGet()));
        assertTrue(none.getWorkers().isEmpty());
        assertTrue(none.isNoOnlineWorker());
        assertFalse(none.isAllFilteredOut());

        // online workers but none meets the requirement
        Long appId = APP_ID_SEQ.incrementAndGet();
        register(appId, "10.0.0.1:1", metrics(8, 1, 4, 3));
        JobInfoDO demanding = job(appId);
        demanding.setMinMemorySpace(100);
        WorkerClusterQueryService.AvailableWorkers filtered = service(0).geAvailableWorkersWithDetail(demanding);
        assertTrue(filtered.getWorkers().isEmpty());
        assertFalse(filtered.isNoOnlineWorker());
        assertTrue(filtered.isAllFilteredOut());
        assertEquals(1, filtered.getTotalOnline());
    }

    @Test
    void sortCandidatesIsStableAcrossShuffledInput() {
        List<WorkerInfo> base = Lists.newArrayList(
                worker("10.0.0.3:1", metrics(8, 1, 4, 2)),
                worker("10.0.0.1:1", metrics(8, 1, 4, 2)),
                worker("10.0.0.2:1", metrics(8, 1, 4, 1)),
                worker("10.0.0.4:1", metrics(8, 1, 4, 2))
        );
        List<String> expected = Lists.newArrayList("10.0.0.2:1", "10.0.0.1:1", "10.0.0.3:1", "10.0.0.4:1");

        Random random = new Random(42);
        for (int i = 0; i < 30; i++) {
            List<WorkerInfo> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, random);
            WorkerClusterQueryService.sortCandidates(shuffled, Collections.emptySet(), 0, System.currentTimeMillis());
            assertEquals(expected, addresses(shuffled));
        }
    }

    private static WorkerInfo worker(String address, SystemMetrics metrics) {
        WorkerInfo workerInfo = new WorkerInfo();
        workerInfo.setAddress(address);
        workerInfo.setSystemMetrics(metrics);
        workerInfo.setLastActiveTime(System.currentTimeMillis());
        return workerInfo;
    }

    /**
     * Test-only access to the worker instance held by the cluster holder, so a test can mutate
     * its snapshot age without going through the heartbeat path (which always refreshes it).
     */
    private static WorkerInfo worker(Long appId, String address) {
        ClusterStatusHolder holder = WorkerClusterManagerService.getAppId2ClusterStatus().get(appId);
        return holder.getAllWorkers().get(address);
    }
}
