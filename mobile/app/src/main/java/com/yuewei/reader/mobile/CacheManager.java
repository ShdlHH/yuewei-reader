package com.yuewei.reader.mobile;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 在线书正文缓存：统计、清理与超限裁剪；本地导入的 TXT 章节永不删除。 */
final class CacheManager {

    private CacheManager() {
    }

    static long onlineCacheBytes(Library library) {
        synchronized (library) {
            long total = 0;
            for (File file : onlineChapterFiles(library)) {
                total += file.length();
            }
            return total;
        }
    }

    /** 清理在线书缓存，返回删除的文件数。 */
    static int clearOnlineCache(Library library) {
        synchronized (library) {
            int deleted = 0;
            for (File file : onlineChapterFiles(library)) {
                if (file.delete()) {
                    deleted++;
                }
            }
            return deleted;
        }
    }

    /**
     * 超过上限时按最后修改时间从旧到新删除在线章节缓存。
     * 当前正在阅读/下载的书整本跳过，避免刚看过的内容被清掉。
     */
    static void pruneOnline(Library library, long limitBytes, String keepBookId) {
        if (limitBytes <= 0) {
            return;
        }
        synchronized (library) {
            long total = onlineCacheBytes(library);
            if (total <= limitBytes) {
                return;
            }
            List<File> files = onlineChapterFiles(library);
            files.sort(Comparator.comparingLong(File::lastModified));
            for (File file : files) {
                if (total <= limitBytes) {
                    break;
                }
                if (keepBookId != null
                        && library.bookDir(keepBookId).equals(file.getParentFile())) {
                    continue;
                }
                long size = file.length();
                if (file.delete()) {
                    total -= size;
                }
            }
        }
    }

    private static List<File> onlineChapterFiles(Library library) {
        List<File> files = new ArrayList<>();
        for (Library.Book book : library.books) {
            if (!"ONLINE".equals(book.sourceType)) {
                continue;
            }
            File[] children = library.bookDir(book.id).listFiles();
            if (children == null) {
                continue;
            }
            for (File file : children) {
                if (file.isFile() && file.getName().endsWith(".txt")) {
                    files.add(file);
                }
            }
        }
        return files;
    }
}
