import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raccoonback.BackendService;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

public class BulkheadBasedOnSemaphoreTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BulkheadBasedOnSemaphoreTest.class);

    private BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(2)
            .maxWaitDuration(Duration.ofMillis(500))
            .build();

    private BulkheadRegistry registry = BulkheadRegistry.of(config);

    private BackendService backendService = mock(BackendService.class);

    @Test
    @DisplayName("max concurrent calls 만큼 동시 호출해야 한다")
    void callConcurrentlyMaxCount() {
        // given
        Bulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(400);
                    return "ok";
                });

        Supplier<String> decoratedSupplier = Bulkhead.decorateSupplier(
                bulkhead,
                () -> backendService.doSomething("param1", "param2")
        );

        // when, then
        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(500),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(500),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        )
                )
                .join();
    }

    @Test
    @DisplayName("max concurrent calls 만큼 모두 호출중이라면 나머지 호출은 max wait duration 동안 대기해야만 한다")
    void waitDuringMaxDuration() {
        // given
        Bulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(400);
                    return "ok";
                });

        Supplier<String> decoratedSupplier = Bulkhead.decorateSupplier(
                bulkhead,
                () -> backendService.doSomething("param1", "param2")
        );

        // when, then
        long startTime = System.currentTimeMillis();
        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(500),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(500),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    decoratedSupplier.get();

                                    long elapsedTime = System.currentTimeMillis() - startTime;
                                    if (elapsedTime <= 500) {
                                        fail("Test completed too quickly!");
                                    }
                                }
                        )
                )
                .join();
    }

    @Test
    @DisplayName("max wait duration 시간동안 대기 후에도 context를 점유하지 못하면 예외가 발생해야 한다")
    void raiseExceptionWhenDeosNotAcquireContext() {
        // given
        Bulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(500);
                    return "ok";
                });

        Supplier<String> decoratedSupplier = Bulkhead.decorateSupplier(
                bulkhead,
                () -> backendService.doSomething("param1", "param2")
        );

        // when, then
        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(600),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(600),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertThrows(
                                            BulkheadFullException.class,
                                            () -> decoratedSupplier.get()
                                    );
                                }
                        )
                )
                .join();
    }

    @Test
    @DisplayName("bulk head 상태전이 이벤트를 전달받아야 한다")
    void consumeTransitState() {
        // given
        Bulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(500);
                    return "ok";
                });

        Supplier<String> decoratedSupplier = Bulkhead.decorateSupplier(
                bulkhead,
                () -> backendService.doSomething("param1", "param2")
        );

        bulkhead.getEventPublisher()
                .onCallPermitted(event -> LOGGER.info("permitted event: {}", event))
                .onCallRejected(event -> LOGGER.error("rejected event: {}", event))
                .onCallFinished(event -> LOGGER.warn("finished event: {}", event));

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(600),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertTimeout(
                                            Duration.ofMillis(600),
                                            () -> {
                                                decoratedSupplier.get();
                                            }
                                    );
                                }
                        ),
                        CompletableFuture.runAsync(
                                () -> {
                                    assertThrows(
                                            BulkheadFullException.class,
                                            () -> decoratedSupplier.get()
                                    );
                                }
                        )
                )
                .join();
    }
}
