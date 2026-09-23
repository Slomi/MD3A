package app.md3a.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows the Macro Deck 3 host's own web client (served at "/") full screen. A pairing code is handed
 * to it as "#enroll=&lt;code&gt;", which the web client redeems at /api/auth/device-enrollment/redeem.
 * Session, device id and settings are kept by the web client itself (cookies + localStorage).
 */
public class DeckActivity extends Activity {

    static final String EXTRA_BASE_URL = "baseUrl";
    static final String EXTRA_ENROLL = "enroll";
    private static final long RETRY_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout errorOverlay;
    private TextView errorText;
    private String baseUrl;
    private boolean mainFrameFailed;

    private final Runnable autoRetry = new Runnable() {
        @Override public void run() {
            if (errorOverlay.getVisibility() == View.VISIBLE) reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
        if (baseUrl == null) { finish(); return; }

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(buildErrorOverlay(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        setupWebView();
        if (savedInstanceState == null || !restore(savedInstanceState)) {
            web.loadUrl(startUrl(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = intent.getStringExtra(EXTRA_BASE_URL);
        if (url == null) return;
        setIntent(intent);
        baseUrl = url;
        web.loadUrl(startUrl(intent));
    }

    private String startUrl(Intent intent) {
        String enroll = intent.getStringExtra(EXTRA_ENROLL);
        String url = baseUrl + "/";
        if (enroll != null && !enroll.isEmpty()) url += "#enroll=" + Uri.encode(enroll);
        return url;
    }

    private boolean restore(Bundle state) {
        return web.restoreState(state) != null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " MacroDeck3AndroidClient/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openOutside(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openOutside(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainFrameFailed = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!mainFrameFailed) hideError();
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= 21 && request.isForMainFrame()) {
                    mainFrameFailed = true;
                    showError(getString(R.string.load_failed), true);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith(baseUrl)) {
                    mainFrameFailed = true;
                    showError(getString(R.string.load_failed), true);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never accept an untrusted certificate silently.
                handler.cancel();
                mainFrameFailed = true;
                showError(getString(R.string.cert_error), false);
            }
        });
    }

    /** Pages of the host stay inside; anything else (docs, store links) opens in the browser. */
    private boolean openOutside(Uri uri) {
        String u = uri.toString();
        if (u.startsWith(baseUrl + "/") || u.equals(baseUrl)) return false;
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme) && !"mailto".equals(scheme)) return true;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) { }
        return true;
    }

    // ---------------------------------------------------------------- error overlay

    private View buildErrorOverlay() {
        errorOverlay = new LinearLayout(this);
        errorOverlay.setOrientation(LinearLayout.VERTICAL);
        errorOverlay.setGravity(Gravity.CENTER);
        errorOverlay.setBackgroundColor(0xF016181B);
        int pad = dp(24);
        errorOverlay.setPadding(pad, pad, pad, pad);
        errorOverlay.setVisibility(View.GONE);
        errorOverlay.setClickable(true);

        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(16);
        errorText.setGravity(Gravity.CENTER);
        errorOverlay.addView(errorText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button retry = button(getString(R.string.retry), true);
        retry.setOnClickListener(v -> reload());
        Button change = button(getString(R.string.change_host), false);
        change.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(16), dp(6), 0);
        row.addView(retry, lp);
        row.addView(change, lp);
        errorOverlay.addView(row);
        return errorOverlay;
    }

    private void showError(String message, boolean retryAutomatically) {
        errorText.setText(message);
        errorOverlay.setVisibility(View.VISIBLE);
        main.removeCallbacks(autoRetry);
        if (retryAutomatically) main.postDelayed(autoRetry, RETRY_MS);
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
        main.removeCallbacks(autoRetry);
    }

    private void reload() {
        main.removeCallbacks(autoRetry);
        String current = web.getUrl();
        if (current == null || current.startsWith("data:") || current.equals("about:blank")) {
            web.loadUrl(baseUrl + "/");
        } else {
            web.reload();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            CookieManager.getInstance().flush();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.leave_title)
                .setMessage(R.string.leave_msg)
                .setPositiveButton(R.string.yes, (d, i) -> finish())
                .setNegativeButton(R.string.no, null)
                .show();
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(primary ? 0xFF0D6EFD : 0xFF2B3035);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setStateListAnimator(null);
        return b;
    }
}
