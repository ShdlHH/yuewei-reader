package com.yuewei.reader.mobile;

import android.content.Context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 书源仓库：保存 Legado 3.0 书源 JSON，供在线搜索与正文解析使用。 */
public class SourceStore {

    public static class Source {
        public String name;
        public String url;
        public String ruleJson;

        JSONObject toJson() throws Exception {
            JSONObject node = new JSONObject();
            node.put("name", name);
            node.put("url", url);
            node.put("ruleJson", ruleJson);
            return node;
        }

        static Source fromJson(JSONObject node) {
            Source source = new Source();
            source.name = node.optString("name");
            source.url = node.optString("url");
            source.ruleJson = node.optString("ruleJson");
            return source;
        }
    }

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();
    public final List<Source> sources = new ArrayList<>();

    public SourceStore(Context context) {
        file = new File(context.getFilesDir(), "sources.json");
        reload();
    }

    public void reload() {
        sources.clear();
        if (!file.isFile()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray array = root.optJSONArray("sources");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    sources.add(Source.fromJson(array.getJSONObject(i)));
                }
            }
        } catch (Exception ignored) {
            // 文件损坏时从空书源开始
        }
    }

    public void save() {
        try {
            JSONArray array = new JSONArray();
            for (Source source : sources) {
                array.put(source.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("sources", array);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 下次保存再试
        }
    }

    /** 支持单个书源、数组或 {"bookSources":[...]}；重复地址会被跳过。 */
    public int importJson(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else if (root.has("bookSources") && root.get("bookSources").isArray()) {
            root.get("bookSources").forEach(items::add);
        } else if (root.isObject()) {
            items.add(root);
        }
        int added = 0;
        for (JsonNode item : items) {
            String url = item.path("bookSourceUrl").asText(item.path("url").asText(""));
            String name = item.path("bookSourceName").asText(item.path("name").asText(""));
            if (url.isBlank() || name.isBlank() || find(url) != null) {
                continue;
            }
            Source source = new Source();
            source.name = name;
            source.url = url;
            source.ruleJson = item.toString();
            sources.add(source);
            added++;
        }
        if (added > 0) {
            save();
        }
        return added;
    }

    public Source find(String url) {
        for (Source source : sources) {
            if (source.url.equals(url)) {
                return source;
            }
        }
        return null;
    }

    public void remove(Source source) {
        sources.remove(source);
        save();
    }
}
