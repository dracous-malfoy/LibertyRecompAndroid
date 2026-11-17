package com.libertyrecomp;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TouchEditorActivity extends Activity {
    private static final class Entry {
        final String id, label;
        final int color, arrow;
        final boolean stick;
        float dx = 0f, dy = 0f, scale = 1f, opacity = 1f;
        boolean hidden = false;

        Entry(String id, String label, int color, boolean stick, int arrow) {
            this.id = id;
            this.label = label;
            this.color = color;
            this.stick = stick;
            this.arrow = arrow;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private File layoutFile;
    private boolean grid = true, snap = true, dirty = false, collapsed = false;
    private int selectedIndex = -1;
    private float panelX = 0.32f, panelY = 0.04f;
    private FrameLayout rootView;
    private EditorCanvas canvasView;
    private LinearLayout panel, selectionTools;
    private ScrollView toolsScroll;
    private TextView selectedText, sizeValueText, opacityValueText;
    private SeekBar sizeBar, opacityBar;
    private CheckBox hiddenBox;
    private Button collapseBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(a);
        }
        hideSystemBars();
        File ext = getExternalFilesDir(null);
        layoutFile = new File(new File(ext != null ? ext : getFilesDir(), "LibertyRecomp"),
                "xbox_touch_layout.txt");
        initEntries();
        loadLayout();
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(0xFF0A0D12);
        canvasView = new EditorCanvas();
        rootView.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));
        buildPanel();
        rootView.addView(panel, new FrameLayout.LayoutParams(-2, -2));
        setContentView(rootView);
        rootView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.min(Math.max(1, rootView.getWidth() - dp(16)),
                    Math.max(dp(240), Math.min(dp(330), Math.round(rootView.getWidth() * 0.34f))));
            if (panel.getLayoutParams().width != w) {
                panel.getLayoutParams().width = w;
                panel.requestLayout();
            }
            positionPanel();
            canvasView.invalidate();
        });
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionPanel());
        updateSelectionUi();
    }

    private void initEntries() {
        entries.clear();
        entries.add(new Entry("left_stick", "L Stick", 0xFFE1E1E1, true, -1));
        entries.add(new Entry("right_stick", "R Stick", 0xFFE1E1E1, true, -1));
        entries.add(new Entry("dpad_up", "D-Pad Up", 0xFFE6E6E6, false, 0));
        entries.add(new Entry("dpad_down", "D-Pad Down", 0xFFE6E6E6, false, 1));
        entries.add(new Entry("dpad_left", "D-Pad Left", 0xFFE6E6E6, false, 2));
        entries.add(new Entry("dpad_right", "D-Pad Right", 0xFFE6E6E6, false, 3));
        entries.add(new Entry("lt", "LT", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("lb", "LB", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("l3", "L3", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("a", "A", 0xFF6ECD50, false, -1));
        entries.add(new Entry("b", "B", 0xFFEB4B46, false, -1));
        entries.add(new Entry("x", "X", 0xFF4B91F0, false, -1));
        entries.add(new Entry("y", "Y", 0xFFF5CD32, false, -1));
        entries.add(new Entry("rt", "RT", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("rb", "RB", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("r3", "R3", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("back", "BACK", 0xFFE6E6E6, false, -1));
        entries.add(new Entry("start", "START", 0xFFE6E6E6, false, -1));
    }

    private void loadLayout() {
        if (!layoutFile.isFile()) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(layoutFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) continue;
                for (Entry e : entries) {
                    if (e.id.equals(p[0])) {
                        e.dx = clamp(parseFloat(p[1], 0f), -2000f, 2000f);
                        e.dy = clamp(parseFloat(p[2], 0f), -800f, 800f);
                        e.scale = clamp(parseFloat(p[3], 1f), 0.35f, 3f);
                        e.hidden = !"0".equals(p[4]);
                        if (p.length >= 6) e.opacity = clamp(parseFloat(p[5], 1f), 0.15f, 1.5f);
                        break;
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private boolean saveLayout() {
        try {
            File p = layoutFile.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) {
                throw new IOException("Cannot create folder");
            }
            StringBuilder out = new StringBuilder();
            for (Entry e : entries) {
                out.append(String.format(Locale.ROOT, "%s %.2f %.2f %.3f %d %.2f\n",
                        e.id, e.dx, e.dy, e.scale, e.hidden ? 1 : 0, e.opacity));
            }
            try (FileOutputStream fos = new FileOutputStream(layoutFile)) {
                fos.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            dirty = false;
            return true;
        } catch (IOException err) {
            Toast.makeText(this, "Save failed: " + err.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE181A20);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x44FFFFFF);
        panel.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(4), dp(6), dp(4));
        header.setBackgroundColor(0xFF252932);
        TextView title = new TextView(this);
        title.setText("Xbox Editor (drag)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        enablePanelDrag(title);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1));

        collapseBtn = makeBtn(collapsed ? "+" : "−", 0xFF333845);
        collapseBtn.setOnClickListener(v -> {
            collapsed = !collapsed;
            toolsScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapseBtn.setText(collapsed ? "+" : "−");
        });
        header.addView(collapseBtn, new LinearLayout.LayoutParams(dp(36), dp(32)));

        Button saveTop = makeBtn("Save", 0xFF2E7D32);
        saveTop.setOnClickListener(v -> {
            if (saveLayout()) {
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(58), dp(32));
        slp.leftMargin = dp(4);
        header.addView(saveTop, slp);
        panel.addView(header);

        toolsScroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                int avail = rootView.getHeight() > 0 ? rootView.getHeight()
                        : getResources().getDisplayMetrics().heightPixels;
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.max(dp(80), avail - dp(80)),
                        MeasureSpec.AT_MOST));
            }
        };
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(8), dp(10), dp(10));

        TextView hint = new TextView(this);
        hint.setText("Drag button to move. Tap to resize/hide.");
        hint.setTextColor(0xFFAAAAAA);
        hint.setTextSize(11);
        body.addView(hint);

        selectionTools = new LinearLayout(this);
        selectionTools.setOrientation(LinearLayout.VERTICAL);
        selectionTools.setPadding(0, dp(6), 0, dp(4));

        selectedText = new TextView(this);
        selectedText.setTextColor(0xFFFFD54F);
        selectedText.setTextSize(13);
        selectedText.setTypeface(Typeface.DEFAULT_BOLD);
        selectionTools.addView(selectedText);

        LinearLayout nudgeRow = new LinearLayout(this);
        nudgeRow.setPadding(0, dp(4), 0, dp(4));
        String[] dirs = {"←", "→", "↑", "↓"};
        for (int i = 0; i < 4; i++) {
            final int d = i;
            Button nb = makeBtn(dirs[i], 0xFF2E3440);
            nb.setOnClickListener(v -> {
                if (selectedIndex < 0) return;
                Entry e = entries.get(selectedIndex);
                if (d == 0) e.dx = clamp(e.dx - 5f, -2000f, 2000f);
                else if (d == 1) e.dx = clamp(e.dx + 5f, -2000f, 2000f);
                else if (d == 2) e.dy = clamp(e.dy - 5f, -800f, 800f);
                else e.dy = clamp(e.dy + 5f, -800f, 800f);
                dirty = true;
                canvasView.invalidate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (i > 0) lp.leftMargin = dp(4);
            nudgeRow.addView(nb, lp);
        }
        selectionTools.addView(nudgeRow);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeValueText = new TextView(this);
        sizeValueText.setTextColor(Color.WHITE);
        sizeValueText.setTextSize(12);
        sizeRow.addView(sizeValueText, new LinearLayout.LayoutParams(dp(74), -2));
        sizeBar = new SeekBar(this);
        sizeBar.setMax(265);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (u && selectedIndex >= 0) {
                    Entry e = entries.get(selectedIndex);
                    e.scale = 0.35f + p * 0.01f;
                    dirty = true;
                    sizeValueText.setText(String.format(Locale.ROOT, "Size %.2f×", e.scale));
                    canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        sizeRow.addView(sizeBar, new LinearLayout.LayoutParams(0, -2, 1));
        selectionTools.addView(sizeRow);

        LinearLayout opRow = new LinearLayout(this);
        opRow.setGravity(Gravity.CENTER_VERTICAL);
        opacityValueText = new TextView(this);
        opacityValueText.setTextColor(Color.WHITE);
        opacityValueText.setTextSize(12);
        opRow.addView(opacityValueText, new LinearLayout.LayoutParams(dp(74), -2));
        opacityBar = new SeekBar(this);
        opacityBar.setMax(135);
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (u && selectedIndex >= 0) {
                    Entry e = entries.get(selectedIndex);
                    e.opacity = 0.15f + p * 0.01f;
                    dirty = true;
                    opacityValueText.setText(String.format(Locale.ROOT, "Alpha %d%%",
                            Math.round(e.opacity * 100f)));
                    canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        opRow.addView(opacityBar, new LinearLayout.LayoutParams(0, -2, 1));
        selectionTools.addView(opRow);

        hiddenBox = new CheckBox(this);
        hiddenBox.setText("Hide button");
        hiddenBox.setTextColor(Color.WHITE);
        hiddenBox.setOnCheckedChangeListener((b, on) -> {
            if (selectedIndex >= 0 && entries.get(selectedIndex).hidden != on) {
                entries.get(selectedIndex).hidden = on;
                dirty = true;
                canvasView.invalidate();
            }
        });
        selectionTools.addView(hiddenBox);
        body.addView(selectionTools);

        LinearLayout toggles = new LinearLayout(this);
        CheckBox gridBox = new CheckBox(this);
        gridBox.setText("Grid");
        gridBox.setTextColor(Color.WHITE);
        gridBox.setChecked(grid);
        gridBox.setOnCheckedChangeListener((b, on) -> {
            grid = on;
            canvasView.invalidate();
        });
        CheckBox snapBox = new CheckBox(this);
        snapBox.setText("Snap");
        snapBox.setTextColor(Color.WHITE);
        snapBox.setChecked(snap);
        snapBox.setOnCheckedChangeListener((b, on) -> snap = on);
        toggles.addView(gridBox);
        toggles.addView(snapBox);
        body.addView(toggles);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setPadding(0, dp(6), 0, 0);
        Button resetBtn = makeBtn("Reset All", 0xFF8E2424);
        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset Xbox layout?")
                .setMessage("Restore all buttons to default?")
                .setPositiveButton("Reset", (d, w) -> {
                    for (Entry e : entries) {
                        e.dx = 0f; e.dy = 0f; e.scale = 1f; e.opacity = 1f; e.hidden = false;
                    }
                    dirty = true;
                    updateSelectionUi();
                    canvasView.invalidate();
                })
                .setNegativeButton("Cancel", null)
                .show());
        Button closeBtn = makeBtn("Cancel", 0xFF3A404D);
        closeBtn.setOnClickListener(v -> onBackPressed());
        bottomRow.addView(resetBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(36), 1);
        clp.leftMargin = dp(6);
        bottomRow.addView(closeBtn, clp);
        body.addView(bottomRow);

        toolsScroll.addView(body);
        toolsScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        panel.addView(toolsScroll);
    }

    private Button makeBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        b.setBackground(d);
        return b;
    }

    private void updateSelectionUi() {
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            selectionTools.setVisibility(View.GONE);
            return;
        }
        Entry e = entries.get(selectedIndex);
        selectionTools.setVisibility(View.VISIBLE);
        selectedText.setText("Selected: " + e.label);
        sizeValueText.setText(String.format(Locale.ROOT, "Size %.2f×", e.scale));
        sizeBar.setProgress(Math.round((e.scale - 0.35f) * 100f));
        opacityValueText.setText(String.format(Locale.ROOT, "Alpha %d%%",
                Math.round(e.opacity * 100f)));
        opacityBar.setProgress(Math.round((e.opacity - 0.15f) * 100f));
        hiddenBox.setChecked(e.hidden);
    }

    private void positionPanel() {
        if (rootView == null || panel == null || rootView.getWidth() <= 0) return;
        panel.setX(clamp(rootView.getWidth() * panelX, dp(6),
                Math.max(dp(6), rootView.getWidth() - panel.getWidth() - dp(6))));
        panel.setY(clamp(rootView.getHeight() * panelY, dp(6),
                Math.max(dp(6), rootView.getHeight() - panel.getHeight() - dp(6))));
    }

    private void enablePanelDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = panel.getX(); startY = panel.getY();
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE
                        && rootView.getWidth() > 0 && rootView.getHeight() > 0) {
                    panelX = (startX + e.getRawX() - downX) / rootView.getWidth();
                    panelY = (startY + e.getRawY() - downY) / rootView.getHeight();
                    positionPanel();
                }
                return true;
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!dirty) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Save your Xbox button layout before closing?")
                .setPositiveButton("Save", (d, w) -> {
                    if (saveLayout()) {
                        setResult(RESULT_OK);
                        finish();
                    }
                })
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton("Keep editing", null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(5894);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float v, float min, float max) {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
    }

    private static float parseFloat(String s, float fb) {
        try {
            float v = Float.parseFloat(s);
            return Float.isFinite(v) ? v : fb;
        } catch (Exception e) {
            return fb;
        }
    }

    private final class EditorCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private float lastX, lastY;
        private int activePointer = -1, draggingIndex = -1;
        private boolean moved = false;

        EditorCanvas() {
            super(TouchEditorActivity.this);
        }

        private float[] geom(int index) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return new float[]{0, 0, 20};
            float lx = 0.12f * w, dx = lx + 0.26f * h, dy = 0.86f * h, arm = 0.078f * h, dr = 0.042f * h;
            float br = 0.048f * h, small = 0.036f * h, fx = w - 0.52f * h, fy = 0.80f * h, fs = 0.097f * h, fr = 0.052f * h;
            float rx = w - 0.17f * h, sr = 0.036f * h, bx, by, baseR;
            switch (index) {
                case 0: bx = lx; by = 0.72f * h; baseR = 0.105f * h; break;
                case 1: bx = w * 0.75f; by = 0.60f * h; baseR = 0.095f * h; break;
                case 2: bx = dx; by = dy - arm; baseR = dr; break;
                case 3: bx = dx; by = dy + arm; baseR = dr; break;
                case 4: bx = dx - arm; by = dy; baseR = dr; break;
                case 5: bx = dx + arm; by = dy; baseR = dr; break;
                case 6: bx = lx; by = 0.355f * h; baseR = br; break;
                case 7: bx = lx; by = 0.475f * h; baseR = br; break;
                case 8: bx = lx + 0.115f * h; by = 0.475f * h; baseR = small; break;
                case 9: bx = fx; by = fy + fs; baseR = fr; break;
                case 10: bx = fx + fs; by = fy; baseR = fr; break;
                case 11: bx = fx - fs; by = fy; baseR = fr; break;
                case 12: bx = fx; by = fy - fs; baseR = fr; break;
                case 13: bx = rx; by = 0.47f * h; baseR = br; break;
                case 14: bx = rx; by = 0.59f * h; baseR = br; break;
                case 15: bx = rx - 0.115f * h; by = 0.59f * h; baseR = small; break;
                case 16: bx = 0.44f * w; by = 0.075f * h; baseR = sr; break;
                default: bx = 0.56f * w; by = 0.075f * h; baseR = sr; break;
            }
            Entry e = entries.get(index);
            float unit = h / 737f;
            float r = Math.min(Math.min(w, h) * 0.45f, Math.max(4f, baseR * e.scale));
            return new float[]{clamp(bx + e.dx * unit, r, w - r),
                    clamp(by + e.dy * unit, r, h - r), r};
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawColor(0xFF0A0D12);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float unit = h / 737f;
            if (grid) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(0x22557799);
                float step = 20f * unit;
                for (float x = 0; x <= w; x += step) c.drawLine(x, 0, x, h, paint);
                for (float y = 0; y <= h; y += step) c.drawLine(0, y, w, y, paint);
                paint.setColor(0x557799BB);
                c.drawLine(w * 0.5f, 0, w * 0.5f, h, paint);
                c.drawLine(0, h * 0.5f, w, h * 0.5f, paint);
            }
            paint.setTypeface(Typeface.MONOSPACE);
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                float[] g = geom(i);
                float cx = g[0], cy = g[1], r = g[2];
                float alphaMul = e.hidden ? 0.2f : e.opacity;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(scaleAlpha(0x375A5A5A, alphaMul));
                c.drawCircle(cx, cy, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, r * 0.025f));
                paint.setColor(scaleAlpha(0x69D7D7D7, alphaMul));
                c.drawCircle(cx, cy, r, paint);
                if (e.stick) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(scaleAlpha(0x82E1E1E1, alphaMul));
                    c.drawCircle(cx, cy, r * 0.34f, paint);
                } else if (e.arrow >= 0) {
                    float t = r * 0.42f;
                    arrowPath.reset();
                    if (e.arrow == 0) {
                        arrowPath.moveTo(cx, cy - t);
                        arrowPath.lineTo(cx - t, cy + t * 0.6f);
                        arrowPath.lineTo(cx + t, cy + t * 0.6f);
                    } else if (e.arrow == 1) {
                        arrowPath.moveTo(cx, cy + t);
                        arrowPath.lineTo(cx + t, cy - t * 0.6f);
                        arrowPath.lineTo(cx - t, cy - t * 0.6f);
                    } else if (e.arrow == 2) {
                        arrowPath.moveTo(cx - t, cy);
                        arrowPath.lineTo(cx + t * 0.6f, cy + t);
                        arrowPath.lineTo(cx + t * 0.6f, cy - t);
                    } else {
                        arrowPath.moveTo(cx + t, cy);
                        arrowPath.lineTo(cx - t * 0.6f, cy - t);
                        arrowPath.lineTo(cx - t * 0.6f, cy + t);
                    }
                    arrowPath.close();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(scaleAlpha(0x96E6E6E6, alphaMul));
                    c.drawPath(arrowPath, paint);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(scaleAlpha(e.color, alphaMul));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(Math.max(10f, e.label.length() == 1 ? r * 0.95f : r * 0.55f));
                    c.drawText(e.label, cx, cy - (paint.ascent() + paint.descent()) * 0.5f, paint);
                }
                if (i == selectedIndex || e.hidden) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(i == selectedIndex ? dp(2) : dp(1));
                    paint.setColor(e.hidden ? 0xFFFF5252 : 0xFFFFD54F);
                    c.drawCircle(cx, cy, r + dp(2), paint);
                }
            }
        }

        private int scaleAlpha(int argb, float factor) {
            int scaled = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * factor)));
            return (argb & 0x00FFFFFF) | (scaled << 24);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float unit = getHeight() > 0 ? getHeight() / 737f : 1f;
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0);
                float px = event.getX(), py = event.getY(), best = Float.MAX_VALUE;
                draggingIndex = -1;
                moved = false;
                for (int i = 0; i < entries.size(); i++) {
                    float[] g = geom(i);
                    float score = (float) Math.hypot(px - g[0], py - g[1]) / Math.max(g[2] * 1.25f, dp(22));
                    if (score <= 1f && score < best) {
                        best = score;
                        draggingIndex = i;
                    }
                }
                if (draggingIndex >= 0) {
                    selectedIndex = draggingIndex;
                    updateSelectionUi();
                }
                lastX = px;
                lastY = py;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && draggingIndex >= 0 && unit > 0) {
                int idx = event.findPointerIndex(activePointer);
                if (idx < 0) return true;
                float px = event.getX(idx), py = event.getY(idx);
                Entry e = entries.get(draggingIndex);
                float ndx = clamp(e.dx + (px - lastX) / unit, -2000f, 2000f);
                float ndy = clamp(e.dy + (py - lastY) / unit, -800f, 800f);
                if (ndx != e.dx || ndy != e.dy) {
                    moved = true;
                    dirty = true;
                    e.dx = ndx;
                    e.dy = ndy;
                }
                lastX = px;
                lastY = py;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP && draggingIndex >= 0 && snap && moved && unit > 0) {
                    float step = 20f * unit;
                    float[] g = geom(draggingIndex);
                    Entry e = entries.get(draggingIndex);
                    e.dx = clamp(e.dx + (Math.round(g[0] / step) * step - g[0]) / unit, -2000f, 2000f);
                    e.dy = clamp(e.dy + (Math.round(g[1] / step) * step - g[1]) / unit, -800f, 800f);
                }
                draggingIndex = -1;
                activePointer = -1;
                invalidate();
                return true;
            }
            return true;
        }
    }
}