package tech.powerjob.common.request.http;

import org.junit.jupiter.api.Test;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.exception.PowerJobException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates the busy-machine resource requirements at the job save entry. Only the request object
 * is exercised; no database or filter is involved.
 *
 * @author PowerJob
 */
class SaveJobInfoRequestResourceValidationTest {

    private static SaveJobInfoRequest baseRequest() {
        SaveJobInfoRequest request = new SaveJobInfoRequest();
        request.setJobName("job");
        request.setAppId(1L);
        request.setProcessorInfo("processor");
        request.setExecuteType(ExecuteType.STANDALONE);
        request.setProcessorType(ProcessorType.BUILT_IN);
        request.setTimeExpressionType(TimeExpressionType.API);
        return request;
    }

    @Test
    void zeroAndPositiveFractionalRequirementsAreAllowed() {
        SaveJobInfoRequest request = baseRequest();
        request.setMinCpuCores(0.5);
        request.setMinMemorySpace(0.5);
        request.setMinDiskSpace(0.25);
        request.setMaxWorkerCount(2);
        assertDoesNotThrow(request::valid);
    }

    @Test
    void zeroMeansNoRestriction() {
        SaveJobInfoRequest request = baseRequest();
        request.setMinCpuCores(0);
        request.setMinMemorySpace(0);
        request.setMinDiskSpace(0);
        request.setMaxWorkerCount(0);
        assertDoesNotThrow(request::valid);
    }

    @Test
    void negativeRequirementsAreRejected() {
        assertThrows(PowerJobException.class, () -> {
            SaveJobInfoRequest request = baseRequest();
            request.setMinCpuCores(-0.1);
            request.valid();
        });
        assertThrows(PowerJobException.class, () -> {
            SaveJobInfoRequest request = baseRequest();
            request.setMinMemorySpace(-1);
            request.valid();
        });
        assertThrows(PowerJobException.class, () -> {
            SaveJobInfoRequest request = baseRequest();
            request.setMinDiskSpace(-1);
            request.valid();
        });
        assertThrows(PowerJobException.class, () -> {
            SaveJobInfoRequest request = baseRequest();
            request.setMaxWorkerCount(-1);
            request.valid();
        });
    }
}
