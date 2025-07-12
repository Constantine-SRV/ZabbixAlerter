package config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Metric
 * ------
 * Describes a single Zabbix item with dual thresholds (hysteresis).
 *
 * alertType :
 *   MAX – alert when value >= thresholdHigh, clear when value <= thresholdLow
 *   MIN – alert when value <= thresholdLow,  clear when value >= thresholdHigh
 */
public class Metric {

    public enum AlertType { MAX, MIN }

    @JacksonXmlProperty(localName = "host")
    private String host;

    @JacksonXmlProperty(localName = "key")
    private String key;

    /** Filled later after itemId resolution */
    private Long metricId;

    /** Upper numeric bound (used either for trigger or clear depending on alertType) */
    @JacksonXmlProperty(localName = "thresholdHigh")
    private double thresholdHigh;

    /** Lower numeric bound */
    @JacksonXmlProperty(localName = "thresholdLow")
    private double thresholdLow;

    /** How to interpret thresholds: MAX or MIN (default = MAX) */
    @JacksonXmlProperty(localName = "alertType")
    private AlertType alertType = AlertType.MAX;

    public Metric() {}

    /* -------- getters / setters -------- */
    public String getHost()                     { return host; }
    public void   setHost(String host)          { this.host = host; }

    public String getKey()                      { return key; }
    public void   setKey(String key)            { this.key = key; }

    public Long   getMetricId()                 { return metricId; }
    public void   setMetricId(Long id)          { this.metricId = id; }

    public double getThresholdHigh()            { return thresholdHigh; }
    public void   setThresholdHigh(double v)    { this.thresholdHigh = v; }

    public double getThresholdLow()             { return thresholdLow; }
    public void   setThresholdLow(double v)     { this.thresholdLow = v; }

    public AlertType getAlertType()             { return alertType; }
    public void setAlertType(AlertType t)       { this.alertType = t; }

    /* helpers */
    public boolean isMaxType() { return alertType == AlertType.MAX; }
}
