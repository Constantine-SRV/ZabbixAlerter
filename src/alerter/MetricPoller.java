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
    private final int pollSec;
    private final int statusEveryMin;

    /* last values saved only for pretty console output */
    private final ConcurrentMap<Long, Double> lastValues = new ConcurrentHashMap<>();

    public MetricPoller(List<Metric> metrics,
                        ZabbixClient client,
                        TelegramNotifier notifier,
                        int poolSize,
                        int pollSeconds,
                        int statusEveryMinutes) {

        this.metrics = metrics;
        this.client = client;
        this.notifier = notifier;
        this.pollSec = pollSeconds;
        this.statusEveryMin = statusEveryMinutes;
        this.pool = Executors.newFixedThreadPool(poolSize);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollAll, 0, pollSec, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown(); pool.shutdown();
        }));
    }

    /** Single polling cycle for all metrics */
    private void pollAll() {
        AtomicInteger success = new AtomicInteger(0);
        LocalDateTime nowDT = LocalDateTime.now();
        String ts = nowDT.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        boolean logNow = (nowDT.getMinute() % statusEveryMin == 0);

        for (Metric m : metrics) {
            // skip unresolved
            if (m.getMetricId() == null) {
                if (logNow)
                    System.out.printf("[%s] [SKIP] %s %s: no itemId%n", ts, m.getHost(), m.getKey());
                continue;
            }
            final Metric metric = m;

            pool.submit(() -> {
                String msg = null;
                try {
                    ZabbixValue zv = client.getLastValueAndClock(metric.getMetricId());
                    if (zv == null) {                             // no data
                        lastValues.remove(metric.getMetricId());
                        msg = String.format("[%s] [NO DATA] %s %s (id=%s)",
                                ts, metric.getHost(), metric.getKey(), metric.getMetricId());
                        // old-value alert
                        if (!AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE)) {
                            notifier.sendMessage("OLD_VALUE: " + metric.getKey() + " on " + metric.getHost());
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE);
                        }
                        if (logNow) System.out.println(msg);
                        return;
                    }

                    success.incrementAndGet();
                    lastValues.put(metric.getMetricId(), zv.value);
                    double v   = zv.value;
                    long   age = Instant.now().getEpochSecond() - zv.clock;

                    // clear OLD_VALUE if data has resumed
                    if (AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE) && age < 600) {
                        notifier.sendMessage("Value resumed: " + metric.getKey() + " on " + metric.getHost() + " = " + v);
                        AlertManager.clearAlert(metric.getMetricId());
                    }

                    /* ---------- hysteresis ---------- */
                    if (metric.isMaxType()) {
                        // trigger
                        if (v >= metric.getThresholdHigh()
                                && !AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {

                            notifier.sendMessage("OVER: " + metric.getKey() + " " + v + " >= " + metric.getThresholdHigh());
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OVER);
                            msg = String.format("[%s] [ALERT: OVER] %s %s v=%s >= %s",
                                    ts, metric.getHost(), metric.getKey(), v, metric.getThresholdHigh());
                        }
                        // clear
                        if (v <= metric.getThresholdLow()
                                && AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {

                            notifier.sendMessage("CLEARED: " + metric.getKey() + " back to " + v);
                            AlertManager.clearAlert(metric.getMetricId());
                            msg = String.format("[%s] [CLEAR] %s %s v=%s <= %s",
                                    ts, metric.getHost(), metric.getKey(), v, metric.getThresholdLow());
                        }
                    } else { // MIN type
                        // trigger
                        if (v <= metric.getThresholdLow()
                                && !AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.UNDER)) {

                            notifier.sendMessage("UNDER: " + metric.getKey() + " " + v + " <= " + metric.getThresholdLow());
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.UNDER);
                            msg = String.format("[%s] [ALERT: UNDER] %s %s v=%s <= %s",
                                    ts, metric.getHost(), metric.getKey(), v, metric.getThresholdLow());
                        }
                        // clear
                        if (v >= metric.getThresholdHigh()
                                && AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.UNDER)) {

                            notifier.sendMessage("CLEARED: " + metric.getKey() + " back to " + v);
                            AlertManager.clearAlert(metric.getMetricId());
                            msg = String.format("[%s] [CLEAR] %s %s v=%s >= %s",
                                    ts, metric.getHost(), metric.getKey(), v, metric.getThresholdHigh());
                        }
                    }

                    if (msg == null && logNow) {          // regular OK line
                        msg = String.format("[%s] [OK] %s %s: %s", ts, metric.getHost(), metric.getKey(), v);
                    }

                } catch (Exception e) {
                    msg = String.format("[%s] [ERROR] %s %s: %s",
                            ts, metric.getHost(), metric.getKey(), e.getMessage());
                }
                if (logNow && msg != null) System.out.println(msg);
            });
        }

        /* global Zabbix-down check */
        scheduler.schedule(() -> {
            if (success.get() == 0 && !AlertManager.isGlobalDown()) {
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                System.out.printf("[%s] [ALERT] ZABBIX DOWN%n", now);
                try {
                    notifier.sendMessage("ZABBIX DOWN: no data for any metric");
                } catch (Exception ignored) {}
                AlertManager.setGlobalDown();
            }
        }, 5, TimeUnit.SECONDS);
    }

    /* holder */
    public static class ZabbixValue {
        public final double value;
        public final long   clock;
        public ZabbixValue(double v, long c) { value = v; clock = c; }
    }
}
