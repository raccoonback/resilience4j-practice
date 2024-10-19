import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import raccoonback.BackendService;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

public class BulkheadBasedOnThreadPoolTest {

    private ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(2)
            .coreThreadPoolSize(1)
            .queueCapacity(1)
            .build();

    private ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.of(config);

    private BackendService backendService = mock(BackendService.class);

    @Test
    @DisplayName("max thread pool size 만큼 동시 호출해야 한다")
    void callConcurrentlyMaxPoolSize() {
        // given
        ThreadPoolBulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(400);
                    return "ok";
                });

        Supplier<CompletionStage<String>> decoratedSupplier = ThreadPoolBulkhead.decorateSupplier(
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
    @DisplayName("queue capacity를 넘어서는 요청은 예외를 발생한다")
    void raiseExceptionIfExceededCapacity() {
        // given
        ThreadPoolBulkhead bulkhead = registry.bulkhead("test");

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(400);
                    return "ok";
                });

        Supplier<CompletionStage<String>> decoratedSupplier = ThreadPoolBulkhead.decorateSupplier(
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
