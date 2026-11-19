package com.yuewei.reader.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** 书架：自动同步、TXT 导入、在线搜索与删除。 */
public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_TXT = 1001;

    private Library library;
    private AppSettings settings;
    private Ui.Palette palette;
    private String themeKey;
    private LinearLayout bookList;
    private LinearLayout heroHolder;
    private LinearLayout emptyState;
    private ScrollView shelfScroll;
    private TextView syncStatus;
    private TextView syncAction;
    private DownloadManager download;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        settings = new AppSettings(this);
        themeKey = settings.theme();
        palette = Ui.palette(settings.theme());
        buildViews();
        Ui.systemBars(this, palette);
        refreshShelf();
        updateSyncStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings = new AppSettings(this);
        if (!settings.theme().equals(themeKey)) {
            recreate();
            return;
        }
        library.reload();
        refreshShelf();
        updateSyncStatus();
        autoSync(5_000);
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bg);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(Ui.SPACE_PAGE), dp(Ui.HEADER_TOP),
                dp(Ui.SPACE_PAGE), dp(6));
        root.addView(header);

        TextView seal = new TextView(this);
        seal.setText("阅微");
        seal.setTextSize(10);
        seal.setTypeface(Ui.SERIF_BOLD);
        seal.setTextColor(0xFFFFF6EA);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(Ui.round(this, palette.accent, 5));
        LinearLayout.LayoutParams sealParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        sealParams.setMargins(0, 0, dp(10), 0);
        header.addView(seal, sealParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = Ui.pageTitle(this, palette, "书架");
        titles.addView(title);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(statusRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        syncStatus = new TextView(this);
        syncStatus.setTextSize(Ui.TEXT_META);
        syncStatus.setTextColor(palette.muted);
        syncStatus.setLetterSpacing(0.04f);
        statusRow.addView(syncStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        syncAction = new TextView(this);
        syncAction.setText("同步");
        syncAction.setTextSize(Ui.TEXT_META);
        syncAction.setTextColor(palette.accent);
        syncAction.setLetterSpacing(0.06f);
        syncAction.setPadding(dp(10), dp(4), 0, dp(4));
        syncAction.setClickable(true);
        syncAction.setOnClickListener(v -> manualSync());
        statusRow.addView(syncAction);

        syncStatus.setOnClickListener(v -> manualSync());

        TextView settingsButton = Ui.circleButton(this, palette, "⚙");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settingsButton);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(Ui.SPACE_PAGE), dp(2), dp(Ui.SPACE_PAGE), dp(8));
        root.addView(actions);

        TextView importButton = actionButton("导入 TXT", true, 1.35f);
        importButton.setOnClickListener(v -> openTxt());
        actions.addView(importButton);

        TextView search = actionButton("搜索", false, 1f);
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        actions.addView(search);

        TextView sources = actionButton("书源", false, 1f);
        sources.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
        actions.addView(sources);

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyState.setPadding(dp(Ui.SPACE_PAGE), dp(38), dp(Ui.SPACE_PAGE), dp(16));
        root.addView(emptyState);

        TextView emptySeal = new TextView(this);
        emptySeal.setText("阅");
        emptySeal.setTextSize(20);
        emptySeal.setTypeface(Ui.SERIF_BOLD);
        emptySeal.setTextColor(0xFFFFF6EA);
        emptySeal.setGravity(Gravity.CENTER);
        emptySeal.setBackground(Ui.round(this, palette.accent, 6));
        LinearLayout.LayoutParams emptySealParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        emptySealParams.setMargins(0, 0, 0, dp(14));
        emptyState.addView(emptySeal, emptySealParams);

        TextView emptyTitle = new TextView(this);
        emptyTitle.setText("书架还是空的");
        emptyTitle.setTextSize(Ui.TEXT_BOOK);
        emptyTitle.setTypeface(Ui.SERIF_BOLD);
        emptyTitle.setTextColor(palette.ink);
        emptyState.addView(emptyTitle);

        TextView emptyText = new TextView(this);
        emptyText.setText("导入本地 TXT，或去搜索里找一本在线书。");
        emptyText.setTextSize(Ui.TEXT_HINT);
        emptyText.setTextColor(palette.muted);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(dp(4), 1f);
        emptyText.setPadding(0, dp(8), 0, 0);
        emptyState.addView(emptyText);

        LinearLayout emptyActions = new LinearLayout(this);
        emptyActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams emptyActionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        emptyActionParams.setMargins(0, dp(18), 0, 0);
        emptyState.addView(emptyActions, emptyActionParams);

        TextView emptyImport = Ui.pill(this, "导入 TXT", true, palette);
        emptyImport.setOnClickListener(v -> openTxt());
        emptyActions.addView(emptyImport, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView emptySearch = Ui.pill(this, "去搜索", false, palette);
        emptySearch.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        LinearLayout.LayoutParams emptySearchParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        emptySearchParams.setMargins(dp(8), 0, 0, 0);
        emptyActions.addView(emptySearch, emptySearchParams);

        heroHolder = new LinearLayout(this);
        heroHolder.setOrientation(LinearLayout.VERTICAL);
        root.addView(heroHolder);

        shelfScroll = new ScrollView(this);
        shelfScroll.setClipToPadding(false);
        bookList = new LinearLayout(this);
        bookList.setOrientation(LinearLayout.VERTICAL);
        bookList.setPadding(dp(Ui.SPACE_PAGE), dp(4), dp(Ui.SPACE_PAGE), dp(24));
        shelfScroll.addView(bookList);
        root.addView(shelfScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private TextView actionButton(String text, boolean primary, float weight) {
        TextView view = Ui.pill(this, text, primary, palette);
        view.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(4), 0, dp(4), 0);
        view.setLayoutParams(params);
        return view;
    }

    private void refreshShelf() {
        bookList.removeAllViews();
        List<Library.Book> visible = SyncManager.sortedBooks(library);
        boolean empty = visible.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        heroHolder.setVisibility(empty ? View.GONE : View.VISIBLE);
        shelfScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            heroHolder.removeAllViews();
            heroHolder.addView(heroCard(visible.get(0)));
        }
        for (int i = 0; i < visible.size(); i++) {
            bookList.addView(bookRow(visible.get(i), i));
            if (i < visible.size() - 1) {
                bookList.addView(rowDivider());
            }
        }
    }

    /** 书目行之间的发丝线。 */
    private View rowDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(28), 0, 0, 0);
        divider.setLayoutParams(params);
        return divider;
    }

    /** 继续阅读：竖排书脊 + 四层信息。 */
    private View heroCard(Library.Book book) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11), dp(11), dp(11), dp(11));
        card.setBackground(Ui.ripple(this, Ui.round(this, palette.card, Ui.RADIUS_CARD), 0x14000000));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(this, Ui.SPACE_PAGE), dp(4),
                Ui.dp(this, Ui.SPACE_PAGE), dp(8));
        card.setLayoutParams(params);

        TextView spine = Ui.spine(this, palette, book.title, 40, 72);
        LinearLayout.LayoutParams spineParams = new LinearLayout.LayoutParams(dp(40), dp(72));
        spineParams.setMargins(0, 0, dp(10), 0);
        card.addView(spine, spineParams);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("在读");
        eyebrow.setTextSize(10.5f);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTextColor(palette.muted);
        info.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setPadding(0, dp(4), 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);

        int total = Math.max(1, book.chapterTitles.size());
        int chapter = Math.min(book.chapterIndex + 1, total);
        String chapterName = book.chapterTitles.isEmpty() ? "无章节"
                : book.chapterTitles.get(Math.min(book.chapterIndex, total - 1));
        TextView meta = new TextView(this);
        meta.setText((book.author == null || book.author.isBlank() ? "未知" : book.author)
                + " · 第 " + chapter + " 章 " + chapterName);
        meta.setTextSize(Ui.TEXT_META);
        meta.setTextColor(palette.muted);
        meta.setPadding(0, dp(3), 0, 0);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(meta);

        Ui.ProgressLine bar = Ui.progressLine(this, palette);
        bar.setFraction(Ui.fraction(book.percent));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        barParams.setMargins(0, dp(7), 0, 0);
        info.addView(bar, barParams);

        card.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setText("继续阅读");
        action.setTextSize(10.5f);
        action.setLetterSpacing(0.06f);
        action.setTextColor(palette.accent);
        action.setPadding(dp(10), 0, 0, 0);
        card.addView(action);

        card.setOnClickListener(v -> openBook(book));
        return card;
    }

    /** 书目行：序号 + 书名 / 来源章节 + 行内细进度。 */
    private View bookRow(Library.Book book, int position) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(14), dp(4), dp(14));
        row.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 0), 0x14000000));

        TextView index = new TextView(this);
        index.setText(String.format(java.util.Locale.ROOT, "%02d", position + 1));
        index.setTextSize(10.5f);
        index.setTypeface(android.graphics.Typeface.MONOSPACE);
        index.setTextColor(palette.muted);
        index.setGravity(Gravity.TOP);
        index.setPadding(0, dp(2), 0, 0);
        row.addView(index, new LinearLayout.LayoutParams(dp(20),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextSize(Ui.TEXT_ROW);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);

        String chapter = book.chapterTitles.isEmpty() ? "无章节"
                : book.chapterTitles.get(Math.min(book.chapterIndex, book.chapterTitles.size() - 1));
        String sourceName = Ui.clean(book.sourceName);
        String origin = "ONLINE".equals(book.sourceType) && !sourceName.isEmpty()
                ? " · " + sourceName : "";
        TextView meta = new TextView(this);
        meta.setText(book.author + origin + " · " + chapter);
        meta.setTextSize(Ui.TEXT_META);
        meta.setTextColor(palette.muted);
        meta.setPadding(0, dp(3), 0, 0);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(meta);

        if (book.percent > 0) {
            Ui.ProgressLine line = Ui.progressLine(this, palette);
            line.setFraction(Ui.fraction(book.percent));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
            lineParams.setMargins(0, dp(5), 0, 0);
            info.addView(line, lineParams);
        }

        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.setMargins(dp(10), 0, dp(10), 0);
        row.addView(info, infoParams);

        TextView percent = new TextView(this);
        percent.setText(SyncManager.percentText(book));
        percent.setTextSize(10.5f);
        percent.setTypeface(android.graphics.Typeface.MONOSPACE);
        percent.setTextColor(palette.accent);
        row.addView(percent);

        row.setOnClickListener(v -> openBook(book));
        row.setOnLongClickListener(v -> {
            showBookActions(book);
            return true;
        });
        return row;
    }

    private void openBook(Library.Book book) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        startActivity(intent);
    }

    /** 长按书目：底部操作面板，替代系统 AlertDialog 列表。 */
    private void showBookActions(Library.Book book) {
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));

        content.addView(Ui.grabber(this, palette));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(20), 0, dp(20), dp(10));
        TextView title = new TextView(this);
        title.setText(book.title);
        title.setTextSize(Ui.TEXT_BOOK);
        title.setTypeface(Ui.SERIF_BOLD);
        title.setTextColor(palette.ink);
        heading.addView(title);
        TextView meta = new TextView(this);
        meta.setText((book.author == null ? "未知" : book.author)
                + " · " + SyncManager.percentText(book));
        meta.setTextSize(Ui.TEXT_META);
        meta.setTextColor(palette.muted);
        meta.setPadding(0, dp(3), 0, 0);
        heading.addView(meta);
        content.addView(heading);
        content.addView(Ui.hairline(this, palette));

        boolean online = "ONLINE".equals(book.sourceType);
        if (online) {
            content.addView(Ui.actionRow(this, palette, "下载整本",
                    "抓取全部章节到本机，可随时继续补齐", false,
                    () -> {
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        downloadBook(book);
                    }));
        }
        content.addView(Ui.actionRow(this, palette, "移出书架",
                "其他设备同步后也会删除", true,
                () -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    confirmRemove(book);
                }));
        content.addView(Ui.actionRow(this, palette, "取消", null, false,
                () -> {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }));

        Dialog dialog = Ui.sheet(this, palette, content, 0);
        holder[0] = dialog;
        dialog.show();
    }

    /** 移出书架：只做墓碑标记，交给同步传播。 */
    private void confirmRemove(Library.Book book) {
        Ui.dialog(this, settings.theme())
                .setTitle("移出书架")
                .setMessage("删除《" + book.title + "》？其他设备同步后也会删除。")
                .setPositiveButton("删除", (dialog, which) -> {
                    library.markDeleted(book);
                    refreshShelf();
                    boolean started = SyncManager.autoSync(this, 0, (message, ok) -> {
                        if (ok) {
                            updateSyncStatus();
                        } else if (message != null && !message.isEmpty()) {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                    if (!started) {
                        Toast.makeText(this, "已移出书架，点「同步」同步删除",
                                Toast.LENGTH_LONG).show();
                    } else {
                        updateSyncStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 在线书整本下载：顺序抓取，可取消；再次下载会补齐失败章节。 */
    private void downloadBook(Library.Book book) {
        if (download == null) {
            download = new DownloadManager();
        }
        if (download.running()) {
            Toast.makeText(this, "已有下载任务在进行", Toast.LENGTH_SHORT).show();
            return;
        }
        final AlertDialog progress = Ui.dialog(this, settings.theme())
                .setTitle("下载整本")
                .setMessage("准备下载…")
                .setNegativeButton("取消", (dialog, which) -> download.cancel())
                .setCancelable(false)
                .create();
        progress.show();
        boolean started = download.start(this, library, book, new DownloadManager.Listener() {
            @Override
            public void progress(int done, int total, int failed) {
                if (progress.isShowing()) {
                    progress.setMessage("正在下载 " + done + "/" + total
                            + (failed > 0 ? "\n失败 " + failed + " 章，完成后可再次下载补齐" : ""));
                }
            }

            @Override
            public void finished(int done, int failed, boolean cancelled) {
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                String message = cancelled
                        ? "已取消下载（完成 " + done + " 章）"
                        : "下载完成：" + done + " 章"
                        + (failed > 0 ? "，失败 " + failed + " 章" : "");
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                refreshShelf();
            }
        });
        if (!started) {
            progress.dismiss();
            Toast.makeText(this, "这本书暂时无法下载（书源缺失或章节为空）",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openTxt() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQUEST_OPEN_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_TXT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String name = displayName(uri);
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("无法读取文件");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                Library.Book book = TxtImporter.importBook(library, name, out.toByteArray());
                runOnUiThread(() -> {
                    refreshShelf();
                    Toast.makeText(this, "已导入《" + book.title + "》", Toast.LENGTH_SHORT).show();
                    autoSync(0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "yuewei-import").start();
    }

    /** 手动同步：状态行进入“同步中”，结束后刷新书架与状态。 */
    private void manualSync() {
        if (SyncManager.running()) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        syncAction.setEnabled(false);
        syncAction.setText("同步中");
        SyncManager.sync(this, (message, ok) -> {
            syncAction.setEnabled(true);
            syncAction.setText("同步");
            library.reload();
            refreshShelf();
            updateSyncStatus();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
        updateSyncStatus();
    }

    /** 自动同步不打断阅读：成功只更新顶部时间，失败才提示。 */
    private void autoSync(long minIntervalMs) {
        boolean started = SyncManager.autoSync(this, minIntervalMs, (message, ok) -> {
            if (ok) {
                library.reload();
                refreshShelf();
                updateSyncStatus();
            } else if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                updateSyncStatus();
            }
        });
        if (started) {
            updateSyncStatus();
        }
    }

    private void updateSyncStatus() {
        if (syncStatus == null) {
            return;
        }
        boolean running = SyncManager.running();
        syncStatus.setText(running ? "正在同步…" : SyncManager.lastSyncText(this));
        if (syncAction != null) {
            syncAction.setText(running ? "同步中" : "同步");
            syncAction.setEnabled(!running);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
            // 取不到名字时用固定标题
        }
        return "未命名.txt";
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
