package com.example.parsecdemo;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/**
 * Modal settings overlay using Material 3 components: rounded surface
 * container, list of grouped cards, MaterialSwitch + Slider + dialog pickers.
 */
public final class SettingsPanel {

    public interface OnCloseListener { void onClose(); }

    public static FrameLayout build(final Activity a, final Settings s,
                                    final OnCloseListener onClose) {
        FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(0xCC000000);
        dim.setClickable(true);

        MaterialCardView panel = new MaterialCardView(a);
        panel.setRadius(dp(a, 28));
        panel.setCardBackgroundColor(MaterialUi.color(a,
                com.google.android.material.R.attr.colorSurface));
        panel.setStrokeWidth(0);
        panel.setCardElevation(dp(a, 6));
        panel.setUseCompatPadding(false);

        LinearLayout inside = new LinearLayout(a);
        inside.setOrientation(LinearLayout.VERTICAL);

        // === Top bar ===
        FrameLayout top = new FrameLayout(a);

        MaterialButton close = new MaterialButton(a, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        close.setIcon(a.getResources().getDrawable(R.drawable.ic_close, a.getTheme()));
        close.setIconSize(dp(a, 24));
        close.setIconPadding(0);
        close.setIconTint(ColorStateList.valueOf(MaterialUi.color(a,
                com.google.android.material.R.attr.colorOnSurface)));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> { if (onClose != null) onClose.onClose(); });
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dp(a, 48), dp(a, 48), Gravity.START | Gravity.CENTER_VERTICAL);
        closeLp.leftMargin = dp(a, 8);
        top.addView(close, closeLp);

        TextView title = new TextView(a);
        title.setText("Settings");
        title.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        top.addView(title, titleLp);

