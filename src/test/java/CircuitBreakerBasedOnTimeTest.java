import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raccoonback.BackendService;
import raccoonback.BusinessException;
import raccoonback.OtherBusinessException;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.*;
import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.TIME_BASED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CircuitBreakerBasedOnTimeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreakerBasedOnTimeTest.class);

    private CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(TIME_BASED)
            .failureRateThreshold(60)
            .slowCallRateThreshold(30)
            .slowCallDurationThreshold(Duration.ofMillis(100))
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .waitDurationInOpenState(Duration.ofSeconds(1))
            .permittedNumberOfCallsInHalfOpenState(4)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(10)
            .recordExceptions(IOException.class, TimeoutException.class)
            .ignoreExceptions(BusinessException.class, OtherBusinessException.class)
            .build();

    private CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

    private BackendService backendService = mock(BackendService.class);

    @DisplayName("slow call rate가 임계치를 넘기면 circuit 상태는 open 이다.")
    @Test
    void openCircuitIfExceedsSlowCallRateThreshold() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer(new Answer<String>() {
                    private int count = 0;

                    @Override
                    public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                        if (count++ >= 7) {
                            Thread.sleep(101);
                        }

                        return "ok";
                    }
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        // when, then
        IntStream.range(0, 6).forEach((noOp) -> {
            decoratedSupplier.get();
        });

        IntStream.range(6, 10).forEach((noOp) -> {
            decoratedSupplier.get();
        });

        assertEquals(OPEN, circuitBreaker.getState());
    }

    @DisplayName("circuit close -> open -> half-open -> close 전이 확인")
    @TestFactory
    Collection<DynamicTest> verifyReturnToCircuitClosedStateTransition() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer(new Answer<String>() {
                    private int count = 0;

                    @Override
                    public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                        count++;
                        if (count > 7) {
                            Thread.sleep(101);
                        }

                        return "ok";
                    }
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        return List.of(
                DynamicTest.dynamicTest("circuit 상태가 open", () -> {
                    // when, then
                    IntStream.range(0, 7).forEach((noOp) -> {
                        decoratedSupplier.get();
                    });

                    IntStream.range(7, 10).forEach((noOp) -> {
                        decoratedSupplier.get();
                    });

                    assertEquals(OPEN, circuitBreaker.getState());
                }),
                DynamicTest.dynamicTest("open 상태로 1초 대기", () -> {
                    Thread.sleep(1000);
                }),
                DynamicTest.dynamicTest("half-open 상태로 변경", () -> {
                    assertEquals(HALF_OPEN, circuitBreaker.getState());
                }),
                DynamicTest.dynamicTest("half-open 상태에서 네 번 까지는 호출 가능", () -> {
                    IntStream.range(10, 14).forEach((noOp) -> {
                        decoratedSupplier.get();
                    });
                }),
                DynamicTest.dynamicTest("다시 open 상태로 변경", () -> {
                    assertEquals(OPEN, circuitBreaker.getState());
                })
        );
    }

    @DisplayName("circuit close -> open -> half-open -> close 전이 확인")
    @TestFactory
    Collection<DynamicTest> verifyReopenCircuitC() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer(new Answer<String>() {
                    private int count = 0;

                    @Override
                    public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                        count++;
                        if (count > 10) {
                            return "ok";
                        }

                        if (count > 6) {
                            Thread.sleep(101);
                        }

                        return "ok";
                    }
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        return List.of(
                DynamicTest.dynamicTest("circuit 상태가 open", () -> {
                    // when, then
                    IntStream.range(0, 6).forEach((noOp) -> {
                        assertDoesNotThrow(
                                () -> decoratedSupplier.get()
                        );
                    });

                    IntStream.range(6, 10).forEach((noOp) -> {
                        decoratedSupplier.get();
                    });

                    assertEquals(OPEN, circuitBreaker.getState());
                }),
                DynamicTest.dynamicTest("open 상태로 1초 대기", () -> {
                    Thread.sleep(1000);
                }),
                DynamicTest.dynamicTest("half-open 상태로 변경", () -> {
                    assertEquals(HALF_OPEN, circuitBreaker.getState());
                }),
                DynamicTest.dynamicTest("half-open 상태에서 네 번 까지는 호출 가능", () -> {
                    IntStream.range(10, 14).forEach((noOp) -> {
                        decoratedSupplier.get();
                    });
                }),
                DynamicTest.dynamicTest("close 상태로 변경", () -> {
                    assertEquals(CLOSED, circuitBreaker.getState());
                })
        );
    }
}
