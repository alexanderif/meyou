package ir.maryafox.irupload;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // آدرس پنل شما
    static final String START_URL = "https://maryafox.ir:100/";

    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_NOTIF = 4;

    private static final String JS_COLOR =
            "(function(){try{var v=getComputedStyle(document.documentElement)"
            + ".getPropertyValue('--background').trim();if(!v)return '';"
            + "var d=document.createElement('div');d.style.color='hsl('+v+')';"
            + "document.body.appendChild(d);var c=getComputedStyle(d).color;d.remove();return c;}"
            + "catch(e){return ''}})()";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private SwipeRefreshLayout swipe;
    private ImageButton fab;
    private ValueCallback<Uri[]> filePathCallback;
    private String[] pendingDownload;
    private String camPath;
    private boolean firstLoaded = false;
    private boolean errorShown = false;
    private int barColor = 0xFFFDF2F3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splash.setKeepOnScreenCondition(() -> !firstLoaded);
        handler.postDelayed(() -> firstLoaded = true, 3000); // سقف زمان splash
        splash.setOnExitAnimationListener(p -> {
            View v = p.getView();
            v.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(380)
                    .withEndAction(p::remove).start();
        });

        if (savedInstanceState != null) camPath = savedInstanceState.getString("camPath");

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(0xFFE60F23);
        swipe.addView(web);
        swipe.setOnRefreshListener(this::refresh);
        swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0);
        root.addView(swipe, new FrameLayout.LayoutParams(-1, -1));

        fab = new ImageButton(this);
        fab.setImageResource(R.drawable.ic_camera);
        fab.setBackgroundResource(R.drawable.fab_bg);
        fab.setElevation(dp(8));
        int pad = dp(14);
        fab.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(18), 0, 0, dp(22));
        root.addView(fab, lp);
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.setOnClickListener(v -> openCamera());
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFFFDF2F3);
        web.setAlpha(0f);

        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = Uri.parse(START_URL).getHost();
                if (host != null && host.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                if (!url.startsWith("data:")) errorShown = false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                swipe.setRefreshing(false);
                web.animate().alpha(1f).setDuration(350).start();
                firstLoaded = true;
                showFab();
                syncBarColor();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errorShown = true;
                    String html = "<html><body style='font-family:sans-serif;text-align:center;"
                            + "padding-top:28vh;direction:rtl;background:#FDF2F3;color:#3b0a10'>"
                            + "<h3>اتصال به سرور برقرار نشد</h3>"
                            + "<p>اینترنت یا آدرس پنل را بررسی کنید.<br>برای تلاش مجدد صفحه را به پایین بکشید.</p>"
                            + "</body></html>";
                    v.loadDataWithBaseURL(START_URL, html, "text/html", "UTF-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "انتخاب فایل ممکن نشد", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = new String[]{url, ua, cd, mime};
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
            startDownload(url, ua, cd, mime);
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
        handleShare(getIntent());

        // هماهنگ‌سازی رنگ نوار بالا با تم پنل (تم عوض شود، رنگ هم عوض می‌شود)
        handler.postDelayed(new Runnable() {
            @Override public void run() { syncBarColor(); handler.postDelayed(this, 1500); }
        }, 1500);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void showFab() {
        if (fab.getScaleX() == 1f) return;
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(300).setDuration(450)
                .setInterpolator(new OvershootInterpolator(2f)).start();
    }

    private void refresh() {
        if (errorShown) web.loadUrl(START_URL); else web.reload();
        handler.postDelayed(() -> swipe.setRefreshing(false), 6000);
    }

    // ---------- رنگ نوار بالا/پایین مطابق تم ----------
    private void syncBarColor() {
        web.evaluateJavascript(JS_COLOR, val -> {
            if (val == null) return;
            String c = val.replace("\"", "");
            if (!c.startsWith("rgb")) return;
            try {
                String[] p = c.substring(c.indexOf('(') + 1, c.indexOf(')')).split("[ ,/]+");
                int color = Color.rgb(Integer.parseInt(p[0].trim()),
                        (int) Float.parseFloat(p[1].trim()), (int) Float.parseFloat(p[2].trim()));
                applyBarColor(color);
            } catch (Exception ignored) { }
        });
    }

    private void applyBarColor(int to) {
        if (to == barColor) return;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), barColor, to);
        a.setDuration(300);
        a.addUpdateListener(v -> {
            int col = (int) v.getAnimatedValue();
            getWindow().setStatusBarColor(col);
            getWindow().setNavigationBarColor(col);
        });
        a.start();
        barColor = to;
        boolean light = Color.luminance(to) > 0.5f;
        var c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
        swipe.setProgressBackgroundColorSchemeColor(to);
    }

    // ---------- دوربین ----------
    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "cam");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "IMG_" + stamp + ".jpg");
            camPath = f.getAbsolutePath();
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fp", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.setClipData(ClipData.newRawUri("o", out));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "دوربین در دسترس نیست", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- اشتراک‌گذاری مستقیم ----------
    @SuppressWarnings("deprecation")
    private void handleShare(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) {
            startUpload(uris);
            in.setAction(Intent.ACTION_MAIN); // جلوگیری از آپلود دوباره
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private void startUpload(List<Uri> uris) {
        ClipData clip = ClipData.newRawUri("files", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        Intent s = new Intent(this, UploadService.class);
        s.setClipData(clip);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(s);
        Toast.makeText(this, uris.size() + " فایل در حال آپلود در پس‌زمینه…", Toast.LENGTH_SHORT).show();
    }

    // ---------- دانلود ----------
    private void startDownload(String url, String ua, String cd, String mime) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.addRequestHeader("User-Agent", ua);
            String name = URLUtil.guessFileName(url, cd, mime);
            req.setMimeType(mime);
            req.setTitle(name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "دانلود شروع شد: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "دانلود ناموفق بود", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE && pendingDownload != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload(pendingDownload[0], pendingDownload[1],
                        pendingDownload[2], pendingDownload[3]);
            }
            pendingDownload = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result, data));
            filePathCallback = null;
        } else if (code == REQ_CAMERA && result == Activity.RESULT_OK && camPath != null) {
            File f = new File(camPath);
            if (f.exists() && f.length() > 0) {
                List<Uri> l = new ArrayList<>();
                l.add(FileProvider.getUriForFile(this, getPackageName() + ".fp", f));
                startUpload(l);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        out.putString("camPath", camPath);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UploadService.onFinished = () -> { if (web != null) web.reload(); };
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
