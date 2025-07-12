package config;

import java.util.List;
import com.fasterxml.jackson.dataformat.xml.annotation.*;

/** Root <metrics> wrapper */
@JacksonXmlRootElement(localName = "metrics")
public class MetricsConfig {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "metric")
    private List<Metric> metricList;

    public MetricsConfig() {}

    public List<Metric> getMetricList()         { return metricList; }
    public void setMetricList(List<Metric> list) { this.metricList = list; }
}
