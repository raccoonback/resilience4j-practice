import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.SupplierUtils;
import io.vavr.control.Try;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CircuitBreakerBasedOnCountsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreakerBasedOnCountsTest.class);

    private CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(40)
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

    @DisplayName("minimum number of calls 만큼 메서드 호출하지 않았다면 circuit은 close 상태이다.")
    @Test
    void closedCircuitEvenIfAlreadyExceedsFailureThresholdWithoutSatisfyingMinimumNumberOfCalls() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((invocation) -> {
                    throw new IOException();
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        // when, then
        IntStream.range(0, 9).forEach((noOp) -> {
            assertThrows(
                    IOException.class,
                    () -> decoratedSupplier.get()
            );
        });
        assertEquals(CLOSED, circuitBreaker.getState());
    }

    @DisplayName("실패율 임계치를 넘기지 않으면 circuit 상태는 close 이다.")
    @Test
    void closedCircuitIfDoesNotExceedsFailureThreshold() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer(new Answer<String>() {
                    private int count = 0;

                    @Override
                    public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                        if (count++ >= 7) {
                            throw new IOException();
                        }

                        return "ok";
                    }
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        // when, then
        IntStream.range(0, 7).forEach((noOp) -> {
            assertDoesNotThrow(
                    () -> decoratedSupplier.get()
            );
        });

        IntStream.range(7, 10).forEach((noOp) -> {
            assertThrows(
                    IOException.class,
                    () -> decoratedSupplier.get()
            );
        });

        assertEquals(CLOSED, circuitBreaker.getState());
    }

    @DisplayName("실패율 임계치를 넘기면 circuit 상태는 open 이다.")
    @Test
    void openCircuitIfExceedsFailureThreshold() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer(new Answer<String>() {
                    private int count = 0;

                    @Override
                    public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                        if (count++ >= 6) {
                            throw new IOException();
                        }

                        return "ok";
                    }
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        // when, then
        IntStream.range(0, 6).forEach((noOp) -> {
            assertDoesNotThrow(
                    () -> decoratedSupplier.get()
            );
        });

        IntStream.range(6, 10).forEach((noOp) -> {
            assertThrows(
                    IOException.class,
                    () -> decoratedSupplier.get()
            );
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
                        if (count > 6) {
                            throw new IOException();
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
                        assertThrows(
                                IOException.class,
                                () -> decoratedSupplier.get()
                        );
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
                        assertThrows(
                                IOException.class,
                                () -> decoratedSupplier.get()
                        );
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
                            throw new IOException();
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
                        assertThrows(
                                IOException.class,
                                () -> decoratedSupplier.get()
                        );
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
                        assertDoesNotThrow(
                                () -> decoratedSupplier.get()
                        );
                    });
                }),
                DynamicTest.dynamicTest("close 상태로 변경", () -> {
                    assertEquals(CLOSED, circuitBreaker.getState());
                })
        );
    }

    @DisplayName("ignoreExceptions에 포함되는 예외가 발생하면 failure 카운트로 집계하지 않는다.")
    @Test
    void doesNotCountFailureIfSatisfyingIgnoreExceptions() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((invocation) -> {
                    throw new BusinessException();
                });

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        // when, then
        IntStream.range(0, 10).forEach((noOp) -> {
            assertThrows(
                    BusinessException.class,
                    () -> decoratedSupplier.get()
            );
        });

        assertEquals(CLOSED, circuitBreaker.getState());
    }

    @DisplayName("circuit이 open 되어 있다면 메서드 호출시 예외가 발생한다.")
    @Test
    void disableToCallMethodIfOpenedCircuit() {
        // given
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        circuitBreaker.transitionToOpenState();

        // when, then
        assertThrows(
                CallNotPermittedException.class,
                () -> decoratedSupplier.get()
        );
    }

    @DisplayName("circuit이 half-open 되어 있다면 메서드 호출이 가능해야만 한다")
    @Test
    void enableToCallMethodIfHalfOpenedCircuit() {
        // given
        given(backendService.doSomething(anyString(), anyString()))
                .willCallRealMethod();

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        Supplier<String> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> backendService.doSomething("param1", "param2"));

        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        // when
        String result = decoratedSupplier.get();

        // then
        assertEquals("param1 / param2", result);
    }

    @DisplayName("circuit의 상태 전이를 컨슈밍해야 한다")
    @Test
    void consumeTransitionEvents() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        circuitBreaker.getEventPublisher()
                .onSuccess(event -> LOGGER.info("success event: {}", event))
                .onError(event -> LOGGER.error("error event: {}", event))
                .onIgnoredError(event -> LOGGER.warn("ignored error event: {}", event))
                .onReset(event -> LOGGER.info("reset event: {}", event))
                .onFailureRateExceeded(event -> LOGGER.info("failure rate exceeded event: {}", event))
                .onSlowCallRateExceeded(event -> LOGGER.info("slow call rate event: {}", event))
                .onStateTransition(event -> LOGGER.info("transition state event: {}", event));

        circuitBreaker.transitionToOpenState();
    }

    @DisplayName("fallback 값이 반환되어야 한다.")
    @Test
    void recoverFromException() {
        // given
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        Supplier<Object> checkedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
            throw new RuntimeException("BAM!");
        });

        // when
        Try<Object> result = Try.ofSupplier(checkedSupplier)
                .recover(throwable -> "Hello Recovery");

        // then
        assertTrue(result.isSuccess());
        assertEquals("Hello Recovery", result.get());
    }

    @Test
    void recoverBeforeFailure() {
        // given
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");

        Supplier<String> supplier = () -> {
            throw new RuntimeException("BAM!");
        };

        Supplier<String> supplierWithRecovery = SupplierUtils.recover(
                supplier,
                (exception) -> "Hello Recovery"
        );

        // when
        String result = circuitBreaker.executeSupplier(supplierWithRecovery);

        // then
        assertEquals("Hello Recovery", result);
    }
}
