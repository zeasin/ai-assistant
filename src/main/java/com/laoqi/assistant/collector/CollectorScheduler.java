package com.laoqi.assistant.collector;

import com.laoqi.assistant.collector.model.CollectorTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class CollectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectorScheduler.class);

    private final CollectorService collectorService;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CollectorScheduler(CollectorService collectorService, TaskScheduler taskScheduler) {
        this.collectorService = collectorService;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void init() {
        rescheduleAll();
    }

    public void rescheduleAll() {
        scheduledTasks.values().forEach(future -> future.cancel(false));
        scheduledTasks.clear();

        for (CollectorTask task : collectorService.getAllTasks()) {
            if (Boolean.TRUE.equals(task.getEnabled())) {
                scheduleTask(task);
            }
        }
        log.info("Rescheduled {} collector tasks", scheduledTasks.size());
    }

    public void scheduleTask(CollectorTask task) {
        if (!Boolean.TRUE.equals(task.getEnabled())) {
            unscheduleTask(task.getId());
            return;
        }

        try {
            CronTrigger trigger = new CronTrigger(task.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                log.info("Executing scheduled collector task: {}", task.getName());
                try {
                    collectorService.executeTask(task.getId());
                } catch (Exception e) {
                    log.error("Scheduled collector task {} failed: {}", task.getName(), e.getMessage());
                }
            }, trigger);

            scheduledTasks.put(task.getId(), future);
            log.info("Scheduled collector task {} with cron: {}", task.getName(), task.getCronExpression());
        } catch (Exception e) {
            log.error("Failed to schedule collector task {}: {}", task.getName(), e.getMessage());
        }
    }

    public void unscheduleTask(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("Unscheduled collector task: {}", taskId);
        }
    }

    public int getScheduledTaskCount() {
        return scheduledTasks.size();
    }
}