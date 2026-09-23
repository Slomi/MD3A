package app.md3a.client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host selection. Finds Macro Deck 3 hosts over DNS-SD (_macrodeck._tcp), accepts a manual address or
 * a connect link from the QR code, verifies the host with GET /api/auth/status and opens the host's
 * built-in web client in {@link DeckActivity}.
 */
public class MainActivity extends Activity {

    static final String PREFS = "md3a";
    static final String KEY_LAST_URL = "lastUrl";
    static final String KEY_AUTO = "autoConnect";
    static final String EXTRA_NO_AUTO = "noAuto";
    static final int DEFAULT_PORT = 8193;
    private static final String SERVICE_TYPE = "_macrodeck._tcp.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private LinearLayout discoveredList;
    private TextView discoveredEmpty;
    private EditText address;
    private EditText code;
    private TextView status;
    private Button openAnyway;
    private Button connectButton;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private final Map<String, String> found = new LinkedHashMap<>(); // base url -> label

    private volatile int attempt; // cancels stale probes
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();

        boolean handled = handleIntent(getIntent());
        if (!handled && savedInstanceState == null
                && !getIntent().getBooleanExtra(EXTRA_NO_AUTO, false)
                && prefs.getBoolean(KEY_AUTO, true)) {
            String last = prefs.getString(KEY_LAST_URL, null);
            if (last != null) probeAndOpen(listOf(last), "", true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startDiscovery();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDiscovery();
    }

    // ---------------------------------------------------------------- intents

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        String link = ConnectLink.extract(text);
        if (link == null) return false;
        useConnectLink(link);
        return true;
    }

    private void useConnectLink(String link) {
        ConnectLink parsed;
        try {
            parsed = ConnectLink.parse(link);
        } catch (IllegalArgumentException e) {
            setStatus(getString(R.string.bad_link), true);
            return;
        }
        // Plain HTTP first (the host lists it first as well): a self-signed TLS listener
        // would be refused by the WebView anyway.
        List<String> urls = new ArrayList<>();
        for (ConnectLink.Endpoint e : parsed.endpoints) if (!e.tls) urls.add(e.baseUrl());
        for (ConnectLink.Endpoint e : parsed.endpoints) if (e.tls) urls.add(e.baseUrl());
        if (urls.isEmpty()) {
            setStatus(getString(R.string.bad_link), true);
            return;
        }
        address.setText(urls.get(0).replaceFirst("^https?://", ""));
        code.setText(parsed.token);
        probeAndOpen(urls, parsed.token, false);
    }

    // ---------------------------------------------------------------- connecting

    private void onConnectClicked() {
        String input = address.getText().toString().trim();
        if (ConnectLink.extract(input) != null) {
            useConnectLink(ConnectLink.extract(input));
            return;
        }
        if (input.isEmpty()) {
            setStatus(getString(R.string.bad_input), true);
            return;
        }
        probeAndOpen(candidatesFor(input), code.getText().toString().trim(), false);
    }

    /** "192.168.1.10", "192.168.1.10:8193", "http://pc.local:8193/" ... -> base urls to try. */
    static List<String> candidatesFor(String input) {
        List<String> out = new ArrayList<>();
        String s = input.trim().replaceAll("/+$", "");
        Matcher m = Pattern.compile("^(?:(https?)://)?(\\[[^\\]]+\\]|[^/:]+)(?::(\\d+))?(/.*)?$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (!m.matches()) {
            out.add(s);
            return out;
        }
        String scheme = m.group(1) == null ? "http" : m.group(1).toLowerCase();
        String host = m.group(2);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        if (m.group(3) != null) {
            out.add(scheme + "://" + host + ":" + m.group(3));
        } else {
            out.add(scheme + "://" + host + ":" + DEFAULT_PORT);
            out.add(scheme + "://" + host);
        }
        return out;
    }

