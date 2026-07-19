package com.example.parsecdemo;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HostListActivity extends AppCompatActivity {
    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_HOST_NAME = "host_name";

    private String sessionId;
    private LinearLayout hostsContainer;
    private TextView hostCount;
    private TextView refreshTime;
    private FrameLayout root;
    private FrameLayout settingsOverlay;
    private FrameLayout loadingOverlay;
    private Settings settings;

    private enum Tab { HOSTS, FRIENDS }
    private Tab currentTab = Tab.HOSTS;
    private ScrollView hostsScroll;
    private ScrollView friendsScroll;
    private LinearLayout friendsContainer;
    private TextView friendsHeader;
    private boolean friendsLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getStringExtra(LoginActivity.EXTRA_SESSION_ID);
        settings = new Settings(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(MaterialUi.color(this, com.google.android.material.R.attr.colorSurface));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);

        hostsScroll = buildHostsPage();
        pages.addView(hostsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        friendsScroll = buildFriendsPage();
        friendsScroll.setVisibility(View.GONE);
        pages.addView(friendsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(buildBottomTabs(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        loadHosts();
    }

    // ===== Top bar — MaterialToolbar with logo, host count, refresh, settings =====
    private MaterialToolbar buildTopBar() {
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurfaceContainer));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int hp = dp(12);
        content.setPadding(hp, 0, hp, 0);

        // Logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.parsec_logo);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoLp.rightMargin = dp(12);
        content.addView(logo, logoLp);

        hostCount = new TextView(this);
        hostCount.setText("0 hosts");
        hostCount.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        hostCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        hostCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams hcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        content.addView(hostCount, hcLp);

        MaterialButton refresh = iconButton(R.drawable.ic_refresh, "Refresh");
        refresh.setOnClickListener(v -> loadHosts());
        content.addView(refresh, iconLp());

        MaterialButton gear = iconButton(R.drawable.ic_settings, "Settings");
        gear.setOnClickListener(v -> openSettings());
        content.addView(gear, iconLp());

        MaterialButton logout = iconButton(R.drawable.ic_logout, "Logout");
        logout.setOnClickListener(v -> confirmLogout());
        content.addView(logout, iconLp());

        bar.addView(content, new MaterialToolbar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return bar;
    }

    private MaterialButton iconButton(int drawableRes, String contentDesc) {
        MaterialButton b = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        b.setIcon(getResources().getDrawable(drawableRes, getTheme()));
        b.setIconSize(dp(24));
        b.setIconPadding(0);
        b.setIconTint(ColorStateList.valueOf(
                MaterialUi.color(this, com.google.android.material.R.attr.colorOnSurface)));
        b.setContentDescription(contentDesc);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        return b;
    }

    private LinearLayout.LayoutParams iconLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(4);
        return lp;
    }

    // ===== Hosts page =====
    private ScrollView buildHostsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurface));

        LinearLayout colL = new LinearLayout(this);
        colL.setOrientation(LinearLayout.VERTICAL);
        colL.setPadding(dp(16), dp(16), dp(16), dp(16));

        refreshTime = new TextView(this);
        refreshTime.setText("Last refreshed —");
        refreshTime.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        refreshTime.setGravity(Gravity.CENTER);
        refreshTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rtLp.bottomMargin = dp(16);
        colL.addView(refreshTime, rtLp);

        hostsContainer = new LinearLayout(this);
        hostsContainer.setOrientation(LinearLayout.VERTICAL);
        hostsContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        colL.addView(hostsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv.addView(colL);
        return sv;
    }

    private ScrollView buildFriendsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurface));

        LinearLayout colL = new LinearLayout(this);
        colL.setOrientation(LinearLayout.VERTICAL);
        colL.setPadding(dp(16), dp(16), dp(16), dp(16));

        friendsHeader = new TextView(this);
        friendsHeader.setText("Loading…");
        friendsHeader.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        friendsHeader.setGravity(Gravity.CENTER);
        friendsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(12);
        colL.addView(friendsHeader, hLp);

        friendsContainer = new LinearLayout(this);
        friendsContainer.setOrientation(LinearLayout.VERTICAL);
        friendsContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        colL.addView(friendsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv.addView(colL);
        return sv;
    }

    private void loadFriendsIfNeeded() {
        if (friendsLoaded) return;
        friendsLoaded = true;
        loadFriends();
    }

    private void loadFriends() {
        if (friendsContainer == null) return;
        friendsContainer.removeAllViews();
        if (friendsHeader != null) friendsHeader.setText("Loading…");

        new AsyncTask<Void, Void, FriendsResult>() {
            @Override protected FriendsResult doInBackground(Void... v) {
                FriendsResult r = new FriendsResult();
                try { r.self = ParsecApi.getSelfInfo(sessionId); }
                catch (Exception e) { r.error = e; }
                try { r.friends = ParsecApi.listFriends(sessionId); }
                catch (Exception e) { if (r.error == null) r.error = e; }
                return r;
            }
            @Override protected void onPostExecute(FriendsResult r) {
                if (r.error != null && r.self == null
                        && (r.friends == null || r.friends.isEmpty())) {
                    friendsHeader.setText("Couldn't load friends");
                    return;
                }
                if (r.self != null) {
                    friendsContainer.addView(makeUserCard(
                            "You", r.self.id, r.self.name));
                }
                int count = r.friends == null ? 0 : r.friends.size();
                friendsHeader.setText(count + " " + (count == 1 ? "friend" : "friends"));
                if (count > 0) {
                    TextView section = new TextView(HostListActivity.this);
                    section.setText("Friends");
                    section.setTextColor(MaterialUi.color(HostListActivity.this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
                    section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    section.setPadding(dp(8), dp(16), 0, dp(8));
                    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                            Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(540)),
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    sLp.gravity = Gravity.CENTER_HORIZONTAL;
                    friendsContainer.addView(section, sLp);
                    for (ParsecApi.Friend f : r.friends) {
                        friendsContainer.addView(makeUserCard(
                                f.userName == null || f.userName.isEmpty()
                                        ? "User" : f.userName,
                                f.userId, null));
                    }
                }
            }
        }.execute();
    }

    private static final class FriendsResult {
        ParsecApi.SelfUser self;
        java.util.List<ParsecApi.Friend> friends;
        Exception error;
    }

    /** Card layout shared by self + friends: round-cornered avatar + username#id text. */
    private View makeUserCard(String name, int userId, String emailIfSelf) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        card.setRadius(dp(20));
        card.setStrokeWidth(0);
        card.setCardElevation(dp(0));
        card.setUseCompatPadding(false);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(14);
        inner.setPadding(p, p, p, p);

        FrameLayout avatarWrap = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorPrimaryContainer));
        g.setCornerRadius(dp(14));
        avatarWrap.setBackground(g);
        avatarWrap.setClipToOutline(true);
        ImageView ic = new ImageView(this);
        ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarWrap.addView(ic, new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.CENTER));
        LinearLayout.LayoutParams awLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        awLp.rightMargin = dp(14);
        inner.addView(avatarWrap, awLp);
        AvatarLoader.into(ic, ParsecApi.avatarUrl(userId), R.drawable.parsec_logo);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(name + "#" + userId);
        nameView.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(nameView);

        if (emailIfSelf != null) {
            TextView role = new TextView(this);
            role.setText(emailIfSelf);
            role.setTextColor(MaterialUi.color(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            role.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            textCol.addView(role);
        }

        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inner.addView(textCol, tcLp);

        card.addView(inner);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(540)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    // ===== Bottom tabs — Material BottomNavigationView =====
    private BottomNavigationView buildBottomTabs() {
        BottomNavigationView nav = new BottomNavigationView(this);
        nav.setBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurfaceContainer));
        nav.getMenu().add(0, R.id.nav_hosts, 0, "Hosts").setIcon(R.drawable.ic_monitor);
        nav.getMenu().add(0, R.id.nav_friends, 1, "Friends").setIcon(R.drawable.ic_people);
        nav.setSelectedItemId(R.id.nav_hosts);
        nav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId() == R.id.nav_hosts ? Tab.HOSTS : Tab.FRIENDS);
            return true;
        });
        return nav;
    }

    private void switchTab(Tab t) {
        if (currentTab == t) return;
        currentTab = t;
        boolean h = t == Tab.HOSTS;
        hostsScroll.setVisibility(h ? View.VISIBLE : View.GONE);
        friendsScroll.setVisibility(h ? View.GONE : View.VISIBLE);
        if (!h) loadFriendsIfNeeded();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ===== Hosts data =====
    private void loadHosts() {
        showLoading("Refreshing hosts…");
        hostsContainer.removeAllViews();
        new AsyncTask<Void, Void, List<ParsecApi.Host>>() {
            Exception error;
            @Override protected List<ParsecApi.Host> doInBackground(Void... v) {
                try { return ParsecApi.listHosts(sessionId); }
                catch (Exception e) { error = e; return null; }
            }
            @Override protected void onPostExecute(List<ParsecApi.Host> hosts) {
                hideLoading();
                if (error != null) {
                    hostCount.setText("0 hosts");
                    if (looksLikeAuthFailure(error)) {
                        // Saved session expired or revoked — wipe and force a
                        // fresh login. No alert; the login screen is the right
                        // next step on its own.
                        settings.clearSession();
                        Intent i = new Intent(HostListActivity.this, LoginActivity.class);
                        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        finish();
                        return;
                    }
                    new MaterialAlertDialogBuilder(HostListActivity.this)
                            .setTitle("Couldn't load hosts")
                            .setMessage(error.getMessage())
                            .setPositiveButton("OK", null).show();
                    return;
                }
                int total = hosts.size();
                String grammar = total == 1 ? "host" : "hosts";
                hostCount.setText(total + " " + grammar);
                SimpleDateFormat fmt = new SimpleDateFormat("M/d h:mm a", Locale.US);
                refreshTime.setText("Last refreshed " + fmt.format(new Date()));
                if (total == 0) {
                    TextView empty = new TextView(HostListActivity.this);
                    empty.setText("No hosts available");
                    empty.setTextColor(MaterialUi.color(HostListActivity.this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(48), 0, 0);
                    hostsContainer.addView(empty);
                    return;
                }
                for (ParsecApi.Host h : hosts) {
                    hostsContainer.addView(makeHostCard(h));
                }
            }
        }.execute();
    }

    private View makeHostCard(final ParsecApi.Host h) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        card.setRadius(dp(20));
        card.setStrokeWidth(0);
        card.setCardElevation(dp(0));
        card.setUseCompatPadding(false);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(16);
        inner.setPadding(p, p, p, p);

        // Avatar with logo
        FrameLayout avatarWrap = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorPrimaryContainer));
        g.setCornerRadius(dp(16));
        avatarWrap.setBackground(g);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.parsec_logo);
        FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.CENTER);
        avatarWrap.addView(ic, icLp);
        LinearLayout.LayoutParams awLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        awLp.rightMargin = dp(14);
        inner.addView(avatarWrap, awLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(h.name.isEmpty() ? "Unnamed host" : h.name);
        name.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(name);

        TextView sub = new TextView(this);
        sub.setText(h.userName.isEmpty() ? (h.online ? "Online" : "Offline") : h.userName);
        sub.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textCol.addView(sub);

        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inner.addView(textCol, tcLp);

        MaterialButton connect = new MaterialButton(this);
        connect.setText(h.online ? "Connect" : "Offline");
        connect.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        connect.setEnabled(h.online && !h.peerId.isEmpty());
        connect.setOnClickListener(v -> connectTo(h));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        inner.addView(connect, cLp);

        card.addView(inner);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(540)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private void connectTo(ParsecApi.Host h) {
        Intent i = new Intent(this, ParsecActivity.class);
        i.putExtra(LoginActivity.EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_PEER_ID, h.peerId);
        i.putExtra(EXTRA_HOST_NAME, h.name);
        startActivity(i);
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Log out?")
                .setPositiveButton("Log out", (d, w) -> doLogout())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLogout() {
        // Clear the persisted session so the next launch lands on LoginActivity.
        settings.clearSession();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    /** If the saved session token has expired (e.g. the user logged out from
     *  another device), the very first listHosts call will fail. Treat that
     *  as a hint to clear the token and bounce back to the login screen. */
    private boolean looksLikeAuthFailure(Exception e) {
        if (e == null) return false;
        String m = e.getMessage();
        if (m == null) return false;
        String low = m.toLowerCase(Locale.US);
        return low.contains("401") || low.contains("403")
                || low.contains("unauthor") || low.contains("forbidden")
                || low.contains("invalid session") || low.contains("expired");
    }

    // ===== Loading overlay =====
    private void showLoading(String msg) {
        hideLoading();
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xCC000000);
        loadingOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(MaterialUi.surfaceContainerHigh(this, dp(24)));
        int p = dp(28);
        box.setPadding(p, p, p, p);

        CircularProgressIndicator spin = new CircularProgressIndicator(this);
        spin.setIndeterminate(true);
        spin.setIndicatorSize(dp(40));
        box.addView(spin);

        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        t.setPadding(0, dp(12), 0, 0);
        box.addView(t);

        loadingOverlay.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideLoading() {
        if (loadingOverlay != null) {
            root.removeView(loadingOverlay);
            loadingOverlay = null;
        }
    }

    // ===== Settings overlay =====
    private void openSettings() {
        if (settingsOverlay != null) return;
        settingsOverlay = SettingsPanel.build(this, settings, () -> closeSettings());
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeSettings() {
        if (settingsOverlay != null) {
            root.removeView(settingsOverlay);
            settingsOverlay = null;
        }
    }
}
