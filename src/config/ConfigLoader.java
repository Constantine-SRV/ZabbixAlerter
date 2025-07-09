package config;

import java.io.*;
import java.nio.file.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public final class ConfigLoader {

    private static final String FILENAME = "metricsettings.xml";
    private static final XmlMapper MAPPER = new XmlMapper();

    // запрещаем создавать экземпляры
    private ConfigLoader() { }

    public static MetricsConfig load() throws IOException {
        Path cfgPath = Paths.get(FILENAME).toAbsolutePath();
        try (Reader r = Files.newBufferedReader(cfgPath)) {
            return MAPPER.readValue(r, MetricsConfig.class);
        }
    }
}
