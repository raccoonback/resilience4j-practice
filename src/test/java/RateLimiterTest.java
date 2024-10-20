import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raccoonback.BackendService;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

public class RateLimiterTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimiterTest.class);

    private RateLimiterConfig config = RateLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(100))
            .limitRefreshPeriod(Duration.ofMillis(500))
            .limitForPeriod(2)
            .build();

    private RateLimiterRegistry registry = RateLimiterRegistry.of(config);

    private BackendService backendService = mock(BackendService.class);

    @DisplayName("refresh period 기간내 허용된 limit count까지는 호출이 가능하다")
    @Test
    void callsUntilLimitCountDuringRefresh() {
        // given
        RateLimiter rateLimiter = registry.rateLimiter("test");

        Supplier<String> decorateSupplier = RateLimiter.decorateSupplier(
                rateLimiter,
                () -> backendService.doSomething("param1", "param2")
        );

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(100);
                    return "ok";
                });

        // when, then
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
    }

    @DisplayName("대기 중인 요청이 queue에서 제한된 대기시간을 넘기면 예외가 발생한다")
    @Test
    void raiseExceptionIfWaitTimeout() {
        // given
        RateLimiter rateLimiter = registry.rateLimiter("test");

        Supplier<String> decorateSupplier = RateLimiter.decorateSupplier(
                rateLimiter,
                () -> backendService.doSomething("param1", "param2")
        );

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(100);
                    return "ok";
                });

        // when, then
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertThrows(
                RequestNotPermitted.class,
                decorateSupplier::get
        );
    }

    @DisplayName("refresh period 이후에는 limit count가 초기화되어 호출이 가능하다")
    @Test
    void enableCallsAfterRefresh() throws InterruptedException {
        // given
        RateLimiter rateLimiter = registry.rateLimiter("test");

        Supplier<String> decorateSupplier = RateLimiter.decorateSupplier(
                rateLimiter,
                () -> backendService.doSomething("param1", "param2")
        );

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(100);
                    return "ok";
                });

        // when, then
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertThrows(
                RequestNotPermitted.class,
                decorateSupplier::get
        );

        Thread.sleep(200);

        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
    }

    @Test
    @DisplayName("rate limiter 상태전이 이벤트를 전달받아야 한다")
    void consumeTransitState() throws InterruptedException {
        // given
        RateLimiter rateLimiter = registry.rateLimiter("test");

        Supplier<String> decorateSupplier = RateLimiter.decorateSupplier(
                rateLimiter,
                () -> backendService.doSomething("param1", "param2")
        );

        given(backendService.doSomething(anyString(), anyString()))
                .willAnswer((unUsed) -> {
                    Thread.sleep(100);
                    return "ok";
                });

        // when, then
        rateLimiter.getEventPublisher()
                .onSuccess(event -> LOGGER.info("success: {}", event))
                .onFailure(event -> LOGGER.error("failure: {}", event));

        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
        assertThrows(
                RequestNotPermitted.class,
                decorateSupplier::get
        );

        Thread.sleep(200);

        assertTimeout(
                Duration.ofMillis(150),
                decorateSupplier::get
        );
    }
}
