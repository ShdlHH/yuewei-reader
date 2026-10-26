package com.yuewei.reader.application;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** TXT 导入前预览：编码、章节数、失败原因。 */
public class ImportPreview {
    public String fileName;
    public String defaultTitle;
    public Charset charset;
    public long fileSize;
    public int chapterCount;
    public List<String> chapterTitles = new ArrayList<>();
    public String error;

    public boolean splittable() {
        return error == null;
    }
}
