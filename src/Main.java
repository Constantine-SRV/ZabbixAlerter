import config.*;
import zabbix.ZabbixClient;
import alerter.MetricPoller;
import telegram.TelegramNotifier;

import java.io.File;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/**
 * Main entry point for ZabbixAlerter.
 * CLI arg0 (optional) = status-print interval in minutes.
 */
public class Main {

    /** UTC timestamp without seconds, e.g. 2025-07-12T07:55Z */
    private static final DateTimeFormatter ISO_MIN_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")
                    .withZone(ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {

        /* ---- 1. Parse CLI argument (status interval, min) ---- */
        int statusInterval = 1;                         // default = 1 min
        if (args.length > 0) {
            try {
                int v = Integer.parseInt(args[0]);
                if (v > 0) statusInterval = v;
            } catch (NumberFormatException ign) {
                System.err.println("Invalid interval '" + args[0] + "', using 1 min.");
            }
        }

        /* ---- 2. Compose startup banner ---- */
        String jarStamp = jarUtcStamp();                // JAR:2025-07-12T07:55Z or debug
        String cfgStamp = fileUtcStamp("metricsettings.xml");
        String startMsg = String.format(
                "ZabbixAlerter started  JAR:%s  CFG:%s", jarStamp, cfgStamp);

        /* ---- 3. Load XML config & resolve itemIds ---- */
        MetricsConfig cfg = ConfigLoader.load();
        ZabbixClient   zbx = new ZabbixClient(
                Secrets.ZABBIX_URL(), Secrets.ZABBIX_API_TOKEN());

        ExecutorService resolvePool = Executors.newFixedThreadPool(10);
        for (Metric m : cfg.getMetricList()) {
            resolvePool.submit(() -> {
                try {
                    Long id = zbx.resolveItemId(m.getHost(), m.getKey());
                    m.setMetricId(id);
                    System.out.printf("Host=%s Key=%s ⇒ itemId=%s%n",
                            m.getHost(), m.getKey(), id != null ? id : "NOT FOUND");
                } catch (Exception e) {
                    System.err.printf("Resolve error %s %s: %s%n",
                            m.getHost(), m.getKey(), e.getMessage());
                }
            });
        }
        resolvePool.shutdown();
        resolvePool.awaitTermination(30, TimeUnit.SECONDS);

        /* ---- 4. Telegram notifier + send banner ---- */
        TelegramNotifier tg = new TelegramNotifier(
                Secrets.TELEGRAM_BOT_TOKEN(),
                Secrets.TELEGRAM_CHAT_ID());

        new Thread(() -> {
            try { tg.sendMessage(startMsg); }
            catch (Exception ex) { System.err.println("TG send error: "+ex.getMessage()); }
        }).start();

        /* ---- 5. Start poller ---- */
        MetricPoller poller = new MetricPoller(
                cfg.getMetricList(), zbx, tg,
                5,          // threads
                60,         // poll period (sec)
                statusInterval);
        poller.start();

        /* ---- keep JVM alive ---- */
        Thread.currentThread().join();
    }

    /* ----- helpers ----- */

    /** Returns UTC build time of the running JAR, or "debug" when run from IDE. */
    private static String jarUtcStamp() {
        try {
            File jar = new File(Main.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (jar.isFile()) {
                return ISO_MIN_UTC.format(Instant.ofEpochMilli(jar.lastModified()));
            }
        } catch (URISyntaxException ignored) {}
        return "debug";
    }

    /** Returns UTC mtime for external file or "unknown" if not found. */
    private static String fileUtcStamp(String name) {
        File f = new File(name);
        return f.exists()
                ? ISO_MIN_UTC.format(Instant.ofEpochMilli(f.lastModified()))
                : "unknown";
    }
}
