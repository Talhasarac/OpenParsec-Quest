package com.example.parsecdemo;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

/**
 * Draggable in-session settings button. Built from the same primitives as
 * the {@link DebugGrid} tiles — a plain {@link FrameLayout} with a solid
 * {@link GradientDrawable} background — to guarantee it renders in the same
 * compositing layer as every other view and never clips against the
 * GLSurfaceView.
 */
public final class SessionFab {

    public interface MenuItemAction { void run(); }

    public static final class Item {
        public final String label;
        public final MenuItemAction action;
        public final boolean highlight;
        public Item(String label, boolean highlight, MenuItemAction action) {
            this.label = label;
            this.highlight = highlight;
            this.action = action;
        }
        public Item(String label, MenuItemAction action) { this(label, false, action); }
    }

    private final Activity activity;
    private final FrameLayout root;
    private final FrameLayout fab;
    private final List<Item> items;
    private final HoldToDragTouchListener dragTouchListener;
    private FrameLayout menu;
    private boolean menuOpen = false;

    private static final int FAB_SIZE_DP = 56;
    private static final int EDGE_PAD_DP = 16;

    public SessionFab(Activity activity, FrameLayout root, List<Item> items) {
        this.activity = activity;
        this.root = root;
        this.items = items;

        // Plain FrameLayout with an oval background — same primitives the
        // DebugGrid uses, which renders correctly at every screen position.
        FrameLayout container = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(MaterialUi.color(activity,
                com.google.android.material.R.attr.colorPrimaryContainer));
        container.setBackground(bg);
        container.setClickable(true);
        container.setFocusable(true);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_tune);
        icon.setImageTintList(ColorStateList.valueOf(MaterialUi.color(activity,
                com.google.android.material.R.attr.colorOnPrimaryContainer)));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        int p = dp(14);
        iconLp.setMargins(p, p, p, p);
        container.addView(icon, iconLp);

        this.fab = container;

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(FAB_SIZE_DP), dp(FAB_SIZE_DP));
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(fab, lp);

        root.post(() -> {
            int rw = root.getWidth();
            fab.setX(rw - dp(FAB_SIZE_DP) - dp(EDGE_PAD_DP));
            fab.setY(root.getHeight() / 3f);
        });

        fab.setOnClickListener(v -> toggleMenu());
        dragTouchListener = new HoldToDragTouchListener(
                fab, root, this::hideMenu, this::snapToNearestEdge);
        fab.setOnTouchListener(dragTouchListener);
    }

    public void setVisible(boolean visible) {
        fab.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) hideMenu();
    }

    /** Snap the FAB back to a known-good visible position. Used as a recovery
     *  hatch (e.g. invoked by the activity's 4-finger tap) in case the FAB
     *  ends up offscreen after a rotation, fold, or other layout change. */
    public void resetPosition() {
        dragTouchListener.cancelGesture();
        hideMenu();
        Runnable apply = () -> {
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) { root.post(this::resetPosition); return; }
            fab.setX(rw - dp(FAB_SIZE_DP) - dp(EDGE_PAD_DP));
            fab.setY(rh / 3f);
        };
        if (root.getWidth() == 0 || root.getHeight() == 0) root.post(apply);
        else apply.run();
    }

    public boolean isMenuOpen() { return menuOpen; }

    public void hideMenu() {
        if (menu != null) {
            root.removeView(menu);
            menu = null;
        }
        menuOpen = false;
    }

    private void snapToNearestEdge() {
        float cx = fab.getX() + fab.getWidth() / 2f;
        float rw = root.getWidth();
        float target = (cx < rw / 2f) ? dp(EDGE_PAD_DP) : (rw - fab.getWidth() - dp(EDGE_PAD_DP));
        float y = Math.max(dp(EDGE_PAD_DP),
                Math.min(fab.getY(), root.getHeight() - fab.getHeight() - dp(EDGE_PAD_DP)));
        fab.setX(target);
        fab.setY(y);
    }

    private void toggleMenu() {
        if (menuOpen) { hideMenu(); return; }
        showMenu();
    }

    private void showMenu() {
        if (items == null || items.isEmpty()) return;

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        list.setPadding(pad, pad, pad, pad);

        for (final Item it : items) {
            TextView t = new TextView(activity);
            t.setText(it.label);
            int color = it.highlight
                    ? MaterialUi.color(activity, com.google.android.material.R.attr.colorError)
                    : MaterialUi.color(activity, com.google.android.material.R.attr.colorOnSurface);
            t.setTextColor(color);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            int ip = dp(12);
            int iph = dp(20);
            t.setPadding(iph, ip, iph, ip);
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(v -> {
                hideMenu();
                if (it.action != null) it.action.run();
            });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            list.addView(t, ilp);
        }

        // Wrap the list in a plain FrameLayout with a rounded background to
        // match the rendering primitives used by the FAB and DebugGrid.
        FrameLayout menuContainer = new FrameLayout(activity);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setShape(GradientDrawable.RECTANGLE);
        menuBg.setColor(MaterialUi.color(activity,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        menuBg.setCornerRadius(dp(20));
        menuContainer.setBackground(menuBg);
        menuContainer.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        menu = menuContainer;
        root.addView(menu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        menu.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                menu.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                positionMenuAdaptive();
            }
        });
        menuOpen = true;
    }

    private void positionMenuAdaptive() {
        int rw = root.getWidth();
        int rh = root.getHeight();
        int mw = menu.getWidth();
        int mh = menu.getHeight();
        float fx = fab.getX();
        float fy = fab.getY();
        int fw = fab.getWidth();
        int fh = fab.getHeight();
        float fabCx = fx + fw / 2f;
        float fabCy = fy + fh / 2f;
        int gap = dp(12);

        float leftRoom = fx;
        float rightRoom = rw - (fx + fw);
        float menuX;
        boolean placedLaterally = false;
        if (rightRoom >= mw + gap && rightRoom >= leftRoom) {
            menuX = fx + fw + gap;
            placedLaterally = true;
        } else if (leftRoom >= mw + gap) {
            menuX = fx - mw - gap;
            placedLaterally = true;
        } else {
            menuX = Math.max(gap, Math.min(rw - mw - gap, fabCx - mw / 2f));
        }

        float menuY;
        if (placedLaterally) {
            menuY = fabCy - mh / 2f;
            menuY = Math.max(gap, Math.min(menuY, rh - mh - gap));
        } else {
            float topRoom = fy;
            float bottomRoom = rh - (fy + fh);
            if (bottomRoom >= mh + gap && bottomRoom >= topRoom) {
                menuY = fy + fh + gap;
            } else {
                menuY = fy - mh - gap;
            }
            menuY = Math.max(gap, Math.min(menuY, rh - mh - gap));
        }

        menu.setX(menuX);
        menu.setY(menuY);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
