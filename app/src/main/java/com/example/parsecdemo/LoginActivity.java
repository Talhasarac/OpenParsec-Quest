package com.example.parsecdemo;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class LoginActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "session_id";

    private TextInputEditText emailField;
    private TextInputEditText passwordField;
    private MaterialButton loginButton;
    private FrameLayout root;
    private FrameLayout loadingOverlay;
    private Settings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(MaterialUi.color(this, com.google.android.material.R.attr.colorSurface));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);

        // Centered logo + "OpenParsec" wordmark (logo_shadow.png from OpenParsec assets)
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.parsec_logo);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        logoLp.bottomMargin = dp(8);
        form.addView(logo, logoLp);

        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.openparsec_wordmark);
        wordmark.setAdjustViewBounds(true);
        LinearLayout.LayoutParams wmLp = new LinearLayout.LayoutParams(
                dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        wmLp.bottomMargin = dp(32);
        wmLp.gravity = Gravity.CENTER_HORIZONTAL;
        form.addView(wordmark, wmLp);

        if (QuestPlatform.isQuest(this)) {
            TextView questHint = new TextView(this);
            questHint.setText(R.string.quest_control_hint);
            questHint.setGravity(Gravity.CENTER);
            questHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            questHint.setTextColor(MaterialUi.color(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.bottomMargin = dp(16);
            form.addView(questHint, hintLp);
        }

        emailField = new TextInputEditText(this);
        emailField.setText(settings.lastEmail());
        TextInputLayout emailLayout = MaterialUi.textField(this, "Email", emailField,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(emailLayout, stackLp());

        passwordField = new TextInputEditText(this);
        TextInputLayout passwordLayout = MaterialUi.textField(this, "Password", passwordField,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        form.addView(passwordLayout, stackLp());

        loginButton = new MaterialButton(this);
        loginButton.setText("Log in");
        loginButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        loginButton.setOnClickListener(v -> authenticate(""));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        btnLp.topMargin = dp(16);
        form.addView(loginButton, btnLp);

        LinearLayout.LayoutParams formLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        formLp.gravity = Gravity.CENTER;
        outer.addView(form, formLp);
        form.getLayoutParams().width = Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(48), dp(420));

        scroll.addView(outer);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Accreditation footer + version pinned to the bottom of the screen.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.addView(buildCreditsView());

        TextView version = new TextView(this);
        version.setText("v" + BuildConfig.VERSION_NAME);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        version.setTextColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(6);
        footer.addView(version, vLp);

        FrameLayout.LayoutParams credLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        credLp.bottomMargin = dp(16);
        credLp.leftMargin = dp(24);
        credLp.rightMargin = dp(24);
        root.addView(footer, credLp);

        // Soft keyboard handling: the manifest sets windowSoftInputMode=adjustPan
        // for this activity so the form pans up over the footer when the IME
        // opens, instead of adjustResize which would shrink the layout and
        // squish the bottom-anchored footer into the inputs.

        setContentView(root);

        // The upstream Android updater installs a differently packaged build.
        // Quest releases are installed through ADB/SideQuest instead.
        if (!QuestPlatform.isQuest(this)) {
            UpdateChecker.checkInBackground(this);
        }
    }

    private TextView buildCreditsView() {
        final String openParsecUrl = "https://github.com/hugeBlack/OpenParsec";
        final String androidPortUrl = "https://github.com/NomadsGalaxy";

        String line1Prefix = "OpenParsec originally by ";
        String line1Author = "hugeBlack";
        String line2Prefix = "Android port by ";
        String line2Author = "NomadsGalaxy";
        String text = line1Prefix + line1Author + "\n" + line2Prefix + line2Author;

        SpannableString s = new SpannableString(text);
        int start1 = line1Prefix.length();
        int end1   = start1 + line1Author.length();
        int start2 = line1Prefix.length() + line1Author.length() + 1 /*\n*/ + line2Prefix.length();
        int end2   = start2 + line2Author.length();

        int linkColor = MaterialUi.color(this, com.google.android.material.R.attr.colorPrimary);
        s.setSpan(makeLink(openParsecUrl), start1, end1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(linkColor), start1, end1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(makeLink(androidPortUrl), start2, end2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(linkColor), start2, end2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView t = new TextView(this);
        t.setText(s);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(MaterialUi.color(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private ClickableSpan makeLink(final String url) {
        return new ClickableSpan() {
            @Override public void onClick(View widget) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) { /* no browser installed */ }
            }
        };
    }

    private LinearLayout.LayoutParams stackLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showLoading(String text) {
        hideLoading();
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xCC000000);
        loadingOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(MaterialUi.surfaceContainer(this, dp(24)));
        int p = dp(28);
        box.setPadding(p, p, p, p);

        CircularProgressIndicator spin = new CircularProgressIndicator(this);
        spin.setIndeterminate(true);
        spin.setIndicatorSize(dp(40));
        box.addView(spin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MaterialUi.color(this, com.google.android.material.R.attr.colorOnSurface));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, 0);
        box.addView(t);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        loadingOverlay.addView(box, boxLp);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideLoading() {
        if (loadingOverlay != null) {
            root.removeView(loadingOverlay);
            loadingOverlay = null;
        }
    }

    private void showAlert(String title, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void promptTfa() {
        final TextInputEditText tfaInput = new TextInputEditText(this);
        tfaInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextInputLayout layout = MaterialUi.textField(this, "Authenticator code", tfaInput,
                InputType.TYPE_CLASS_NUMBER);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Two-factor authentication")
                .setMessage("Enter the code from your authenticator app")
                .setView(layout)
                .setPositiveButton("Submit", (d, w) -> authenticate(tfaInput.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        tfaInput.requestFocus();
        tfaInput.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(tfaInput,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void authenticate(final String tfa) {
        final String email = emailField.getText().toString().trim();
        final String pass = passwordField.getText().toString();
        if (email.isEmpty() || pass.isEmpty()) {
            showAlert("Login Failed", "Enter email and password.");
            return;
        }
        showLoading("Signing in…");

        new AsyncTask<Void, Void, ParsecApi.AuthResult>() {
            Exception error;
            @Override protected ParsecApi.AuthResult doInBackground(Void... v) {
                try { return ParsecApi.login(email, pass, tfa); }
                catch (Exception e) { error = e; return null; }
            }
            @Override protected void onPostExecute(ParsecApi.AuthResult r) {
                hideLoading();
                if (error != null) {
                    showAlert("Login Failed", "Network error: " + error.getMessage());
                    return;
                }
                if (r.info != null) {
                    // Persist the session token + email so the next launch can
                    // skip the login screen entirely.
                    settings.sessionId(r.info.sessionId);
                    settings.lastEmail(email);
                    Intent i = new Intent(LoginActivity.this, HostListActivity.class);
                    i.putExtra(EXTRA_SESSION_ID, r.info.sessionId);
                    startActivity(i);
                    finish();
                } else if (r.tfaRequired) {
                    promptTfa();
                } else {
                    showAlert("Login Failed", r.error == null ? "Unknown error" : r.error);
                }
            }
        }.execute();
    }
}
