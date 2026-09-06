package com.grash.event.fanout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The after-commit fan-out gets its own executor, for the same reason the rule engine does.
 *
 * <p>{@code AsyncConfig} defines the application-wide default: three threads, a queue of eleven,
 * shared by CSV exports, imports, mail, comment notifications, demo data and request triage. An
 * export runs for minutes. Fan-out fires on every approval, rejection, completion and meter
 * reading, and once that queue is full the default {@code AbortPolicy} rejects the task — a
 * {@code TaskRejectedException} nobody sees, because the transaction committed long ago. The
 * approval would succeed and the notification would never be sent.
 *
 * <p>{@code CallerRunsPolicy} closes the last gap: under saturation the publishing thread does
 * the work itself, which costs latency rather than a lost notification. That trade is the whole
 * point of moving these side effects out of the transaction — they must survive the move, not
 * merely leave it.
 */
@Configuration
public class FanoutAsyncConfig {

    public static final String EXECUTOR = "fanoutExecutor";

    @Bean(EXECUTOR)
    public Executor fanoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("fanout-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
