import io.github.resilience4j.retry.MaxRetriesExceededException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import raccoonback.BackendService;
import raccoonback.NonRetryableException;
import raccoonback.RetryableException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

public class RetryTest {

    RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryOnResult(response -> response == "FAILED")
            .retryOnException(exception -> exception instanceof RetryableException)
            .retryExceptions(IOException.class, TimeoutException.class)
            .ignoreExceptions(NonRetryableException.class)
            .failAfterMaxAttempts(true)
            .build();

    RetryRegistry registry = RetryRegistry.of(config);

    private BackendService backendService = mock(BackendService.class);

    @DisplayName("결과값이 정의한 재시도 조건에 부합하면 재시도한다")
    @Test
    void retryIfSatisfiedReturnValue() {
        // given
        Retry retry = registry.retry("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willReturn("FAILED")
                .willReturn("SUCCESS");

        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> backendService.doSomething("param1", "param2"));

        // when
        supplier.get();

        // then
        verify(backendService, times(2))
                .doSomething("param1", "param2");
    }

    @DisplayName("결과값이 정의한 재시도 조건에 부합하지 않으면 설정한 재시도 카운트까지만 재시도한다")
    @Test
    void retryUntilMaxAttempts() {
        // given
        Retry retry = registry.retry("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willReturn("FAILED");

        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> backendService.doSomething("param1", "param2"));

        // when
        assertThrows(MaxRetriesExceededException.class, supplier::get);

        // then
        verify(backendService, times(3))
                .doSomething("param1", "param2");
    }

    @DisplayName("결과값이 정의한 재시도 조건에 부합하지 않으며 재시도하지 않는다")
    @Test
    void doesNotRetryIfSatisfiedCondition() {
        // given
        Retry retry = registry.retry("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willReturn("SUCCESS");

        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> backendService.doSomething("param1", "param2"));

        // when
        supplier.get();

        // then
        verify(backendService, only())
                .doSomething("param1", "param2");
    }

    @DisplayName("발생한 예외가 재시도해야하는 타입에 부합하면 재시도한다")
    @Test
    void retryIfSatisfiedRaisedException() {
        // given
        Retry retry = registry.retry("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willThrow(RetryableException.class)
                .willReturn("SUCCESS");

        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> backendService.doSomething("param1", "param2"));

        // when
        supplier.get();

        // then
        verify(backendService, times(2))
                .doSomething("param1", "param2");
    }

    @DisplayName("발생한 예외가 재시도하지 않아야하는 타입에 부합하면 재시도하지 않는다")
    @Test
    void retryIfSatisfiedIgnoredException() {
        // given
        Retry retry = registry.retry("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willThrow(NonRetryableException.class);

        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> backendService.doSomething("param1", "param2"));

        // when
        assertThrows(
                NonRetryableException.class,
                supplier::get
        );

        // then
        verify(backendService, only())
                .doSomething("param1", "param2");
    }
}
