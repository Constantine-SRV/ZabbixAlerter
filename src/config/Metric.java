package config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class Metric {

    @JacksonXmlProperty(localName = "host")
    private String host;

    @JacksonXmlProperty(localName = "key")
    private String key;

    // заполним позже, когда найдем itemId в Zabbix
    @JacksonXmlProperty(localName = "metricId")
    private Long metricId;

    @JacksonXmlProperty(localName = "thresholdHigh")
    private double thresholdHigh;

    @JacksonXmlProperty(localName = "thresholdLow")
    private double thresholdLow;

    public Metric() { }          // нужен Jackson

    // --- getters / setters ---
    public String getHost()              { return host; }
    public void   setHost(String host)   { this.host = host; }

    public String getKey()               { return key; }
    public void   setKey(String key)     { this.key = key; }

    public Long   getMetricId()          { return metricId; }
    public void   setMetricId(Long id)   { this.metricId = id; }

    public double getThresholdHigh()     { return thresholdHigh; }
    public void   setThresholdHigh(double v) { this.thresholdHigh = v; }

    public double getThresholdLow()      { return thresholdLow; }
    public void   setThresholdLow(double v)  { this.thresholdLow = v; }
}
