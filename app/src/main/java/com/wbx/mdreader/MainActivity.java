package com.wbx.mdreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 2001;
    private static final int REQ_OPEN = 2002;
    private static final int REQ_EXPORT_HTML = 2003;
    private static final int REQ_SAVE_COPY = 2004;

    private Palette p;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MarkdownRenderer renderer = new MarkdownRenderer();

    private FrameLayout root;
    private LinearLayout drawer;
    private View scrim;
    private TextView drawerTitle;
    private TextView drawerHint;
    private TextView pathLabel;
    private LinearLayout fileList;
    private TextView upRow;

    private EditText editor;
    private WebView preview;
    private LinearLayout toolbar;
    private LinearLayout statusBar;
    private LinearLayout findBar;
    private EditText findInput;
    private EditText replaceInput;
    private TextView titleView;
    private TextView modeBtn;
    private TextView statusLeft;
    private TextView statusRight;
    private LinearLayout emptyView;

    private Workspace ws;
    private String dirId;
    private final ArrayList<String> dirStack = new ArrayList<String>();
    private final ArrayList<Workspace.Doc> docs = new ArrayList<Workspace.Doc>();

    private Uri docUri;
    private String docName = "";
    private boolean dirty;
    private boolean readOnly;
    private boolean previewMode;
    private boolean drawerOpen;
    private boolean loading;
    private boolean pendingPrint;
    private String pendingHtml;
    private int fontPx;

    private final Runnable highlightTask = new Runnable() {
        @Override
        public void run() {
            if (!previewMode) {
                SyntaxHighlighter.apply(editor, p);
            }
        }
    };

    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            saveNow(false);
        }
    };

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("md_reader", MODE_PRIVATE);
        boolean dark = prefs.getBoolean("dark", true);
        fontPx = prefs.getInt("fontPx", 15);
        setTheme(dark ? R.style.AppTheme : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
        p = Palette.of(dark);
        setContentView(buildRoot());
        applyChrome();

        ws = Workspace.load(this);
        if (ws != null) {
            dirId = prefs.getString("dirId", ws.rootId);
            refreshFiles();
        }
        if (!handleExternal(getIntent())) {
            openLastOrEmpty();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternal(intent);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(saveTask);
        saveNow(false);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            return;
        }
        if (dirty) {
            saveNow(true);
        }
        super.onBackPressed();
    }

    // ---------------- 界面搭建 ----------------

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(p.bg);
        root.addView(buildEditorPane(), lp(-1, -1));

        scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
        root.addView(scrim, lp(-1, -1));

        drawer = buildDrawer();
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82f), -1);
        root.addView(drawer, dlp);
        drawer.setTranslationX(-dlp.width);
        return root;
    }

    private void applyChrome() {
        int status = statusBarHeight();
        root.setPadding(0, status, 0, 0);
        Window w = getWindow();
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
    }

    private LinearLayout buildEditorPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(p.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(8), 0);
        pane.addView(bar, lp(-1, dp(52)));

        TextView menu = barButton("\u2630", 19);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerOpen) {
                    closeDrawer();
                } else {
                    openDrawer();
                }
            }
        });
        bar.addView(menu, lp(dp(40), dp(44)));

        titleView = new TextView(this);
        titleView.setTextSize(14.5f);
        titleView.setTextColor(p.text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleView.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));

        modeBtn = new TextView(this);
        modeBtn.setText("预览");
        modeBtn.setTextSize(13);
        modeBtn.setTextColor(p.accent);
        modeBtn.setGravity(Gravity.CENTER);
        modeBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (previewMode) {
                    showEdit();
                } else {
                    showPreview();
                }
            }
        });
        bar.addView(modeBtn, lp(-2, dp(44)));

        TextView more = barButton("\u22EF", 21);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        bar.addView(more, lp(dp(40), dp(44)));

        View divider = new View(this);
        divider.setBackgroundColor(p.line);
        pane.addView(divider, lp(-1, dp(1)));

        findBar = buildFindBar();
        findBar.setVisibility(View.GONE);
        pane.addView(findBar, lp(-1, -2));

        FrameLayout content = new FrameLayout(this);
        pane.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        editor = new EditText(this);
        editor.setBackgroundColor(p.editorBg);
        editor.setTextColor(p.editorText);
        editor.setHintTextColor(p.text3);
        editor.setHint("开始写 Markdown…");
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontPx);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(14), dp(12), dp(14), dp(40));
        editor.setLineSpacing(dp(3), 1f);
        editor.setHighlightColor(p.dark ? 0x556C8CFF : 0x332F6FEB);
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading) {
                    return;
                }
                dirty = true;
                updateStatus();
                ui.removeCallbacks(highlightTask);
                ui.postDelayed(highlightTask, 120);
                ui.removeCallbacks(saveTask);
                ui.postDelayed(saveTask, 1500);
            }
        });
        content.addView(editor, lp(-1, -1));

        preview = new WebView(this);
        preview.setBackgroundColor(p.dark ? 0xFF121418 : 0xFFFFFFFF);
        WebSettings s = preview.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        preview.setVisibility(View.GONE);
        preview.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("#") || url.startsWith("javascript:"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingPrint) {
                    pendingPrint = false;
                    doPrint();
                }
            }
        });
        content.addView(preview, lp(-1, -1));

        emptyView = buildEmpty();
        content.addView(emptyView, lp(-1, -1));

        toolbar = buildToolbar();
        pane.addView(toolbar, lp(-1, dp(46)));

        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(14), 0, dp(14), 0);
        statusBar.setBackgroundColor(p.bg);
        statusLeft = new TextView(this);
        statusLeft.setTextSize(11);
        statusLeft.setTextColor(p.text3);
        statusRight = new TextView(this);
        statusRight.setTextSize(11);
        statusRight.setTextColor(p.text3);
        statusRight.setGravity(Gravity.END);
        statusBar.addView(statusLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        statusBar.addView(statusRight);
        pane.addView(statusBar, lp(-1, dp(26)));
        return pane;
    }

    private LinearLayout buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(p.bg);
        box.setPadding(dp(30), 0, dp(30), dp(40));
        box.setVisibility(View.GONE);

        TextView icon = new TextView(this);
        icon.setText("M\u2193");
        icon.setTextSize(34);
        icon.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        icon.setTextColor(p.text3);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, lp(-1, -2));

        TextView t1 = new TextView(this);
        t1.setText("还没有打开文档");
        t1.setTextSize(15);
        t1.setTextColor(p.text2);
        t1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l1 = lp(-1, -2);
        l1.topMargin = dp(12);
        box.addView(t1, l1);

        TextView t2 = new TextView(this);
        t2.setText("从左上角选文件夹里的 .md，或打开单个文件");
        t2.setTextSize(12.5f);
        t2.setTextColor(p.text3);
        t2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l2 = lp(-1, -2);
        l2.topMargin = dp(6);
        box.addView(t2, l2);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lb = lp(-2, -2);
        lb.topMargin = dp(24);
        box.addView(btns, lb);

        TextView chooseDir = new TextView(this);
        chooseDir.setText("选择文件夹");
        chooseDir.setTextSize(13.5f);
        chooseDir.setTextColor(0xFFFFFFFF);
        chooseDir.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF4B7BFF, 0xFF7C5CFF});
        g.setCornerRadius(dp(11));
        chooseDir.setBackground(g);
        chooseDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
            }
        });
        btns.addView(chooseDir, new LinearLayout.LayoutParams(dp(132), dp(44)));

        TextView openFile = new TextView(this);
        openFile.setText("打开文件");
        openFile.setTextSize(13.5f);
        openFile.setTextColor(p.text);
        openFile.setGravity(Gravity.CENTER);
        GradientDrawable g2 = new GradientDrawable();
        g2.setColor(0x00000000);
        g2.setCornerRadius(dp(11));
        g2.setStroke(dp(1), p.line);
        openFile.setBackground(g2);
        openFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        LinearLayout.LayoutParams ofLp = new LinearLayout.LayoutParams(dp(132), dp(44));
        ofLp.leftMargin = dp(10);
        btns.addView(openFile, ofLp);

        return box;
    }

    private LinearLayout buildFindBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(p.surface);
        wrap.setPadding(dp(10), dp(8), dp(6), dp(8));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        findInput = smallInput("查找内容");
        row1.addView(findInput, new LinearLayout.LayoutParams(0, dp(38), 1f));
        row1.addView(smallAction("\u2191", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findNext(false);
            }
        }));
        row1.addView(smallAction("\u2193", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findNext(true);
            }
        }));
        row1.addView(smallAction("\u2715", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findBar.setVisibility(View.GONE);
            }
        }));
        wrap.addView(row1, lp(-1, -2));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = lp(-1, -2);
        row2Lp.topMargin = dp(6);

        replaceInput = smallInput("替换为");
        row2.addView(replaceInput, new LinearLayout.LayoutParams(0, dp(38), 1f));
        row2.addView(smallAction("替换", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceOne();
            }
        }));
        row2.addView(smallAction("全部", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceAll();
            }
        }));
        wrap.addView(row2, row2Lp);
        return wrap;
    }

    private EditText smallInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        et.setTextColor(p.text);
        et.setHintTextColor(p.text3);
        et.setBackgroundColor(0x00000000);
        et.setPadding(dp(6), 0, dp(6), 0);
        return et;
    }

    private TextView smallAction(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(p.accent);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout buildToolbar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(p.bg);
        View line = new View(this);
        line.setBackgroundColor(p.line);
        wrap.addView(line, lp(-1, dp(1)));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        wrap.addView(hsv, lp(-1, dp(45)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), 0, dp(9), 0);
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));

        row.addView(chipHeading());
        row.addView(chipWrap("B", "**", "**"));
        row.addView(chipWrap("I", "*", "*"));
        row.addView(chipWrap("S", "~~", "~~"));
        row.addView(chipPrefix("•", "- "));
        row.addView(chipPrefix("1.", "1. "));
        row.addView(chipWrap("`", "`", "`"));
        row.addView(chipAction("</>", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertFence();
            }
        }));
        row.addView(chipAction("▦", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertTable();
            }
        }));
        row.addView(chipAction("链接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertLink();
            }
        }));
        row.addView(chipPrefix("☑", "- [ ] "));
        row.addView(chipPrefix("引用", "> "));
        return wrap;
    }

    private View chipHeading() {
        TextView t = chipAction("H", null);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleHeading();
            }
        });
        return t;
    }

    private View chipWrap(String label, final String left, final String right) {
        TextView t = chipAction(label, null);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wrapSelection(left, right);
            }
        });
        return t;
    }

    private View chipPrefix(String label, final String prefix) {
        TextView t = chipAction(label, null);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLinePrefix(prefix);
            }
        });
        return t;
    }

    private TextView chipAction(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13.5f);
        t.setTextColor(p.text);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        GradientDrawable g = new GradientDrawable();
        g.setColor(p.chip);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        t.setPadding(dp(11), dp(7), dp(11), dp(7));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(33));
        lp.rightMargin = dp(7);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout buildDrawer() {
        DrawerPane box = new DrawerPane(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(p.surface);
        box.setPadding(dp(16), dp(18), dp(16), dp(14));

        drawerTitle = new TextView(this);
        drawerTitle.setTextSize(15);
        drawerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        drawerTitle.setTextColor(p.text);
        drawerTitle.setSingleLine(true);
        drawerTitle.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(drawerTitle, lp(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = lp(-1, -2);
        actLp.topMargin = dp(6);
        box.addView(actions, actLp);

        drawerHint = new TextView(this);
        drawerHint.setText("尚未选择工作区");
        drawerHint.setTextSize(11.5f);
        drawerHint.setTextColor(p.text3);
        drawerHint.setSingleLine(true);
        drawerHint.setEllipsize(TextUtils.TruncateAt.END);
        actions.addView(drawerHint, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView change = new TextView(this);
        change.setText("选择文件夹");
        change.setTextSize(12);
        change.setTextColor(p.accent);
        change.setPadding(dp(8), dp(4), dp(2), dp(4));
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
            }
        });
        actions.addView(change);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navLp = lp(-1, -2);
        navLp.topMargin = dp(14);
        box.addView(nav, navLp);

        upRow = new TextView(this);
        upRow.setText("\u2191");
        upRow.setTextSize(13);
        upRow.setTextColor(p.text2);
        upRow.setPadding(dp(2), dp(4), dp(10), dp(4));
        upRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        nav.addView(upRow);

        pathLabel = new TextView(this);
        pathLabel.setTextSize(11.5f);
        pathLabel.setTextColor(p.text3);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        nav.addView(pathLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        View line = new View(this);
        line.setBackgroundColor(p.line);
        LinearLayout.LayoutParams lineLp = lp(-1, dp(1));
        lineLp.topMargin = dp(8);
        box.addView(line, lineLp);

        ScrollView sv = new ScrollView(this);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(0, dp(6), 0, dp(6));
        sv.addView(fileList, new LinearLayout.LayoutParams(-1, -2));

        TextView newFile = new TextView(this);
        newFile.setText("+  新建 Markdown");
        newFile.setTextSize(13.5f);
        newFile.setTextColor(p.accent);
        newFile.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), p.line);
        newFile.setBackground(g);
        newFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFile();
            }
        });
        box.addView(newFile, lp(-1, dp(44)));
        return box;
    }

    private TextView barButton(String label, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(sizeSp);
        t.setTextColor(p.text2);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    // ---------------- 抽屉 ----------------

    private void openDrawer() {
        if (drawerOpen) {
            return;
        }
        drawerOpen = true;
        refreshFiles();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
        drawer.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(180).start();
        drawer.animate().translationX(-drawer.getWidth()).setDuration(220)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        scrim.setVisibility(View.GONE);
                    }
                }).start();
    }

    /**
     * 抽屉容器：向右滑出、向左滑回。
     * 用 onInterceptTouchEvent 判定方向——横向手势由抽屉自己接管（跟手位移 + 松手判定关闭），
     * 纵向手势照常交给内部 ScrollView，所以文件列表该滚还是能滚。
     */
    private final class DrawerPane extends LinearLayout {
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        DrawerPane(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
            } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && !dragging) {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    dragging = true;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (!drawerOpen) {
                return false;
            }
            int w = getWidth();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        float vx = ev.getX() - downX;
                        float vy = ev.getY() - downY;
                        if (Math.abs(vx) > slop && Math.abs(vx) > Math.abs(vy)) {
                            dragging = true;
                        } else {
                            return true;
                        }
                    }
                    // 只允许向左拖出屏幕，dx>0 时停在原位
                    float tx = Math.max(-w, Math.min(0f, ev.getX() - downX));
                    drawer.setTranslationX(tx);
                    scrim.setAlpha(Math.max(0f, 1f - (-tx / (float) Math.max(1, w))));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        return true;
                    }
                    dragging = false;
                    float dx = ev.getX() - downX;
                    long dt = Math.max(1L, ev.getEventTime() - ev.getDownTime());
                    float speed = dx / dt; // px/ms，负值向左
                    boolean far = dx < -w * 0.3f;                       // 拖过三成
                    boolean flick = dx < -w * 0.1f && speed < -0.5f;    // 或快速轻扫
                    if (far || flick) {
                        closeDrawer();
                    } else {
                        drawer.animate().translationX(0f).setDuration(160).start();
                        scrim.animate().alpha(1f).setDuration(160).start();
                    }
                    return true;
                }
                default:
                    break;
            }
            return super.onTouchEvent(ev);
        }
    }

    private void refreshFiles() {
        if (fileList == null) {
            return;
        }
        fileList.removeAllViews();
        if (ws == null) {
            drawerTitle.setText("墨读");
            drawerHint.setText("尚未选择工作区");
            pathLabel.setText("");
            upRow.setAlpha(0.35f);
            addHintRow("点「选择文件夹」授权一个目录，之后的 .md 都会列在这里");
            return;
        }
        drawerTitle.setText(ws.title);
        drawerHint.setText("工作区");
        pathLabel.setText(currentPath());
        upRow.setAlpha(dirStack.isEmpty() ? 0.35f : 1f);
        if (dirId == null) {
            dirId = ws.rootId;
        }
        docs.clear();
        docs.addAll(ws.list(getContentResolver(), dirId));
        if (docs.isEmpty()) {
            addHintRow("这个目录里还没有 Markdown 文件");
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            final Workspace.Doc d = docs.get(i);
            TextView row = new TextView(this);
            row.setText(d.dir ? d.name + "  \u203A" : d.name);
            row.setTextSize(14.5f);
            row.setTextColor(d.dir ? p.text2 : p.text);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(8), dp(13), dp(8), dp(13));
            applySelectable(row);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (d.dir) {
                        dirStack.add(dirId);
                        dirId = d.id;
                        refreshFiles();
                    } else {
                        openDoc(ws.uriFor(d.id), d.name);
                        closeDrawer();
                    }
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showFileActions(d);
                    return true;
                }
            });
            fileList.addView(row, lp(-1, -2));
        }
    }

    private void addHintRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(p.text3);
        t.setPadding(dp(8), dp(20), dp(8), dp(8));
        fileList.addView(t, lp(-1, -2));
    }

    private void applySelectable(View v) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    private String currentPath() {
        if (ws == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(ws.title);
        for (String id : dirStack) {
            for (Workspace.Doc d : docs) {
                if (d.id.equals(id)) {
                    sb.append(" / ").append(d.name);
                    break;
                }
            }
        }
        return sb.toString();
    }

    private void goUp() {
        if (dirStack.isEmpty()) {
            return;
        }
        dirId = dirStack.remove(dirStack.size() - 1);
        refreshFiles();
    }

    private void showFileActions(final Workspace.Doc d) {
        String[] items = new String[]{"打开", "重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(d.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openDoc(ws.uriFor(d.id), d.name);
                            closeDrawer();
                        } else if (which == 1) {
                            askRename(d);
                        } else {
                            askDelete(d);
                        }
                    }
                })
                .show();
    }

    private void askRename(final Workspace.Doc d) {
        final EditText input = new EditText(this);
        input.setText(d.name);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            return;
                        }
                        if (!Workspace.isMarkdown(name)) {
                            name = name + ".md";
                        }
                        try {
                            ws.rename(getContentResolver(), d, name);
                            if (d.id.equals(currentDocId())) {
                                docName = name;
                                titleView.setText(name);
                                rememberDoc();
                            }
                            refreshFiles();
                        } catch (Exception e) {
                            toast("重命名失败：" + e.getClass().getSimpleName());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askDelete(final Workspace.Doc d) {
        new AlertDialog.Builder(this)
                .setMessage("删除「" + d.name + "」？此操作不可撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            ws.delete(getContentResolver(), d);
                            if (d.id.equals(currentDocId())) {
                                closeDoc();
                            }
                            refreshFiles();
                        } catch (Exception e) {
                            toast("删除失败：" + e.getClass().getSimpleName());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void newFile() {
        if (ws == null) {
            pickTree();
            return;
        }
        String base = "未命名";
        String name = base + ".md";
        int n = 2;
        while (exists(name)) {
            name = base + " " + n + ".md";
            n++;
        }
        try {
            Workspace.Doc d = ws.create(getContentResolver(), dirId, name);
            refreshFiles();
            openDoc(ws.uriFor(d.id), d.name);
            closeDrawer();
        } catch (Exception e) {
            toast("新建失败：" + e.getClass().getSimpleName());
        }
    }

    private boolean exists(String name) {
        for (Workspace.Doc d : docs) {
            if (d.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String currentDocId() {
        if (docUri == null) {
            return "";
        }
        try {
            return android.provider.DocumentsContract.getDocumentId(docUri);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 文档读写 ----------------

    private boolean handleExternal(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null && intent.hasExtra(Intent.EXTRA_STREAM)) {
            Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) {
                uri = (Uri) o;
            }
        }
        if (uri == null) {
            return false;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        openDoc(uri, queryName(uri));
        intent.setData(null);
        intent.removeExtra(Intent.EXTRA_STREAM);
        return true;
    }

    private void openLastOrEmpty() {
        String last = prefs.getString("lastUri", null);
        if (last != null && last.length() > 0) {
            Uri u = Uri.parse(last);
            if (!docExists(u)) {
                // 上个文件被删了/授权失效：清掉记录，别打开一个空壳
                prefs.edit().remove("lastUri").remove("lastName").apply();
                showEmpty();
                return;
            }
            try {
                openDoc(u, prefs.getString("lastName", ""));
                if (docUri != null) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        showEmpty();
    }

    private boolean docExists(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private void openDoc(Uri uri, String name) {
        String text;
        try {
            text = Workspace.readText(getContentResolver(), uri);
        } catch (Exception e) {
            toast("无法打开：" + e.getClass().getSimpleName());
            return;
        }
        docUri = uri;
        docName = TextUtils.isEmpty(name) ? guessName(uri) : name;
        readOnly = false;
        titleView.setText(docName);
        loading = true;
        editor.setText(text);
        editor.setSelection(0);
        loading = false;
        dirty = false;
        statusBar.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        updateStatus();
        if (text.trim().length() == 0) {
            // 空文档进预览是一片空白，直接给编辑器
            showEdit();
        } else {
            // 默认先进预览：打开就是排好版的样子
            showPreview();
        }
        modeBtn.setAlpha(1f);
        statusRight.setText("已打开");
        rememberDoc();
    }

    private void rememberDoc() {
        SharedPreferences.Editor ed = prefs.edit();
        if (docUri == null) {
            ed.remove("lastUri");
            ed.remove("lastName");
        } else {
            ed.putString("lastUri", docUri.toString());
            ed.putString("lastName", docName);
        }
        if (dirId != null) {
            ed.putString("dirId", dirId);
        }
        ed.apply();
    }

    private void closeDoc() {
        saveNow(true);
        docUri = null;
        docName = "";
        dirty = false;
        readOnly = false;
        loading = true;
        editor.setText("");
        loading = false;
        showEmpty();
        prefs.edit().remove("lastUri").remove("lastName").apply();
    }

    private void showEmpty() {
        emptyView.setVisibility(View.VISIBLE);
        editor.setVisibility(View.GONE);
        preview.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        statusBar.setVisibility(View.GONE);
        modeBtn.setText("预览");
        modeBtn.setAlpha(0.4f);
        titleView.setText("");
        previewMode = false;
    }

    private String guessName(Uri uri) {
        String n = queryName(uri);
        if (!TextUtils.isEmpty(n)) {
            return n;
        }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名.md" : last;
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private void saveNow(final boolean manual) {
        if (docUri == null) {
            return;
        }
        if (readOnly) {
            statusRight.setText("只读");
            return;
        }
        final String text = editor.getText().toString();
        final Uri uri = docUri;
        statusRight.setText("保存中…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Workspace.writeText(getContentResolver(), uri, text);
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            dirty = !editor.getText().toString().equals(text);
                            statusRight.setText(dirty ? "编辑中…" : "已保存");
                        } else if (error.contains("Security") || error.contains("Permission")) {
                            readOnly = true;
                            statusRight.setText("只读");
                            if (manual) {
                                toast("没有写入权限，可用「另存为副本」导出");
                            }
                        } else {
                            statusRight.setText("保存失败");
                            toast("保存失败：" + error);
                        }
                    }
                });
            }
        });
    }

    private void updateStatus() {
        if (statusLeft == null) {
            return;
        }
        String s = editor.getText().toString();
        int chars = 0;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                chars++;
            }
            if (c == '\n') {
                lines++;
            }
        }
        statusLeft.setText(chars + " 字 · " + lines + " 行");
        if (!readOnly) {
            statusRight.setText("编辑中…");
        }
    }

    // ---------------- 预览 ----------------

    private void showPreview() {
        if (docUri == null) {
            return;
        }
        ui.removeCallbacks(highlightTask);
        String html = renderer.render(editor.getText().toString(), p.dark ? "dark" : "light", fontPx);
        preview.setBackgroundColor(p.dark ? 0xFF121418 : 0xFFFFFFFF);
        preview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        preview.setVisibility(View.VISIBLE);
        editor.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        statusBar.setVisibility(View.VISIBLE);
        previewMode = true;
        modeBtn.setText("编辑");
        modeBtn.setAlpha(1f);
    }

    private void showEdit() {
        previewMode = false;
        preview.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        modeBtn.setText("预览");
        ui.post(new Runnable() {
            @Override
            public void run() {
                SyntaxHighlighter.apply(editor, p);
            }
        });
    }

    private void doPrint() {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String job = Workspace.baseName(docName);
            if (job.length() == 0) {
                job = "墨读文档";
            }
            PrintDocumentAdapter adapter = preview.createPrintDocumentAdapter(job);
            pm.print(job, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            toast("导出 PDF 失败：" + e.getClass().getSimpleName());
        }
    }

    // ---------------- 菜单 ----------------

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "大纲");
        pm.getMenu().add(0, 2, 1, "查找替换");
        pm.getMenu().add(0, 3, 2, "导出 PDF");
        pm.getMenu().add(0, 4, 3, "导出 HTML");
        pm.getMenu().add(0, 5, 4, "另存为副本");
        pm.getMenu().add(0, 6, 5, "重命名");
        pm.getMenu().add(0, 7, 6, "字号 " + fontPx);
        pm.getMenu().add(0, 8, 7, p.dark ? "浅色主题" : "深色主题");
        pm.getMenu().add(0, 9, 8, "关闭文档");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        showOutline();
                        return true;
                    case 2:
                        findBar.setVisibility(View.VISIBLE);
                        findInput.requestFocus();
                        return true;
                    case 3:
                        pendingPrint = true;
                        showPreview();
                        return true;
                    case 4:
                        exportHtml();
                        return true;
                    case 5:
                        saveCopy();
                        return true;
                    case 6:
                        renameCurrent();
                        return true;
                    case 7:
                        cycleFont();
                        return true;
                    case 8:
                        toggleTheme();
                        return true;
                    case 9:
                        closeDoc();
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    private void showOutline() {
        if (docUri == null) {
            return;
        }
        renderer.render(editor.getText().toString(), p.dark ? "dark" : "light", fontPx);
        final java.util.List<MarkdownRenderer.Heading> hs = renderer.getHeadings();
        if (hs.isEmpty()) {
            toast("这份文档没有标题");
            return;
        }
        String[] names = new String[hs.size()];
        for (int i = 0; i < hs.size(); i++) {
            MarkdownRenderer.Heading h = hs.get(i);
            StringBuilder pad = new StringBuilder();
            for (int k = 1; k < h.level; k++) {
                pad.append("   ");
            }
            names[i] = pad + h.text;
        }
        new AlertDialog.Builder(this)
                .setTitle("大纲")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        jumpToHeading(hs.get(which));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void jumpToHeading(MarkdownRenderer.Heading h) {
        if (previewMode) {
            preview.evaluateJavascript("goTo('" + h.id + "')", null);
            return;
        }
        String needle = h.text;
        if (needle.length() == 0) {
            return;
        }
        int idx = editor.getText().toString().indexOf(needle);
        if (idx >= 0) {
            editor.requestFocus();
            editor.setSelection(Math.min(idx + needle.length(), editor.length()));
            scrollEditorTo(idx);
        }
    }

    private void scrollEditorTo(final int offset) {
        editor.post(new Runnable() {
            @Override
            public void run() {
                android.text.Layout layout = editor.getLayout();
                if (layout == null) {
                    return;
                }
                int line = layout.getLineForOffset(offset);
                int y = layout.getLineTop(line);
                editor.scrollTo(0, Math.max(0, y - editor.getHeight() / 3));
            }
        });
    }

    private void cycleFont() {
        int[] sizes = {13, 14, 15, 16, 18, 20, 22};
        int idx = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] <= fontPx) {
                idx = i;
            }
        }
        fontPx = sizes[(idx + 1) % sizes.length];
        prefs.edit().putInt("fontPx", fontPx).apply();
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontPx);
        toast("字号 " + fontPx);
    }

    private void toggleTheme() {
        prefs.edit().putBoolean("dark", !p.dark).apply();
        rememberDoc();
        recreate();
    }

    private void renameCurrent() {
        if (docUri == null || ws == null) {
            toast("只能重命名工作区内的文件");
            return;
        }
        String id = currentDocId();
        for (Workspace.Doc d : docs) {
            if (d.id.equals(id)) {
                askRename(d);
                return;
            }
        }
        toast("当前文件不在打开的目录中");
    }

    private void saveCopy() {
        if (docUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/markdown");
        i.putExtra(Intent.EXTRA_TITLE, Workspace.baseName(docName) + " 副本.md");
        try {
            startActivityForResult(i, REQ_SAVE_COPY);
        } catch (ActivityNotFoundException e) {
            toast("没有可用的文件保存器");
        }
    }

    private void exportHtml() {
        if (docUri == null) {
            return;
        }
        pendingHtml = renderer.render(editor.getText().toString(), p.dark ? "dark" : "light", fontPx);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/html");
        i.putExtra(Intent.EXTRA_TITLE, Workspace.baseName(docName) + ".html");
        try {
            startActivityForResult(i, REQ_EXPORT_HTML);
        } catch (ActivityNotFoundException e) {
            toast("没有可用的文件保存器");
        }
    }

    // ---------------- 选择器 ----------------

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            toast("系统不支持选择文件夹");
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            toast("没有找到文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQ_TREE) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            ws = Workspace.open(this, uri);
            dirId = ws.rootId;
            dirStack.clear();
            refreshFiles();
            if (docUri == null) {
                openDrawer();
            }
        } else if (requestCode == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            openDoc(uri, queryName(uri));
        } else if (requestCode == REQ_EXPORT_HTML && pendingHtml != null) {
            writeTo(uri, pendingHtml, "HTML 已导出");
            pendingHtml = null;
        } else if (requestCode == REQ_SAVE_COPY) {
            writeTo(uri, editor.getText().toString(), "副本已保存");
        }
    }

    private void writeTo(final Uri uri, final String content, final String okMsg) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Workspace.writeText(getContentResolver(), uri, content);
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(error == null ? okMsg : "写入失败：" + error);
                    }
                });
            }
        });
    }

    // ---------------- 查找替换 ----------------

    private void findNext(boolean forward) {
        String q = findInput.getText().toString();
        if (q.length() == 0) {
            return;
        }
        String t = editor.getText().toString();
        int idx;
        if (forward) {
            idx = t.indexOf(q, Math.max(0, editor.getSelectionEnd()));
            if (idx < 0) {
                idx = t.indexOf(q);
            }
        } else {
            idx = t.lastIndexOf(q, Math.max(0, editor.getSelectionStart() - 1));
            if (idx < 0) {
                idx = t.lastIndexOf(q);
            }
        }
        if (idx < 0) {
            toast("没有找到「" + q + "」");
            return;
        }
        editor.requestFocus();
        editor.setSelection(idx, idx + q.length());
        scrollEditorTo(idx);
    }

    private void replaceOne() {
        String q = findInput.getText().toString();
        String r = replaceInput.getText().toString();
        if (q.length() == 0) {
            return;
        }
        int s = Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
        int e = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
        Editable ed = editor.getText();
        String sel = ed.subSequence(Math.max(s, 0), Math.max(e, 0)).toString();
        if (sel.equals(q)) {
            ed.replace(s, e, r);
        }
        findNext(true);
    }

    private void replaceAll() {
        String q = findInput.getText().toString();
        String r = replaceInput.getText().toString();
        if (q.length() == 0) {
            return;
        }
        Editable ed = editor.getText();
        String t = ed.toString();
        int count = 0;
        int idx = t.length();
        while (idx > 0) {
            idx = t.lastIndexOf(q, idx - 1);
            if (idx < 0) {
                break;
            }
            ed.replace(idx, idx + q.length(), r);
            count++;
            if (idx == 0) {
                break;
            }
        }
        toast(count == 0 ? "没有找到匹配内容" : "已替换 " + count + " 处");
    }

    // ---------------- 插入 ----------------

    private void wrapSelection(String left, String right) {
        editor.requestFocus();
        int s = Math.max(0, Math.min(editor.getSelectionStart(), editor.getSelectionEnd()));
        int e = Math.max(0, Math.max(editor.getSelectionStart(), editor.getSelectionEnd()));
        Editable ed = editor.getText();
        e = Math.min(e, ed.length());
        s = Math.min(s, e);
        String sel = ed.subSequence(s, e).toString();
        ed.replace(s, e, left + sel + right);
        editor.setSelection(s + left.length(), s + left.length() + sel.length());
    }

    private void toggleLinePrefix(String prefix) {
        editor.requestFocus();
        int pos = Math.max(editor.getSelectionStart(), 0);
        Editable ed = editor.getText();
        int lineStart = pos;
        while (lineStart > 0 && ed.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int end = Math.min(lineStart + prefix.length(), ed.length());
        if (ed.subSequence(lineStart, end).toString().equals(prefix)) {
            ed.delete(lineStart, end);
            editor.setSelection(Math.max(lineStart, pos - prefix.length()));
        } else {
            ed.insert(lineStart, prefix);
            editor.setSelection(pos + prefix.length());
        }
    }

    private void cycleHeading() {
        editor.requestFocus();
        int pos = Math.max(editor.getSelectionStart(), 0);
        Editable ed = editor.getText();
        int lineStart = pos;
        while (lineStart > 0 && ed.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int level = 0;
        while (lineStart + level < ed.length() && ed.charAt(lineStart + level) == '#') {
            level++;
        }
        int removed = 0;
        if (level > 0) {
            int end = lineStart + level;
            if (end < ed.length() && ed.charAt(end) == ' ') {
                end++;
            }
            removed = end - lineStart;
            ed.delete(lineStart, end);
        }
        int next = level >= 3 ? 0 : level + 1;
        if (next > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < next; i++) {
                sb.append('#');
            }
            sb.append(' ');
            ed.insert(lineStart, sb.toString());
            editor.setSelection(Math.max(0, pos - removed + sb.length()));
        } else {
            editor.setSelection(Math.max(0, pos - removed));
        }
    }

    private void insertBlock(String text, int caretOffset) {
        editor.requestFocus();
        int pos = Math.max(editor.getSelectionStart(), 0);
        Editable ed = editor.getText();
        String ins = text;
        if (pos > 0 && ed.charAt(pos - 1) != '\n') {
            ins = "\n" + ins;
            caretOffset++;
        }
        ed.insert(pos, ins);
        editor.setSelection(Math.min(pos + caretOffset, ed.length()));
    }

    private void insertFence() {
        insertBlock("```\n\n```\n", 4);
    }

    private void insertTable() {
        insertBlock("| 列 1 | 列 2 |\n| --- | --- |\n| 内容 | 内容 |\n", 0);
    }

    private void insertLink() {
        editor.requestFocus();
        int s = Math.max(0, Math.min(editor.getSelectionStart(), editor.getSelectionEnd()));
        int e = Math.max(0, Math.max(editor.getSelectionStart(), editor.getSelectionEnd()));
        Editable ed = editor.getText();
        e = Math.min(e, ed.length());
        s = Math.min(s, e);
        String sel = ed.subSequence(s, e).toString();
        String label = sel.length() > 0 ? sel : "链接文字";
        String ins = "[" + label + "](https://)";
        ed.replace(s, e, ins);
        if (sel.length() == 0) {
            editor.setSelection(s + 1, s + 1 + label.length());
        } else {
            editor.setSelection(s + ins.length() - 1);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