    private void probeAndOpen(final List<String> urls, final String pairingCode, final boolean silentFail) {
        final int my = ++attempt;
        openAnyway.setVisibility(View.GONE);
        connectButton.setEnabled(false);
        setStatus(getString(R.string.checking, display(urls.get(0))), false);

        new Thread(() -> {
            String ok = null;
            String answeredButNotMd3 = null;
            for (String url : urls) {
                int r = probe(url);
                if (r == PROBE_MD3) { ok = url; break; }
                if (r == PROBE_OTHER && answeredButNotMd3 == null) answeredButNotMd3 = url;
            }
            final String okUrl = ok;
            final String other = answeredButNotMd3;
            main.post(() -> {
                if (my != attempt || isFinishing()) return;
                connectButton.setEnabled(true);
                if (okUrl != null) {
                    setStatus("", false);
                    openDeck(okUrl, pairingCode);
                    return;
                }
                if (silentFail) {
                    setStatus(getString(R.string.unreachable, display(urls.get(0))), true);
                } else if (other != null) {
                    setStatus(getString(R.string.not_md3, display(other)), true);
                } else {
                    setStatus(getString(R.string.unreachable, display(urls.get(0))), true);
                }
                pendingUrl = other != null ? other : urls.get(0);
                openAnyway.setTag(pairingCode);
                openAnyway.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private static final int PROBE_FAIL = 0, PROBE_OTHER = 1, PROBE_MD3 = 2;

    /**
     * A Macro Deck 3 host answers GET /api/auth/status with 200 and a JSON object holding a boolean
     * "setupComplete" (engineering/api/macro-deck-2-app.md). It keeps answering while the key ring is locked.
     */
    private static int probe(String baseUrl) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/api/auth/status").openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(3500);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) return PROBE_OTHER;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 65536) buf.write(chunk, 0, n);
            String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            try {
                Object v = new org.json.JSONObject(body).opt("setupComplete");
                return v instanceof Boolean ? PROBE_MD3 : PROBE_OTHER;
            } catch (org.json.JSONException e) {
                return PROBE_OTHER;
            }
        } catch (Exception e) {
            return PROBE_FAIL;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void openDeck(String baseUrl, String pairingCode) {
        prefs.edit().putString(KEY_LAST_URL, baseUrl).apply();
        Intent i = new Intent(this, DeckActivity.class);
        i.putExtra(DeckActivity.EXTRA_BASE_URL, baseUrl);
        if (pairingCode != null && !pairingCode.isEmpty()) i.putExtra(DeckActivity.EXTRA_ENROLL, pairingCode);
        startActivity(i);
        refreshLastHost();
    }

    // ---------------------------------------------------------------- discovery (DNS-SD)

    private void startDiscovery() {
        if (discovery != null) return;
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { main.post(() -> discovery = null); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> enqueueResolve(info)); }
            @Override public void onServiceLost(NsdServiceInfo info) { }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        }
        discovery = null;
        resolveQueue.clear();
    }

    // Before API 34 NsdManager resolves one service at a time.
    private void enqueueResolve(NsdServiceInfo info) {
        resolveQueue.add(info);
        resolveNext();
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo next = resolveQueue.poll();
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    main.post(() -> { resolving = false; resolveNext(); });
                }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    main.post(() -> { resolving = false; addFound(i); resolveNext(); });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void addFound(NsdServiceInfo info) {
        if (info.getHost() == null) return;
        String ip = info.getHost().getHostAddress();
        if (ip == null) return;
        if (ip.indexOf(':') >= 0) {
            int pct = ip.indexOf('%');
            if (pct >= 0) return; // link-local IPv6 with scope: not usable in a URL
            ip = "[" + ip + "]";
        }
        String url = "http://" + ip + ":" + info.getPort();
        String name = info.getServiceName();
        if (Build.VERSION.SDK_INT >= 21) {
            byte[] txtName = info.getAttributes().get("name");
            if (txtName != null) name = new String(txtName, StandardCharsets.UTF_8);
            byte[] ver = info.getAttributes().get("version");
            if (ver != null) name += "  ·  v" + new String(ver, StandardCharsets.UTF_8);
        }
        if (found.containsKey(url)) return;
        found.put(url, name);
        renderFound();
    }

    private void renderFound() {
        discoveredList.removeAllViews();
        for (Map.Entry<String, String> e : found.entrySet()) {
            final String url = e.getKey();
            Button b = button(e.getValue() + "\n" + display(url), false);
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(v -> probeAndOpen(listOf(url), code.getText().toString().trim(), false));
            discoveredList.addView(b, fullWidth(dp(6)));
        }
        discoveredEmpty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- UI

    private LinearLayout root;
    private LinearLayout lastBox;
    private TextView lastLabel;

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text(getString(R.string.app_name), 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text(getString(R.string.subtitle), 14, 0xFF9AA4AE);
        root.addView(sub, fullWidth(dp(4)));

        // Last host
        lastBox = card();
        lastLabel = text("", 14, Color.WHITE);
        lastBox.addView(lastLabel);
        Button reconnect = button(getString(R.string.reconnect_last), true);
        reconnect.setOnClickListener(v -> {
            String last = prefs.getString(KEY_LAST_URL, null);
            if (last != null) probeAndOpen(listOf(last), code.getText().toString().trim(), false);
        });
        lastBox.addView(reconnect, fullWidth(dp(8)));
        CheckBox auto = new CheckBox(this);
        auto.setText(R.string.auto_connect);
        auto.setTextColor(0xFFCED4DA);
        auto.setChecked(prefs.getBoolean(KEY_AUTO, true));
        auto.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean(KEY_AUTO, checked).apply());
        lastBox.addView(auto, fullWidth(dp(4)));
        root.addView(lastBox, fullWidth(dp(16)));

        // Discovered
        root.addView(header(getString(R.string.discovered)), fullWidth(dp(20)));
        discoveredList = new LinearLayout(this);
        discoveredList.setOrientation(LinearLayout.VERTICAL);
        root.addView(discoveredList, fullWidth(0));
        discoveredEmpty = text(getString(R.string.none_found), 13, 0xFF9AA4AE);
        root.addView(discoveredEmpty, fullWidth(dp(6)));

        // Manual
        root.addView(header(getString(R.string.manual)), fullWidth(dp(20)));
        address = input(getString(R.string.address_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(address, fullWidth(dp(8)));
        code = input(getString(R.string.code_hint), InputType.TYPE_CLASS_NUMBER);
        code.setImeOptions(EditorInfo.IME_ACTION_GO);
        code.setOnEditorActionListener((v, id, ev) -> { onConnectClicked(); return true; });
        root.addView(code, fullWidth(dp(8)));
        root.addView(text(getString(R.string.code_help), 12, 0xFF9AA4AE), fullWidth(dp(4)));

        connectButton = button(getString(R.string.connect), true);
        connectButton.setOnClickListener(v -> onConnectClicked());
        root.addView(connectButton, fullWidth(dp(12)));

        Button paste = button(getString(R.string.paste_link), false);
        paste.setOnClickListener(v -> pasteLink());
        root.addView(paste, fullWidth(dp(8)));

        status = text("", 14, 0xFFCED4DA);
        root.addView(status, fullWidth(dp(12)));
        openAnyway = button(getString(R.string.open_anyway), false);
        openAnyway.setVisibility(View.GONE);
        openAnyway.setOnClickListener(v -> {
            if (pendingUrl != null) openDeck(pendingUrl, (String) openAnyway.getTag());
        });
        root.addView(openAnyway, fullWidth(dp(8)));

        setContentView(scroll);
        refreshLastHost();
        renderFound();
    }

    private void refreshLastHost() {
        String last = prefs.getString(KEY_LAST_URL, null);
        lastBox.setVisibility(last == null ? View.GONE : View.VISIBLE);
        if (last != null) {
            lastLabel.setText(getString(R.string.last_host, display(last)));
            if (address.getText().length() == 0) address.setText(display(last));
        }
    }

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        if (t == null) return;
        String link = ConnectLink.extract(t.toString());
        if (link != null) useConnectLink(link);
        else address.setText(t.toString().trim());
    }

    private void setStatus(String s, boolean error) {
        status.setText(s);
        status.setTextColor(error ? 0xFFFF8A80 : 0xFFCED4DA);
    }

    private static String display(String url) {
        return url.replaceFirst("^http://", "");
    }

    private static List<String> listOf(String s) {
        List<String> l = new ArrayList<>();
        l.add(s);
        return l;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams fullWidth(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s.toUpperCase(), 12, 0xFF6EA8FE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        l.setBackground(rounded(0xFF23272B, 0));
        return l;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF6C757D);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(rounded(0xFF23272B, 0xFF3A4046));
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(rounded(primary ? 0xFF0D6EFD : 0xFF2B3035, primary ? 0 : 0xFF3A4046));
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(10));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
}
