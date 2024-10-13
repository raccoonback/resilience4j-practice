package raccoonback;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.util.Arrays.asList;

public class Introduce {

    private BackendService backendService;

    public void sample() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("backendService");

        Retry retry = Retry.ofDefaults("backendService");

        Bulkhead bulkhead = Bulkhead.ofDefaults("backendService");

        Supplier<String> supplier = () -> backendService.doSomething("param1", "param2");

        Supplier<String> decoratedSupplier = Decorators.ofSupplier(supplier)
                .withCircuitBreaker(circuitBreaker)
                .withBulkhead(bulkhead)
                .withRetry(retry)
                .decorate();

        String result = circuitBreaker.executeSupplier(() -> backendService.doSomething("param1", "param2"));

        ThreadPoolBulkhead threadPoolBulkhead = ThreadPoolBulkhead.ofDefaults("backendService");

        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(3);
        TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofSeconds(1));

        CompletableFuture<String> future = Decorators.ofSupplier(supplier)
                .withThreadPoolBulkhead(threadPoolBulkhead)
                .withTimeLimiter(timeLimiter, scheduledExecutorService)
                .withCircuitBreaker(circuitBreaker)
                .withFallback(
                        asList(
                                TimeoutException.class,
                                CallNotPermittedException.class,
                                BulkheadFullException.class
                        ),
                        throwable -> "Hello from Recovery"
                )
                .get()
                .toCompletableFuture();
    }
}