        inside.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 64)));

        // === Scrollable categories ===
        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = dp(a, 16);
        list.setPadding(side, dp(a, 4), side, dp(a, 24));

        // Interactivity
        list.addView(catTitle(a, "Interactivity"));
        LinearLayout interact = catCard(a);
        interact.addView(rowPicker(a, "Mouse Movement",
                new String[]{"Touchpad", "Direct"},
                new String[]{Settings.CURSOR_TOUCHPAD, Settings.CURSOR_DIRECT},
                s.cursorMode(), s::cursorMode));
        addDivider(a, interact);
        interact.addView(rowPicker(a, "Right Click Position",
                new String[]{"First Finger", "Middle", "Second Finger"},
                new String[]{Settings.RIGHTCLICK_FIRST, Settings.RIGHTCLICK_MIDDLE, Settings.RIGHTCLICK_SECOND},
                s.rightClickPosition(), s::rightClickPosition));
        addDivider(a, interact);
        interact.addView(rowSlider(a, "Cursor Scale", s.cursorScale(), 0.1f, 4f, 0.1f, s::cursorScale));
        addDivider(a, interact);
        interact.addView(rowSlider(a, "Mouse Sensitivity", s.mouseSensitivity(), 0.1f, 4f, 0.1f, s::mouseSensitivity));
        addDivider(a, interact);
        interact.addView(rowSlider(a, "Scroll Sensitivity", s.scrollSensitivity(), 1f, 10f, 1f, s::scrollSensitivity));
        list.addView(interact);

        // Graphics
        list.addView(catTitle(a, "Graphics"));
        LinearLayout graphics = catCard(a);
        graphics.addView(rowPickerInt(a, "Host Resolution",
                Settings.RESOLUTION_LABELS,
                new int[]{0, 1, 2, 3, 4},
                s.resolutionIndex(), s::resolutionIndex));
        addDivider(a, graphics);
        graphics.addView(rowPickerInt(a, "Host Refresh Rate",
                new String[]{"Auto", "120 Hz", "60 Hz", "30 Hz"},
                new int[]{0, 120, 60, 30},
                s.preferredFps(), s::preferredFps));
        addDivider(a, graphics);
        graphics.addView(rowPickerInt(a, "Bandwidth Limit",
                Settings.BANDWIDTH_LABELS,
                Settings.BANDWIDTH_VALUES,
                s.bandwidthMbps(), s::bandwidthMbps));
        addDivider(a, graphics);
        // Honest note: resolution/refresh only apply when you're the owner of
        // the host PC connecting first (i.e. streaming your own machine).
        graphics.addView(rowNote(a,
                "Resolution, refresh, and bandwidth changes require the host "
                + "to accept owner video settings. Host Default leaves its "
                + "bandwidth unchanged."));
        addDivider(a, graphics);
        graphics.addView(rowToggle(a, "Force Software Decoder",
                s.decoderCompatibility(), s::decoderCompatibility));
        addDivider(a, graphics);
        graphics.addView(rowToggle(a, "Show Performance Stats",
                s.showStats(), s::showStats));
        list.addView(graphics);

        // Appearance
        list.addView(catTitle(a, "Appearance"));
        LinearLayout appearance = catCard(a);
        appearance.addView(rowPicker(a, "Theme",
                new String[]{"System default", "Light", "Dark"},
                new String[]{Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK},
                s.themeMode(), v -> {
                    s.themeMode(v);
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                            ParsecApp.nightModeFor(v));
                }));
        addDivider(a, appearance);
        appearance.addView(rowPicker(a, "Orientation",
                new String[]{"Auto", "Landscape", "Portrait"},
                new String[]{Settings.ORIENT_AUTO, Settings.ORIENT_LANDSCAPE, Settings.ORIENT_PORTRAIT},
                s.orientation(), s::orientation));
        list.addView(appearance);

        // Misc
        list.addView(catTitle(a, "Misc"));
        LinearLayout misc = catCard(a);
        misc.addView(rowToggle(a, "Never Show Overlay", s.noOverlay(), s::noOverlay));
        addDivider(a, misc);
        misc.addView(rowToggle(a, "Hide Status Bar", s.hideStatusBar(), s::hideStatusBar));
        addDivider(a, misc);
        misc.addView(rowToggle(a, "Show Keyboard Button", s.showKeyboardButton(), s::showKeyboardButton));
        if (s.isQuestDevice()) {
            addDivider(a, misc);
            misc.addView(rowToggle(a, "Quest Controller Shortcuts",
                    s.questControllerShortcuts(), s::questControllerShortcuts));
            addDivider(a, misc);
            misc.addView(rowNote(a,
                    "When Horizon exposes Touch buttons: A = Alt+Tab, "
                    + "X = Copy, Y = Paste, hold B = Ctrl+Alt+Delete. "
                    + "A paired gamepad is not remapped."));
        }
        list.addView(misc);

        // About — version + manual update check
        list.addView(catTitle(a, "About"));
        LinearLayout about = catCard(a);
        about.addView(rowAction(a, "Version", "v" + BuildConfig.VERSION_NAME, null));
        addDivider(a, about);
        about.addView(rowAction(a, "Check for updates",
                "Look for a newer release on GitHub now",
                () -> UpdateChecker.checkInteractive(a)));
        list.addView(about);

        TextView credit = new TextView(a);
        credit.setText("OpenParsec Android · by NomadsGalaxy");
        credit.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurfaceVariant));
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(a, 16), 0, dp(a, 8));
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        list.addView(credit);

        sv.addView(list);
        inside.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        panel.addView(inside);

        int side2 = dp(a, 16);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.setMargins(side2, side2, side2, side2);
        dim.addView(panel, panelLp);

        return dim;
    }

    private static TextView catTitle(Context a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setAllCaps(false);
        t.setLetterSpacing(0.02f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(a, 8), dp(a, 20), 0, dp(a, 8));
        return t;
    }

    private static LinearLayout catCard(Context a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(MaterialUi.color(a,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        bg.setCornerRadius(dp(a, 20));
        l.setBackground(bg);
        l.setClipToOutline(true);
        return l;
    }

    private static void addDivider(Context a, LinearLayout parent) {
        View v = new View(a);
        v.setBackgroundColor(MaterialUi.color(a,
                com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(a, 16);
        lp.rightMargin = dp(a, 16);
        parent.addView(v, lp);
    }

    /** A small explanatory caption row (no control), used for honest notes
     *  like "this setting only applies when streaming your own PC". */
    private static View rowNote(Context a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int p = dp(a, 14);
        t.setPadding(p + dp(a, 2), dp(a, 6), p + dp(a, 2), dp(a, 10));
        return t;
    }

    private static LinearLayout row(Context a, String label) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(a, 14);
        row.setPadding(p + dp(a, 2), p, p + dp(a, 2), p);

        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurface));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(t, lp);
        return row;
    }

    public interface StringSetter { void set(String v); }
    public interface IntSetter { void set(int v); }
    public interface FloatSetter { void set(float v); }
    public interface BoolSetter { void set(boolean v); }

    private static View rowPicker(Context a, String label,
                                  final String[] labels, final String[] values,
                                  String current, final StringSetter onSet) {
        LinearLayout row = row(a, label);
        final MaterialButton btn = pickerButton(a, displayFor(labels, values, current));
        btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(a)
                .setTitle(label)
                .setItems(labels, (d, w) -> {
                    onSet.set(values[w]);
                    btn.setText(labels[w]);
                })
                .show());
        row.addView(btn);
        return row;
    }

    private static View rowPickerInt(Context a, String label,
                                     final String[] labels, final int[] values,
                                     int current, final IntSetter onSet) {
        LinearLayout row = row(a, label);
        final MaterialButton btn = pickerButton(a, displayForInt(labels, values, current));
        btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(a)
                .setTitle(label)
                .setItems(labels, (d, w) -> {
                    onSet.set(values[w]);
                    btn.setText(labels[w]);
                })
                .show());
        row.addView(btn);
        return row;
    }

    private static View rowSlider(Context a, String label, float current,
                                  final float min, final float max, final float step,
                                  final FloatSetter onSet) {
        LinearLayout row = row(a, label);
        final TextView val = new TextView(a);
        val.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurface));
        val.setText(String.format(java.util.Locale.US, "%.1f", current));
        val.setMinWidth(dp(a, 36));
        val.setGravity(Gravity.END);

        Slider bar = new Slider(a);
        bar.setValueFrom(min);
        bar.setValueTo(max);
        bar.setStepSize(step);
        bar.setValue(Math.max(min, Math.min(current, max)));
        bar.addOnChangeListener((s, value, fromUser) -> {
            onSet.set(value);
            val.setText(String.format(java.util.Locale.US, "%.1f", value));
        });
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                dp(a, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.rightMargin = dp(a, 8);
        row.addView(bar, barLp);
        row.addView(val);
        return row;
    }

    public interface Action { void run(); }

    /** Two-line row showing a label and a subtitle. If {@code onClick} is
     *  non-null the entire row is tappable; if null the row is informational
     *  only (e.g. version display). */
    private static View rowAction(final Context a, String label, String subtitle,
                                  final Action onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 14);
        row.setPadding(p + dp(a, 2), p, p + dp(a, 2), p);

        TextView title = new TextView(a);
        title.setText(label);
        title.setTextColor(MaterialUi.color(a, com.google.android.material.R.attr.colorOnSurface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(title);

        TextView sub = new TextView(a);
        sub.setText(subtitle);
        sub.setTextColor(MaterialUi.color(a, onClick != null
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(a, 2), 0, 0);
        row.addView(sub);

        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    private static View rowToggle(Context a, String label, boolean current, final BoolSetter onSet) {
        LinearLayout row = row(a, label);
        final MaterialSwitch sw = new MaterialSwitch(a);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener((b, v) -> onSet.set(v));
        row.addView(sw);
        return row;
    }

    private static MaterialButton pickerButton(Context a, String text) {
        MaterialButton b = new MaterialButton(a, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(a, 36));
        return b;
    }

    private static String displayFor(String[] labels, String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return labels[i];
        return labels[0];
    }

    private static String displayForInt(String[] labels, int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return labels[i];
        return labels[0];
    }

    private static int dp(Context a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
