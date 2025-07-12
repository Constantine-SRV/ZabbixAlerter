package config;

import java.io.*;
import java.nio.file.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public final class ConfigLoader {
    private static final String FILENAME = "metricsettings.xml";
    private static final XmlMapper MAPPER = new XmlMapper();
    private ConfigLoader() {}

    public static MetricsConfig load() throws IOException {
        Path p = Paths.get(FILENAME).toAbsolutePath();
        try (Reader r = Files.newBufferedReader(p)) {
            return MAPPER.readValue(r, MetricsConfig.class);
        }
    }
}
