package alerter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AlertManager is responsible for storing and managing active alerts.
 * It avoids duplicate alerts for the same metric and alert type.
 */
public class AlertManager {
    public enum AlertType { OVER, OLD_VALUE, ZABBIX_DOWN }

    // Store alert state: key = itemId, value = type of alert
    private static final ConcurrentMap<Long, AlertType> alerts = new ConcurrentHashMap<>();

    // For global ZABBIX_DOWN alert (applies to all metrics)
    private static final Long GLOBAL_ALERT_KEY = 0L;

    public static boolean wasAlerted(Long itemId, AlertType type) {
        return alerts.get(itemId) == type;
    }

    public static void setAlert(Long itemId, AlertType type) {
        alerts.put(itemId, type);
    }

    public static void clearAlert(Long itemId) {
        alerts.remove(itemId);
    }

    public static AlertType getAlert(Long itemId) {
        return alerts.get(itemId);
    }

    public static boolean isGlobalZabbixDown() {
        return wasAlerted(GLOBAL_ALERT_KEY, AlertType.ZABBIX_DOWN);
    }

    public static void setGlobalZabbixDown() {
        setAlert(GLOBAL_ALERT_KEY, AlertType.ZABBIX_DOWN);
    }

    public static void clearGlobalZabbixDown() {
        clearAlert(GLOBAL_ALERT_KEY);
    }
}
