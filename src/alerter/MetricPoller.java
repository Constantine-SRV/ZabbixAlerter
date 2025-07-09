package alerter;

import config.Metric;
import zabbix.ZabbixClient;
import telegram.TelegramNotifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricPoller {
    private final List<Metric> metrics;
    private final ZabbixClient client;
    private final TelegramNotifier notifier;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService pool;
    private final int periodSeconds;
    private final int statusPrintIntervalMinutes;

    private final ConcurrentMap<Long, Double> lastValues = new ConcurrentHashMap<>();

    public MetricPoller(List<Metric> metrics, ZabbixClient client, TelegramNotifier notifier, int poolSize, int periodSeconds, int statusPrintIntervalMinutes) {
        this.metrics = metrics;
        this.client = client;
        this.notifier = notifier;
        this.periodSeconds = periodSeconds;
        this.statusPrintIntervalMinutes = statusPrintIntervalMinutes;
        this.pool = Executors.newFixedThreadPool(poolSize);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollAll, 0, periodSeconds, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            pool.shutdown();
        }));
    }

    private void pollAll() {
        final AtomicInteger successCount = new AtomicInteger(0);

        LocalDateTime now = LocalDateTime.now();
        int currentMinute = now.getMinute();
        String nowStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        boolean printStatusNow = (currentMinute % statusPrintIntervalMinutes == 0);

        for (Metric m : metrics) {
            final Metric metric = m;
            String statusMsg = null;

            if (metric.getMetricId() == null) {
                statusMsg = String.format("[%s] [SKIP] %s %s: No itemId found (not monitored)",
                        nowStr, metric.getHost(), metric.getKey());
                if (printStatusNow) System.out.println(statusMsg);
                continue;
            }

            pool.submit(() -> {
                String threadMsg = null;
                try {
                    ZabbixValue val = client.getLastValueAndClock(metric.getMetricId());
                    if (val == null) {
                        lastValues.remove(metric.getMetricId());
                        threadMsg = String.format("[%s] [NO DATA] %s %s: itemId=%s, Zabbix returned no values (maybe item not supported, not updating or no data yet)",
                                nowStr, metric.getHost(), metric.getKey(), metric.getMetricId());
                        if (printStatusNow) System.out.println(threadMsg);
                        return;
                    }
                    successCount.incrementAndGet();
                    lastValues.put(metric.getMetricId(), val.value);

                    double value = val.value;
                    long lastClock = val.clock;
                    long nowEpoch = Instant.now().getEpochSecond();

                    // Check for old value
                    if (nowEpoch - lastClock > 600) {
                        threadMsg = String.format("[%s] [ALERT: OLD_VALUE] %s %s: itemId=%s, value=%s, last update=%d sec ago (>600)",
                                nowStr, metric.getHost(), metric.getKey(), metric.getMetricId(), value, nowEpoch - lastClock);
                        if (!AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE)) {
                            notifier.sendMessage("Metric " + metric.getKey() +
                                    " on " + metric.getHost() + " has not been updated for more than 10 minutes.");
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE);
                        }
                        if (printStatusNow) System.out.println(threadMsg);
                        return;
                    }

                    // Check if value is above threshold
                    if (value > metric.getThresholdHigh()) {
                        threadMsg = String.format("[%s] [ALERT: OVER] %s %s: itemId=%s, value=%s > thresholdHigh=%s",
                                nowStr, metric.getHost(), metric.getKey(), metric.getMetricId(), value, metric.getThresholdHigh());
                        if (!AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {
                            notifier.sendMessage("ALERT: " + metric.getKey() + " on " + metric.getHost() +
                                    " exceeded threshold: " + value + " > " + metric.getThresholdHigh());
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OVER);
                        }
                    } else {
                        // OK, back to normal
                        threadMsg = String.format("[%s] [OK] %s %s: itemId=%s, value=%s, in range (<=%s)",
                                nowStr, metric.getHost(), metric.getKey(), metric.getMetricId(), value, metric.getThresholdHigh());
                        if (AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {
                            notifier.sendMessage("Metric " + metric.getKey() + " on " + metric.getHost() + " returned to normal: " + value);
                            AlertManager.clearAlert(metric.getMetricId());
                        }
                        if (AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE)) {
                            notifier.sendMessage("Metric " + metric.getKey() + " on " + metric.getHost() + " is updating again: " + value);
                            AlertManager.clearAlert(metric.getMetricId());
                        }
                    }

                    // Restore ZABBIX_DOWN if previously set and now ok
                    if (AlertManager.isGlobalZabbixDown()) {
                        notifier.sendMessage("Connection to Zabbix is restored.");
                        AlertManager.clearGlobalZabbixDown();
                    }

                } catch (Exception e) {
                    threadMsg = String.format("[%s] [ERROR] %s %s: itemId=%s, %s",
                            nowStr, metric.getHost(), metric.getKey(), metric.getMetricId(), e.getMessage());
                }
                if (printStatusNow && threadMsg != null) {
                    System.out.println(threadMsg);
                }
            });
        }

        // Global ZABBIX_DOWN logic (check after a short delay)
        scheduler.schedule(() -> {
            if (successCount.get() == 0 && !AlertManager.isGlobalZabbixDown()) {
                final String zabbixDownStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                try {
                    if (printStatusNow)
                        System.out.printf("[%s] [ALERT] ZABBIX DOWN: No data from all monitored metrics!%n", zabbixDownStr);
                    notifier.sendMessage("ZABBIX DOWN: Unable to get data for all metrics. Zabbix may be unavailable.");
                    AlertManager.setGlobalZabbixDown();
                } catch (Exception e) {
                    System.err.println("Error sending global ZABBIX_DOWN alert: " + e.getMessage());
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    public static class ZabbixValue {
        public final double value;
        public final long clock;
        public ZabbixValue(double value, long clock) {
            this.value = value;
            this.clock = clock;
        }
    }
}
