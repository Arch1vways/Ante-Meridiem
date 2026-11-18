package dev.antemeridiem;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Settings: which fonts the editor offers, user fonts, module status. */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final float WINDOW_TITLE_SP = 18f;
    /** Analog dial size slider step, percent. */
    private static final int SCALE_STEP = 5;
    private static final String[] FONT_TYPES = {
            "font/ttf", "font/otf", "font/sfnt", "font/collection", "application/x-font-ttf", "application/x-font-otf",
            "application/font-sfnt", "application/vnd.ms-opentype", "application/x-font-truetype",
            "application/octet-stream",
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Typeface> previews = new HashMap<>();
    private int previewGeneration;
    private String shownCustoms;
    private boolean draggingSlider, rebuildPending;
    private Boolean statusSeen;
    private final Runnable rebuildTask = this::rebuild;
    private final ContentObserver catalogObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshCatalog(); }
    };
    private FontStore store;
    private LinearLayout root;
    private LinearLayout statusCard;
    private TextView statusIcon, statusTitle;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            updateStatus();
            main.postDelayed(this, 2_000);
        }
    };
    private boolean dark;
    private int cBg, cCard, cText, cSub, cAccent, cOnAccent, surfaceHigh, surfaceHighest;
    private MaterialYou.Scheme sc;
    private final SparseArray<Typeface> uiFonts = new SparseArray<>();

    @SuppressWarnings("deprecation") // edge-to-edge system bars on Android 12–14
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new FontStore(this);
        dark = MaterialYou.isNight(this);
        sc = MaterialYou.fromSeed(0xFFB4558C, dark);  // same palette as the other modules
        cBg = sc.surface;
        cCard = sc.surfaceContainer;
        cText = sc.onSurface;
        cSub = sc.onSurfaceVariant;
        cAccent = sc.primary;
        cOnAccent = sc.onPrimary;
        surfaceHigh = MaterialYou.lerp(cCard, cText, dark ? 0.05f : 0.035f);
        surfaceHighest = MaterialYou.lerp(cCard, cText, dark ? 0.10f : 0.07f);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(dark ? 0 : light, light);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int available = Math.max(0, v.getWidth() - b.left - b.right);
            int horizontal = Math.max(dp(available >= dp(600) ? 24 : 16), Math.max(0, (available - dp(840)) / 2));
            root.setPadding(b.left + horizontal, b.top + dp(8), b.right + horizontal, b.bottom + dp(40));
            return WindowInsets.CONSUMED;
        });
        PullDownPromo.install(this, scroll, root);
        worker.execute(store::normalizeCustoms);
    }

    @Override protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(FontsProvider.CONTENT_URI, true, catalogObserver);
    }

    @Override protected void onStop() {
        getContentResolver().unregisterContentObserver(catalogObserver);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) rebuild();
        main.removeCallbacks(refreshStatus);
        main.post(refreshStatus);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refreshStatus);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        previewGeneration++;
        worker.shutdown();
        super.onDestroy();
    }

    private void rebuild() {
        if (root == null || isFinishing() || isDestroyed()) return;
        if (draggingSlider) { rebuildPending = true; return; }
        rebuildPending = false;
        main.removeCallbacks(rebuildTask);
        String revision = store.customRevision();
        if (!Objects.equals(shownCustoms, revision)) {
            previews.keySet().removeIf(id -> id.startsWith(FontSpec.CUSTOM_PREFIX));
            previewGeneration++;
        }
        shownCustoms = revision;
        root.removeAllViews();
        root.addView(statusCard());
        root.addView(layoutCard());
        root.addView(clockCard());

        TextView add = button(getString(R.string.add_font), true);
        add.setOnClickListener(v -> pickFont());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        root.addView(add, lp);

        List<FontSpec> all = store.all();
        for (String cat : BuiltinFonts.CATEGORIES) addCategory(cat, all);
    }

    private void refreshCatalog() {
        if (store != null && !Objects.equals(shownCustoms, store.customRevision())) rebuild();
    }

    // ------------------------------------------------------------------ clock layout

    /** Digital / analog digit layout of the lock screen clock, with the hands switch for the analog one. */
    private View layoutCard() {
        LinearLayout c = card();
        c.addView(header(getString(R.string.layout_title)));

        PixelSegmentedControl seg = new PixelSegmentedControl(this);
        seg.setColors(sc);
        TextView digital = segment(getString(R.string.layout_digital));
        TextView analog = segment(getString(R.string.layout_analog));
        seg.addView(digital, new LinearLayout.LayoutParams(0, dp(40), 1f));
        seg.addView(analog, new LinearLayout.LayoutParams(0, dp(40), 1f));
        c.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout digitalOptions = new LinearLayout(this);
        digitalOptions.setOrientation(LinearLayout.VERTICAL);
        digitalOptions.setPadding(0, dp(12), 0, 0);
        digitalOptions.addView(slider(getString(R.string.analog_size), ClockLayout.MIN_DIGITAL_SCALE,
                ClockLayout.MAX_DIGITAL_SCALE, SCALE_STEP, store.digitalScale(),
                v -> getString(R.string.clock_size_value, v), store::setDigitalScale));
        c.addView(digitalOptions);

        LinearLayout analogOptions = new LinearLayout(this);
        analogOptions.setOrientation(LinearLayout.VERTICAL);
        analogOptions.setPadding(0, dp(12), 0, 0);
        analogOptions.addView(slider(getString(R.string.analog_size), AnalogClock.MIN_SCALE,
                AnalogClock.MAX_SCALE, SCALE_STEP, store.analogScale(),
                v -> getString(R.string.clock_size_value, v), store::setAnalogScale));
        c.addView(analogOptions);

        LinearLayout hands = new LinearLayout(this);
        hands.setOrientation(LinearLayout.HORIZONTAL);
        hands.setGravity(Gravity.CENTER_VERTICAL);
        hands.setMinimumHeight(dp(56));
        hands.setPadding(dp(14), dp(4), dp(8), dp(4));
        hands.setBackground(ripple(round(surfaceHigh, dp(10)), dp(10), cAccent));
        TextView label = text(getString(R.string.analog_hands), 16, cText);
        label.setTypeface(uiFont(600));
        hands.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        PixelSwitch sw = new PixelSwitch(this);
        sw.setColors(sc);
        sw.setChecked(store.analogHands(), false);
        sw.setContentDescription(getString(R.string.analog_hands));
        sw.setOnCheckedChangeListener(on -> store.setAnalogHands(on));
        hands.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(48)));
        hands.setOnClickListener(v -> sw.toggle());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        analogOptions.addView(hands, hlp);

        Runnable sync = () -> {
            boolean on = store.analogLayout();
            digital.setTextColor(on ? cSub : sc.onSecondaryContainer);
            analog.setTextColor(on ? sc.onSecondaryContainer : cSub);
            digital.setSelected(!on);
            analog.setSelected(on);
            digitalOptions.setVisibility(on ? View.GONE : View.VISIBLE);
            analogOptions.setVisibility(on ? View.VISIBLE : View.GONE);
        };
        digital.setOnClickListener(v -> {
            if (!store.analogLayout()) return;
            store.setAnalogLayout(false);
            seg.select(0, true);
            sync.run();
        });
        analog.setOnClickListener(v -> {
            if (store.analogLayout()) return;
            store.setAnalogLayout(true);
            seg.select(1, true);
            sync.run();
        });
        seg.select(store.analogLayout() ? 1 : 0, false);
        sync.run();
        return c;
    }

    // ------------------------------------------------------------------ date

    /** Date switches shared by both clock layouts. */
    private View clockCard() {
        LinearLayout c = card();
        c.addView(header(getString(R.string.date_title)));

        View font = switchRow(getString(R.string.date_font), store.dateFont(), store::setDateFont);
        View hide = switchRow(getString(R.string.date_hide), store.dateHidden(), on -> {
            store.setDateHidden(on);
            font.setVisibility(on ? View.GONE : View.VISIBLE);
        });
        font.setVisibility(store.dateHidden() ? View.GONE : View.VISIBLE);
        c.addView(hide);
        c.addView(font);
        return c;
    }

    /** A titled slider from min to max in steps; the value is saved when the finger lifts (or on each key press). */
    private LinearLayout slider(String title, int min, int max, int step, int current, IntFunction<String> format,
            IntConsumer save) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        TextView label = text(title, 16, cText);
        label.setTypeface(uiFont(600));
        top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(format.apply(current), 16, cAccent);
        value.setTypeface(uiFont(600));
        top.addView(value);
        box.addView(top);

        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        bar.setProgress(Math.round((current - min) / (float) step));
        bar.setProgressTintList(ColorStateList.valueOf(cAccent));
        bar.setThumbTintList(ColorStateList.valueOf(cAccent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(MaterialYou.lerp(cCard, cSub, 0.5f)));
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress * step;
                value.setText(format.apply(v));
                if (fromUser && !dragging) save.accept(v);   // keyboard / accessibility
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
                draggingSlider = true;
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                draggingSlider = false;
                save.accept(min + s.getProgress() * step);
                if (rebuildPending) main.post(rebuildTask);
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return box;
    }

    /** A tappable row with a title and a switch at its end. */
    private View switchRow(String title, boolean checked, PixelSwitch.Listener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(14), dp(4), dp(8), dp(4));
        row.setBackground(ripple(round(surfaceHigh, dp(10)), dp(10), cAccent));
        TextView label = text(title, 16, cText);
        label.setTypeface(uiFont(600));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        PixelSwitch sw = new PixelSwitch(this);
        sw.setColors(sc);
        sw.setChecked(checked, false);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> sw.toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView segment(String label) {
        TextView t = text(label, 15, cSub);
        t.setTypeface(uiFont(600));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------ status

    private View statusCard() {
        LinearLayout c = card();
        statusSeen = null;
        statusCard = c;
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(dp(96));
        c.setBackground(round(sc.secondaryContainer, dp(24)));
        statusIcon = text("", 24, sc.onPrimary);
        statusIcon.setTypeface(uiFont(700));
        statusIcon.setGravity(Gravity.CENTER);
        statusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(sc.primary);
        statusIcon.setBackground(dot);
        c.addView(statusIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        statusTitle = text("", 18, sc.onSecondaryContainer);
        statusTitle.setTypeface(uiFont(600));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(16);
        c.addView(statusTitle, textParams);
        updateStatus();
        return c;
    }

    private void updateStatus() {
        if (statusCard == null || statusTitle == null || statusIcon == null) return;
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        boolean seen = store.lastSeen(HookEntry.THEMES) > boot
                || store.lastSeen(HookEntry.SYSTEMUI) > boot;
        if (statusSeen != null && statusSeen == seen) return;
        statusSeen = seen;
        int bg = seen ? sc.secondaryContainer : (dark ? 0xFF93000A : 0xFFFFDAD6);
        int fg = seen ? sc.onSecondaryContainer : (dark ? 0xFFFFDAD6 : 0xFF410002);
        int iconBg = seen ? sc.primary : (dark ? 0xFFFFB4AB : 0xFFBA1A1A);
        int iconFg = seen ? sc.onPrimary : (dark ? 0xFF690005 : Color.WHITE);
        statusCard.setBackground(round(bg, dp(24)));
        statusTitle.setTextColor(fg);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(iconBg);
        statusIcon.setBackground(dot);
        statusIcon.setTextColor(iconFg);
        statusIcon.setText(seen ? "✓" : "!");
        statusTitle.setText(seen ? R.string.status_ok : R.string.status_unknown);
    }

    // ------------------------------------------------------------------ font lists

    private void addCategory(String cat, List<FontSpec> catalog) {
        List<FontSpec> fonts = new ArrayList<>();
        List<PixelSwitch> categorySwitches = new ArrayList<>();
        for (FontSpec s : catalog) {
            if (s.category.equals(cat)) fonts.add(s);
        }
        if (fonts.isEmpty()) return;
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int shown = 0;
        for (FontSpec s : fonts) {
            if (store.isShown(s.id)) shown++;
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(header(categoryName(cat)));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        PixelSwitch all = new PixelSwitch(this);
        all.setColors(sc);
        all.setChecked(shown > 0, false);
        all.setContentDescription(getString(R.string.show_category, categoryName(cat)));
        all.setOnCheckedChangeListener(on -> {
            store.setCategoryShown(cat, on);
            for (PixelSwitch fontSwitch : categorySwitches) fontSwitch.setChecked(on, true);
        });
        head.addView(all, new LinearLayout.LayoutParams(dp(52), dp(48)));
        c.addView(head);
        for (FontSpec s : fonts) c.addView(fontRow(s, categorySwitches, all));
        root.addView(c);
    }

    private String categoryName(String cat) {
        switch (cat) {
            case BuiltinFonts.APPLE: return getString(R.string.cat_apple);
            case BuiltinFonts.SAMSUNG: return getString(R.string.cat_samsung);
            case BuiltinFonts.GOOGLE: return getString(R.string.cat_google);
            case BuiltinFonts.XIAOMI: return getString(R.string.cat_xiaomi);
            case BuiltinFonts.OPPO: return getString(R.string.cat_oppo);
            case BuiltinFonts.HUAWEI: return getString(R.string.cat_huawei);
            case BuiltinFonts.NOTHING: return getString(R.string.cat_nothing);
            case BuiltinFonts.MOTOROLA: return getString(R.string.cat_motorola);
            case BuiltinFonts.OTHER: return getString(R.string.cat_other);
            default: return getString(R.string.cat_custom);
        }
    }

    private View fontRow(FontSpec s, List<PixelSwitch> categorySwitches, PixelSwitch categorySwitch) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setBackground(ripple(round(surfaceHigh, dp(10)), dp(10), cAccent));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        TextView sample = text(getString(R.string.sample), 23, cText);
        sample.setSingleLine(true);
        sample.setEllipsize(TextUtils.TruncateAt.END);
        sample.setIncludeFontPadding(false);
        sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview(s, sample);
        row.addView(sample, new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = text(s.name, 15, cText);
        name.setTypeface(uiFont(600));
        name.setPadding(dp(8), 0, dp(8), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (s.isCustom()) {
            ImageButton more = new ImageButton(this);
            more.setImageResource(R.drawable.ic_more);
            more.setImageTintList(ColorStateList.valueOf(cSub));
            more.setBackground(ripple(round(surfaceHigh, dp(100)), dp(100), cAccent));
            more.setContentDescription(getString(R.string.more));
            more.setOnClickListener(v -> customMenu(v, s));
            pressMotion(more);
            row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(48)));
        }
        PixelSwitch sw = new PixelSwitch(this);
        sw.setColors(sc);
        sw.setChecked(store.isShown(s.id), false);
        sw.setContentDescription(getString(R.string.show_font, s.name));
        sw.setOnCheckedChangeListener(on -> {
            store.setShown(s.id, on);
            boolean any = false;
            for (PixelSwitch fontSwitch : categorySwitches) any |= fontSwitch.isChecked();
            categorySwitch.setChecked(any, true);
        });
        categorySwitches.add(sw);
        row.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private void preview(FontSpec spec, TextView sample) {
        if (previews.containsKey(spec.id)) {
            Typeface cached = previews.get(spec.id);
            if (cached != null) sample.setTypeface(cached);
            return;
        }
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        WeakReference<TextView> target = new WeakReference<>(sample);
        Context context = getApplicationContext();
        Handler handler = main;
        int generation = previewGeneration;
        worker.execute(() -> {
            MainActivity activity = owner.get();
            if (activity == null || activity.isDestroyed()) return;
            Typeface loaded = loadPreview(context, spec);
            handler.post(() -> {
                MainActivity live = owner.get();
                if (live == null || live.isFinishing() || live.isDestroyed() || live.previewGeneration != generation) return;
                live.previews.put(spec.id, loaded);
                TextView view = target.get();
                if (view != null && loaded != null) view.setTypeface(loaded);
            });
        });
    }

    private static Typeface loadPreview(Context context, FontSpec s) {
        try {
            Typeface.Builder b;
            if (s.isCustom()) {
                File f = new FontStore(context).customFile(s.id);
                if (f == null || !f.isFile()) return null;
                b = new Typeface.Builder(f);
            } else {
                b = new Typeface.Builder(context.getAssets(), s.asset);
            }
            if (s.supportsWeight()) b.setFontVariationSettings(s.variationSettings(s.weightDefault));
            return b.build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void customMenu(View anchor, FontSpec s) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.rename);
        m.getMenu().add(0, 2, 1, R.string.delete);
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) rename(s);
            else confirmDelete(s);
            return true;
        });
        m.show();
    }

    private void rename(FontSpec s) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setText(s.name);
        e.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.rename(s.id, e.getText().toString());
                    refreshCatalog();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(FontSpec s) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, s.name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    store.removeCustom(s.id);
                    previews.remove(s.id);
                    refreshCatalog();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ adding fonts

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, FONT_TYPES);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            toast(getString(R.string.err_no_picker));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        toast(getString(R.string.adding_font));
        worker.execute(() -> {
            String msg;
            try {
                FontSpec s = store.importFont(getContentResolver(), uri);
                msg = getString(R.string.add_done, s.name);
            } catch (FontStore.ImportException e) {
                msg = importError(e);
            }
            final String m = msg;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                toast(m);
                refreshCatalog();
            });
        });
    }

    private String importError(FontStore.ImportException e) {
        switch (e.reason) {
            case FontStore.ImportException.TOO_BIG:
                return getString(R.string.err_too_big, (int) (FontStore.MAX_FONT_BYTES >> 20));
            case FontStore.ImportException.WOFF: return getString(R.string.err_woff);
            case FontStore.ImportException.NOT_A_FONT: return getString(R.string.err_not_font);
            case FontStore.ImportException.NO_DIGITS: return getString(R.string.err_no_digits);
            case FontStore.ImportException.DUPLICATE:
                return getString(R.string.err_duplicate);
            default: return getString(R.string.err_io);
        }
    }

    // ------------------------------------------------------------------ views

    private GradientDrawable round(int color, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return bg;
    }

    private RippleDrawable ripple(GradientDrawable background, int radius, int color) {
        return new RippleDrawable(ColorStateList.valueOf(MaterialYou.withAlpha(color, 40)),
                background, round(Color.WHITE, radius));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(16));
        c.setBackground(round(cCard, dp(24)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private Typeface uiFont(int weight) {
        Typeface t = uiFonts.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(getAssets(), "ui/Montserrat.ttf")
                    .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (RuntimeException ignored) { t = Typeface.create(Typeface.DEFAULT, weight, false); }
        uiFonts.put(weight, t);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, WINDOW_TITLE_SP, cText);
        t.setTypeface(uiFont(700));
        t.setPadding(dp(4), dp(4), dp(4), dp(12));
        t.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        t.setMaxLines(2);
        t.setAccessibilityHeading(true);
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(uiFont(500));
        return t;
    }

    private TextView button(String label, boolean filled) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(uiFont(500));
        b.setTextColor(filled ? cOnAccent : sc.onSecondaryContainer);
        int color = filled ? cAccent : MaterialYou.lerp(sc.secondaryContainer, sc.onSecondaryContainer, dark ? 0.10f : 0.07f);
        b.setBackground(ripple(round(color, dp(100)), dp(100), filled ? cOnAccent : sc.onSecondaryContainer));
        b.setMinHeight(dp(48));
        pressMotion(b);
        return b;
    }

    private void pressMotion(View view) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            }
            return false;
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
