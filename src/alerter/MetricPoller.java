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

/**
 * MetricPoller
 * -------------
 * • Polls Zabbix items every <pollSec> seconds.<br>
 * • Works with MAX / MIN metrics (hysteresis).<br>
 * • Telegram messages now contain host and use tags
 *     “ALERT MAX / ALERT MIN” instead of OVER / UNDER.<br>
 * • Console shows “ALERT … ongoing” while alarm is active.
 */
public class MetricPoller {

    private final List<Metric>  metrics;
    private final ZabbixClient  client;
    private final TelegramNotifier notifier;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService pool;
    private final int pollSec;
    private final int statusEveryMin;

    private final ConcurrentMap<Long, Double> lastValues = new ConcurrentHashMap<>();

    public MetricPoller(List<Metric> metrics,
                        ZabbixClient client,
                        TelegramNotifier notifier,
                        int poolSize,
                        int pollSeconds,
                        int statusEveryMinutes) {

        this.metrics        = metrics;
        this.client         = client;
        this.notifier       = notifier;
        this.pollSec        = pollSeconds;
        this.statusEveryMin = statusEveryMinutes;
        this.pool           = Executors.newFixedThreadPool(poolSize);
        this.scheduler      = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollAll, 0, pollSec, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            pool.shutdown();
        }));
    }

    /* ---------------- main loop ---------------- */
    private void pollAll() {
        AtomicInteger success = new AtomicInteger(0);

        LocalDateTime now  = LocalDateTime.now();
        String ts          = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        boolean logNow     = (now.getMinute() % statusEveryMin == 0);

        for (Metric m : metrics) {
            if (m.getMetricId() == null) {
                if (logNow)
                    System.out.printf("[%s] [SKIP] %s %s: no itemId%n", ts, m.getHost(), m.getKey());
                continue;
            }
            final Metric metric = m;

            pool.submit(() -> {
                String line = null;

                try {
                    ZabbixValue zv = client.getLastValueAndClock(metric.getMetricId());
                    if (zv == null) {                          // NO DATA
                        lastValues.remove(metric.getMetricId());
                        if (!AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE)) {
                            notifier.sendMessage("OLD_VALUE: " + metric.getHost() + " " + metric.getKey());
                            AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE);
                        }
                        line = String.format("[%s] [NO DATA] %s %s",
                                ts, metric.getHost(), metric.getKey());
                    } else {
                        success.incrementAndGet();
                        double v = zv.value;
                        lastValues.put(metric.getMetricId(), v);

                        /* clear OLD_VALUE */
                        if (AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OLD_VALUE)) {
                            notifier.sendMessage("Value resumed: " + metric.getHost() + " " + metric.getKey() + " v=" + v);
                            AlertManager.clearAlert(metric.getMetricId());
                        }

                        /* ------------- HYSTERESIS ------------- */
                        if (metric.isMaxType()) {                // MAX
                            if (v >= metric.getThresholdHigh()
                                    && !AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {

                                notifier.sendMessage("ALERT MAX: " + metric.getHost() + " " + metric.getKey() + " " + v);
                                AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.OVER);
                                line = String.format("[%s] [ALERT MAX] %s %s v=%s >= %s",
                                        ts, metric.getHost(), metric.getKey(), v, metric.getThresholdHigh());
                            } else if (v <= metric.getThresholdLow()
                                    && AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.OVER)) {

                                notifier.sendMessage("CLEAR MAX: " + metric.getHost() + " " + metric.getKey() + " v=" + v);
                                AlertManager.clearAlert(metric.getMetricId());
                                line = String.format("[%s] [CLEAR] %s %s v=%s <= %s",
                                        ts, metric.getHost(), metric.getKey(), v, metric.getThresholdLow());
                            }
                        } else {                                // MIN
                            if (v <= metric.getThresholdLow()
                                    && !AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.UNDER)) {

                                notifier.sendMessage("ALERT MIN: " + metric.getHost() + " " + metric.getKey() + " " + v);
                                AlertManager.setAlert(metric.getMetricId(), AlertManager.AlertType.UNDER);
                                line = String.format("[%s] [ALERT MIN] %s %s v=%s <= %s",
                                        ts, metric.getHost(), metric.getKey(), v, metric.getThresholdLow());
                            } else if (v >= metric.getThresholdHigh()
                                    && AlertManager.wasAlerted(metric.getMetricId(), AlertManager.AlertType.UNDER)) {

                                notifier.sendMessage("CLEAR MIN: " + metric.getHost() + " " + metric.getKey() + " v=" + v);
                                AlertManager.clearAlert(metric.getMetricId());
                                line = String.format("[%s] [CLEAR] %s %s v=%s >= %s",
                                        ts, metric.getHost(), metric.getKey(), v, metric.getThresholdHigh());
                            }
                        }

                        /* ongoing / OK */
                        if (line == null && logNow) {
                            AlertManager.AlertType active = AlertManager.getAlert(metric.getMetricId());
                            if (active == AlertManager.AlertType.OVER || active == AlertManager.AlertType.UNDER) {
                                line = String.format("[%s] [ALERT %s] %s %s ongoing, value=%s",
                                        ts,
                                        active == AlertManager.AlertType.OVER ? "MAX" : "MIN",
                                        metric.getHost(), metric.getKey(), v);
                            } else {
                                line = String.format("[%s] [OK] %s %s: %s",
                                        ts, metric.getHost(), metric.getKey(), v);
                            }
                        }
                    }

                    /* global-down restore */
                    if (AlertManager.isGlobalDown() && success.get() > 0) {
                        notifier.sendMessage("Zabbix connection restored (" + metric.getHost() + ")");
                        AlertManager.clearGlobalDown();
                    }

                } catch (Exception e) {
                    line = String.format("[%s] [ERROR] %s %s: %s",
                            ts, metric.getHost(), metric.getKey(), e.getMessage());
                }
                if (logNow && line != null) System.out.println(line);
            });
        }

        /* global Zabbix-down check */
        scheduler.schedule(() -> {
            if (success.get() == 0 && !AlertManager.isGlobalDown()) {
                String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                System.out.printf("[%s] [ALERT] ZABBIX DOWN%n", t);
                try { notifier.sendMessage("ZABBIX DOWN: no data"); } catch (Exception ignored) {}
                AlertManager.setGlobalDown();
            }
        }, 5, TimeUnit.SECONDS);
    }

    /** DTO from history.get */
    public static class ZabbixValue {
        public final double value;
        public final long   clock;
        public ZabbixValue(double v, long c) { value = v; clock = c; }
    }
}
