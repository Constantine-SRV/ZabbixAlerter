package config;

import java.util.List;
import com.fasterxml.jackson.dataformat.xml.annotation.*;

@JacksonXmlRootElement(localName = "metrics")
public class MetricsConfig {

    // <metric>…</metric> без дополнительной обёртки
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "metric")
    private List<Metric> metricList;

    public MetricsConfig() { }

    public List<Metric> getMetricList()           { return metricList; }
    public void setMetricList(List<Metric> list)  { this.metricList = list; }
}
