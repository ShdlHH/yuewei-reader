package com.yuewei.reader.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内存版 WebDAV：测试同步逻辑，不发真实网络请求。 */
class FakeWebDav extends WebDav {

    final Map<String, String> files = new LinkedHashMap<>();

    FakeWebDav() {
        super("http://127.0.0.1/dav", "yuewei", "", "");
    }

    @Override
    public void ensureDir() {
        // 内存实现不需要建目录
    }

    @Override
    public void put(String path, String json) {
        files.put(path, json);
    }

    @Override
    public String get(String path) {
        return files.get(path);
    }

    @Override
    public List<RemoteFile> list(String directory) {
        String prefix = directory == null || directory.isEmpty() ? "" : directory + "/";
        List<RemoteFile> result = new ArrayList<>();
        for (String path : files.keySet()) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String name = path.substring(prefix.length());
            if (!name.isEmpty() && !name.contains("/")) {
                result.add(new RemoteFile(name, 0));
            }
        }
        return result;
    }
}
