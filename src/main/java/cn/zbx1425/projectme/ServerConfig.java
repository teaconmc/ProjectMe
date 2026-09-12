package cn.zbx1425.projectme;

import com.google.common.base.CaseFormat;
import com.google.common.base.Function;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class ServerConfig {

    public ConfigItem<String> redisUrl;
    public ConfigItem<Integer> syncInterval;
    public ConfigItem<String> peerId;
    public ConfigItem<Integer> peerTimeout;

    public void load(Path configPath) throws IOException {
        JsonObject json = Files.exists(configPath)
                ? JsonParser.parseString(Files.readString(configPath)).getAsJsonObject()
                : new JsonObject();
        redisUrl = new ConfigItem<>(json, "redisUrl", "");
        syncInterval = new ConfigItem<>(json, "syncInterval", "1",
                Object::toString, Integer::parseInt);
        peerId = new ConfigItem<>(json, "peerId", "");
        peerTimeout = new ConfigItem<>(json, "peerTimeout", "100",
                Object::toString, Integer::parseInt);

        if (peerId.value.isEmpty()) {
            peerId.value = resolvePeerId();
        }

        if (!Files.exists(configPath)) save(configPath);
    }

    private static String resolvePeerId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isEmpty()) return hostname;
        try {
            String localHostName = InetAddress.getLocalHost().getHostName();
            if (localHostName != null && !localHostName.isEmpty()) return localHostName;
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString();
    }

    public void save(Path configPath) throws IOException {
        JsonObject json = new JsonObject();
        redisUrl.writeJson(json);
        syncInterval.writeJson(json);
        peerId.writeJson(json);
        peerTimeout.writeJson(json);

        Files.writeString(configPath, new GsonBuilder().setPrettyPrinting().create().toJson(json));
    }

    public static class ConfigItem<T> {

        private final String camelKey;
        public T value;
        public boolean isFromJson;

        private final Function<T, String> serializer;

        public ConfigItem(JsonObject jsonObject, String camelKey, String defaultValue,
                          Function<T, String> serializer, Function<String, T> deSerializer) {
            String snakeKey = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, camelKey);
            this.camelKey = camelKey;
            if (System.getenv("PROJECTME_" + snakeKey) != null) {
                this.value = deSerializer.apply(System.getenv("PROJECTME_" + snakeKey));
                this.isFromJson = false;
            } else if (jsonObject.has(camelKey)) {
                if (jsonObject.get(camelKey).isJsonArray()) {
                    StringBuilder configValue = new StringBuilder();
                    for (int i = 0; i < jsonObject.get(camelKey).getAsJsonArray().size(); i++) {
                        configValue.append(jsonObject.get(camelKey).getAsJsonArray().get(i).getAsString());
                    }
                    this.value = deSerializer.apply(configValue.toString());
                } else {
                    this.value = deSerializer.apply(jsonObject.get(camelKey).getAsString());
                }
                this.isFromJson = true;
            } else {
                this.value = deSerializer.apply(defaultValue);
                this.isFromJson = false;
            }
            this.serializer = serializer;
        }

        @SuppressWarnings("unchecked")
        public ConfigItem(JsonObject jsonObject, String camelKey, String defaultValue) {
            this(jsonObject, camelKey, defaultValue, Object::toString, (s) -> (T) s);
        }

        public void writeJson(JsonObject jsonObject) {
            if (isFromJson) {
                jsonObject.addProperty(camelKey, serializer.apply(value));
            }
        }
    }
}
