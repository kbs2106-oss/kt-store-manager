package com.ktchilgeum.ledger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 충주 KT대리점 칠금점 개통장부 — 캡처 방지 안드로이드 앱.
 *
 * <p>웹 장부(https://kbs2106-oss.github.io/kt-store-manager/)를 WebView로 띄우되
 * FLAG_SECURE로 스크린샷·화면 녹화·최근 앱 미리보기를 차단한다.</p>
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://kbs2106-oss.github.io/kt-store-manager/";
    private static final String APP_HOST = "kbs2106-oss.github.io";
    private static final String APP_PATH = "/kt-store-manager";
    private static final String UA_SUFFIX = " KTChilgeumLedgerApp/1.0";
    private static final String BRIDGE_NAME = "KTChilgeumApp";
    private static final String SAVE_FOLDER = "KT칠금점개통장부";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;
    private static final long BACK_PRESS_WINDOW_MS = 2000L;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileCallback;
    private long lastBackPressedAt = 0L;

    private String pendingSaveName;
    private String pendingSaveMime;
    private byte[] pendingSaveBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 캡처·화면 녹화·최근 앱 미리보기 원천 차단 ──────────────────────────
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        getWindow().setStatusBarColor(Color.parseColor("#0F4C81"));
        getWindow().setNavigationBarColor(Color.WHITE);
        int uiFlags = getWindow().getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        root.addView(progressBar, progressParams);

        setContentView(root);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(APP_URL);
        }
    }

    // ──────────────────────────────────────────────────────────── WebView 설정

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + UA_SUFFIX);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new AppBridge(), BRIDGE_NAME);
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(new AppDownloadListener());
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        webView.setBackgroundColor(Color.WHITE);
    }

    /** window.print() / Blob 다운로드 / data: 다운로드 가로채기 스크립트. */
    private static final String BRIDGE_JS =
            "(function(){"
            + "if(window.__ktLedgerHooked){return;}window.__ktLedgerHooked=true;"
            + "var B=window." + BRIDGE_NAME + ";"
            + "var origPrint=window.print?window.print.bind(window):null;"
            + "try{window.print=function(){try{if(B&&B.printPage){B.printPage();return;}}catch(e){}"
            + "if(origPrint){try{origPrint();}catch(e){}}};}catch(e){}"
            + "var blobMap={};"
            + "try{var oc=URL.createObjectURL?URL.createObjectURL.bind(URL):null;"
            + "if(oc){URL.createObjectURL=function(o){var u=oc(o);try{if(o&&(o instanceof Blob)){blobMap[u]=o;}}catch(e){}return u;};}}catch(e){}"
            + "try{var orv=URL.revokeObjectURL?URL.revokeObjectURL.bind(URL):null;"
            + "if(orv){URL.revokeObjectURL=function(u){setTimeout(function(){try{orv(u);}catch(e){}try{delete blobMap[u];}catch(e){}},30000);};}}catch(e){}"
            + "function b64(blob,cb){try{var fr=new FileReader();"
            + "fr.onload=function(){var s=String(fr.result||'');var i=s.indexOf(',');cb(i<0?'':s.substring(i+1));};"
            + "fr.onerror=function(){cb('');};fr.readAsDataURL(blob);}catch(e){cb('');}}"
            + "function saveBlob(blob,name){if(!blob||!B||!B.saveFile)return;"
            + "var mime=blob.type||'application/octet-stream';"
            + "b64(blob,function(d){try{if(!d){if(B.toast)B.toast('파일을 읽지 못했습니다.');return;}"
            + "B.saveFile(name||'download',mime,d);}catch(e){}});}"
            + "function nameOf(a){var n=a.getAttribute('download');"
            + "if(!n){try{n=decodeURIComponent((a.getAttribute('href')||'').split('/').pop().split('?')[0]);}catch(e){n='';}}"
            + "return (n&&n!=='')?n:'download';}"
            + "var DOWNLOADABLE=/(json|csv|xlsx|xls|zip|txt|octet-stream|excel|ms-|pdf)/i;"
            + "document.addEventListener('click',function(ev){"
            + "var a=null;try{var el=ev.target;a=(el&&el.closest)?el.closest('a'):null;}catch(e){a=null;}"
            + "if(!a)return;"
            + "var href=a.getAttribute('href')||'';"
            + "var hasDl=a.hasAttribute?a.hasAttribute('download'):false;"
            + "var isBlob=href.indexOf('blob:')===0;"
            + "if(isBlob){var blob=blobMap[href];"
            + "var ok=hasDl||(a.target!=='_blank'&&blob&&DOWNLOADABLE.test(blob.type||''));"
            + "if(!ok)return;"
            + "ev.preventDefault();ev.stopPropagation();"
            + "if(blob){saveBlob(blob,nameOf(a));return;}"
            + "try{fetch(href).then(function(r){return r.blob();}).then(function(b){saveBlob(b,nameOf(a));})"
            + ".catch(function(){try{if(B&&B.toast)B.toast('파일을 저장하지 못했습니다.');}catch(e){}});}"
            + "catch(e){try{if(B&&B.toast)B.toast('파일을 저장하지 못했습니다.');}catch(e2){}}return;}"
            + "if(href.indexOf('data:')===0&&hasDl){ev.preventDefault();ev.stopPropagation();"
            + "try{var c=href.indexOf(',');var head=href.substring(5,c);var mime=(head.split(';')[0]||'application/octet-stream');"
            + "var body=href.substring(c+1);"
            + "if(B&&B.saveFile){if(head.indexOf('base64')>=0){B.saveFile(nameOf(a),mime,body);}"
            + "else{B.saveFile(nameOf(a),mime,btoa(unescape(encodeURIComponent(decodeURIComponent(body)))));}}}"
            + "catch(e){}return;}"
            + "},true);"
            + "})();";

    // ──────────────────────────────────────────────────────────── 내부 클래스들

    private class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request != null ? request.getUrl() : null);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(BRIDGE_JS, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                showOfflinePage();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            showOfflinePage();
        }
    }

    /** 인터넷이 끊겼을 때 크롬 오류 화면 대신 장부 톤의 안내 화면을 보여준다. */
    private void showOfflinePage() {
        String html = "<!DOCTYPE html><html lang='ko'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>연결 안 됨</title><style>"
                + "html,body{margin:0;height:100%;background:#f8fafc;color:#0f172a;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Malgun Gothic',sans-serif;}"
                + ".wrap{min-height:100%;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;gap:14px;padding:28px;text-align:center;}"
                + ".logo{width:64px;height:64px;border-radius:18px;background:#0F4C81;color:#fff;"
                + "font-weight:800;font-size:24px;display:flex;align-items:center;"
                + "justify-content:center;letter-spacing:.5px;}"
                + "h1{font-size:18px;margin:4px 0 0;}"
                + "p{margin:0;color:#475569;font-size:14px;line-height:1.55;}"
                + "a.retry{margin-top:10px;display:inline-block;padding:12px 22px;border-radius:999px;"
                + "background:#0F4C81;color:#fff;text-decoration:none;font-weight:700;font-size:15px;}"
                + ".hint{font-size:12px;color:#94a3b8;}"
                + "</style></head><body><div class='wrap'>"
                + "<div class='logo'>KT</div>"
                + "<h1>인터넷에 연결할 수 없습니다</h1>"
                + "<p>충주 KT대리점 칠금점 개통장부를 불러오지 못했습니다.<br>"
                + "Wi-Fi 또는 데이터 연결을 확인해 주세요.</p>"
                + "<a class='retry' href='" + APP_URL + "'>다시 시도</a>"
                + "<p class='hint'>충주 KT대리점 칠금점</p>"
                + "</div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private class AppChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = filePathCallback;

            Intent intent = null;
            try {
                intent = fileChooserParams.createIntent();
            } catch (Exception ignored) {
                intent = null;
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            try {
                startActivityForResult(Intent.createChooser(intent, "파일 선택"), REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                toast("파일을 고를 수 있는 앱을 찾지 못했습니다.");
                return false;
            }
            return true;
        }
    }

    private class AppDownloadListener implements DownloadListener {

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) {
                return;
            }
            if (url.startsWith("data:")) {
                handleDataUrl(url, mimeType);
                return;
            }
            if (url.startsWith("blob:")) {
                // blob은 페이지 안에서만 유효하므로 JS 가로채기(BRIDGE_JS)에서 처리한다.
                toast("파일 저장을 준비하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.");
                return;
            }
            try {
                String fileName = guessFileName(contentDisposition, url, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("User-Agent",
                        (userAgent == null || userAgent.isEmpty())
                                ? webView.getSettings().getUserAgentString() : userAgent);
                request.setMimeType(mimeType);
                request.setTitle(fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                    toast("다운로드를 시작했습니다: " + fileName);
                }
            } catch (Exception e) {
                openExternally(Uri.parse(url), Intent.ACTION_VIEW, "다운로드를 열 앱을 찾지 못했습니다.");
            }
        }
    }

    /** 웹 화면에서 호출하는 앱 기능(인쇄·파일 저장). */
    public class AppBridge {

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(MainActivity.this::doPrint);
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                if (base64 == null || base64.isEmpty()) {
                    throw new IllegalArgumentException("빈 파일");
                }
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                saveBytes(name, mime, bytes);
            } catch (Exception e) {
                toastAsync("파일을 저장하지 못했습니다: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void toast(String message) {
            toastAsync(message);
        }

        @JavascriptInterface
        public String appVersion() {
            return "1.0.0";
        }
    }

    // ──────────────────────────────────────────────────────────── 동작 구현

    /** 같은 사이트는 WebView 안에서, 그 밖의 링크·전화·메일은 안드로이드 앱으로 넘긴다. */
    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);

        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost();
            String path = uri.getPath();
            boolean inApp = APP_HOST.equalsIgnoreCase(host)
                    && (path == null || path.isEmpty() || path.startsWith(APP_PATH));
            if (inApp) {
                return false;
            }
            openExternally(uri, Intent.ACTION_VIEW, "링크를 열 앱을 찾지 못했습니다.");
            return true;
        }

        if ("tel".equals(scheme)) {
            openExternally(uri, Intent.ACTION_DIAL, "전화 앱을 찾지 못했습니다.");
            return true;
        }
        if ("mailto".equals(scheme)) {
            openExternally(uri, Intent.ACTION_SENDTO, "메일 앱을 찾지 못했습니다.");
            return true;
        }
        if ("sms".equals(scheme) || "smsto".equals(scheme) || "mms".equals(scheme)) {
            openExternally(uri, Intent.ACTION_VIEW, "문자 앱을 찾지 못했습니다.");
            return true;
        }
        if ("intent".equals(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception e) {
                toast("연결된 앱을 찾지 못했습니다.");
            }
            return true;
        }
        if ("blob".equals(scheme) || "data".equals(scheme) || "javascript".equals(scheme)) {
            return false;
        }
        // geo:, market:, kakaotalk: 등 그 밖의 앱 링크
        openExternally(uri, Intent.ACTION_VIEW, "연결된 앱을 찾지 못했습니다.");
        return true;
    }

    private void openExternally(Uri uri, String action, String failMessage) {
        try {
            Intent intent = new Intent(action, uri);
            if (!(Intent.ACTION_VIEW.equals(action))) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(failMessage);
        } catch (Exception e) {
            toast(failMessage);
        }
    }

    private void doPrint() {
        try {
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String jobName = "KT 칠금점 개통장부";
            if (printManager != null) {
                printManager.print(jobName, webView.createPrintDocumentAdapter(jobName),
                        new PrintAttributes.Builder().build());
            }
        } catch (Exception e) {
            toast("인쇄 화면을 열지 못했습니다.");
        }
    }

    private void handleDataUrl(String url, String fallbackMime) {
        try {
            int comma = url.indexOf(',');
            String head = url.substring(5, comma);
            String mime = head.split(";")[0];
            if (mime.isEmpty()) {
                mime = (fallbackMime == null || fallbackMime.isEmpty())
                        ? "application/octet-stream" : fallbackMime;
            }
            String body = url.substring(comma + 1);
            byte[] bytes;
            if (head.contains("base64")) {
                bytes = Base64.decode(body, Base64.DEFAULT);
            } else {
                bytes = Uri.decode(body).getBytes("UTF-8");
            }
            saveBytes("download_" + timestamp() + extensionFor(mime), mime, bytes);
        } catch (Exception e) {
            toast("파일을 저장하지 못했습니다.");
        }
    }

    // ────────────────────────────────────────────────── 파일 저장 (Downloads)

    private void saveBytes(String name, String mime, byte[] bytes) {
        final String safeName = sanitizeFileName(
                (name == null || name.trim().isEmpty()) ? "download_" + timestamp() : name);
        final String safeMime = (mime == null || mime.trim().isEmpty())
                ? "application/octet-stream" : mime.trim();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, safeMime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri target = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) {
                    toastAsync("저장 위치를 만들지 못했습니다.");
                    return;
                }
                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) {
                        throw new IllegalStateException("저장 통로를 열지 못했습니다.");
                    }
                    out.write(bytes);
                    out.flush();
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, values, null, null);
                toastAsync("저장했습니다: 다운로드/" + SAVE_FOLDER + "/" + safeName);
            } catch (Exception e) {
                toastAsync("파일을 저장하지 못했습니다: " + e.getMessage());
            }
            return;
        }

        // 안드로이드 9 이하: 공용 다운로드 폴더에 저장(권한 필요). 권한이 없으면 한 번 요청한다.
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSaveName = safeName;
            pendingSaveMime = safeMime;
            pendingSaveBytes = bytes;
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
            return;
        }
        writeToPublicDownloads(safeName, bytes);
    }

    private void writeToPublicDownloads(String safeName, byte[] bytes) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SAVE_FOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("폴더를 만들지 못했습니다.");
            }
            File target = new File(dir, safeName);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
                out.flush();
            }
            toastAsync("저장했습니다: 다운로드/" + SAVE_FOLDER + "/" + safeName);
        } catch (Exception e) {
            // 권한·경로 문제가 있으면 앱 전용 폴더에 저장한다.
            try {
                File fallbackDir = getExternalFilesDir(null);
                if (fallbackDir == null) {
                    toastAsync("파일을 저장하지 못했습니다.");
                    return;
                }
                File target = new File(fallbackDir, safeName);
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(bytes);
                    out.flush();
                }
                toastAsync("저장했습니다: " + target.getAbsolutePath());
            } catch (Exception e2) {
                toastAsync("파일을 저장하지 못했습니다.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingSaveBytes != null && pendingSaveName != null) {
            if (granted) {
                writeToPublicDownloads(pendingSaveName, pendingSaveBytes);
            } else {
                try {
                    File fallbackDir = getExternalFilesDir(null);
                    if (fallbackDir != null) {
                        File target = new File(fallbackDir, pendingSaveName);
                        try (FileOutputStream out = new FileOutputStream(target)) {
                            out.write(pendingSaveBytes);
                            out.flush();
                        }
                        toastAsync("저장했습니다: " + target.getAbsolutePath());
                    } else {
                        toastAsync("저장 권한이 없어 파일을 저장하지 못했습니다.");
                    }
                } catch (Exception e) {
                    toastAsync("파일을 저장하지 못했습니다.");
                }
            }
        }
        pendingSaveName = null;
        pendingSaveMime = null;
        pendingSaveBytes = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    List<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                    results = uris.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ──────────────────────────────────────────────────────── 생명주기·뒤로가기

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < BACK_PRESS_WINDOW_MS) {
            super.onBackPressed();
            return;
        }
        lastBackPressedAt = now;
        toast("'뒤로'를 한 번 더 누르면 종료됩니다.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface(BRIDGE_NAME);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ──────────────────────────────────────────────────────────────── 유틸리티

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toastAsync(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
    }

    private String sanitizeFileName(String raw) {
        String name = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (name.isEmpty()) {
            name = "download_" + timestamp();
        }
        if (name.length() > 90) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 8) {
                ext = name.substring(dot);
                name = name.substring(0, dot);
            }
            name = name.substring(0, Math.max(1, 90 - ext.length())) + ext;
        }
        return name;
    }

    private String guessFileName(String contentDisposition, String url, String mimeType) {
        String name = null;
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("filename*=")) {
                    String value = trimmed.substring("filename*=".length()).trim();
                    int quote = value.indexOf("''");
                    if (quote >= 0) {
                        value = value.substring(quote + 2);
                    }
                    try {
                        name = Uri.decode(value);
                    } catch (Exception ignored) {
                        name = value;
                    }
                    break;
                }
                if (trimmed.toLowerCase(Locale.US).startsWith("filename=")) {
                    name = trimmed.substring("filename=".length()).trim();
                    if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) {
                        name = name.substring(1, name.length() - 1);
                    }
                }
            }
        }
        if (name == null || name.isEmpty()) {
            String last = Uri.parse(url).getLastPathSegment();
            if (last != null && !last.isEmpty()) {
                name = last;
            }
        }
        if (name == null || name.isEmpty()) {
            name = "download_" + timestamp() + extensionFor(mimeType);
        }
        if (!name.contains(".")) {
            name = name + extensionFor(mimeType);
        }
        return sanitizeFileName(name);
    }

    private String extensionFor(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        switch (mimeType.toLowerCase(Locale.US)) {
            case "application/json":
                return ".json";
            case "text/csv":
                return ".csv";
            case "text/plain":
                return ".txt";
            case "application/zip":
                return ".zip";
            case "application/pdf":
                return ".pdf";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
                return ".xlsx";
            case "application/vnd.ms-excel":
                return ".xls";
            default:
                return "";
        }
    }
}
