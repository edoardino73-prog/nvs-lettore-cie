package it.nvs.tablet;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * NVS Tablet: mostra il sito del tablet NVS a tutto schermo (modalita' chiosco) e, in piu', legge la CIE via NFC
 * (stessa lettura dell'app «NVS Lettore CIE») consegnando i dati direttamente alla pagina di anagrafica.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final int RC_FILE = 11, RC_CAM = 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout radice;
    private WebView web;
    private ImageView splash;
    private View overlay;
    private TextView overlayTesto;
    private NfcAdapter nfc;

    private volatile String hostCorrente = "";
    private volatile String canInAttesa = null;
    private volatile boolean occupato = false;
    private boolean offline = false;
    private boolean chioscoTentato = false;
    private boolean cameraChiesta = false;

    private ValueCallback<Uri[]> fileCb;
    private File fotoFile;
    private PermissionRequest permPendente;

    private int tastiVolume = 0;
    private long primoTasto = 0;

    private final Runnable timeoutLettura = new Runnable() {
        @Override public void run() {
            if (canInAttesa != null) { fineLettura(); js("window.NVSCieNativo&&NVSCieNativo.errore('Tempo scaduto: la carta non e\\' stata letta. Riprova.')"); }
        }
    };

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        nfc = NfcAdapter.getDefaultAdapter(this);

        radice = new FrameLayout(this);
        radice.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        radice.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configuraWeb();

        splash = new ImageView(this);
        splash.setImageResource(R.drawable.logo);
        splash.setBackgroundColor(Color.WHITE);
        splash.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        splash.setPadding(80, 80, 80, 80);
        radice.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        creaOverlay();
        setContentView(radice);

        if (b != null) web.restoreState(b); else web.loadUrl(Impostazioni.URL_SITO);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        schermoIntero();
        if (!cameraChiesta && Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraChiesta = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, RC_CAM);
        }
        chiosco();
    }

    @Override
    protected void onPause() {
        super.onPause();
        fineLettura();
        web.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) { radice.removeView(web); web.destroy(); }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) schermoIntero();
    }

    @SuppressWarnings("deprecation")
    private void schermoIntero() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ pagina web

    private static boolean hostOk(String host) {
        if (host == null) return false;
        for (String s : Impostazioni.SITI_CONSENTITI) if (s.equalsIgnoreCase(host)) return true;
        return false;
    }

    private boolean fidato() { return hostOk(hostCorrente); }

    private void configuraWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " NVSTabletApp/1.0");
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Ponte(), "NVSApp");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return gestisciUrl(r.getUrl()); }

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                try { hostCorrente = Uri.parse(url).getHost(); } catch (Throwable e) { hostCorrente = ""; }
                if (hostOk(hostCorrente)) offline = false;
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (hostOk(hostCorrente)) js("(function(){window.print=function(){NVSApp.stampa();};})();");
                nascondiSplash();
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) mostraOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() { @Override public void run() { gestisciPermesso(r); } });
            }

            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                return scegliFile(cb, p);
            }
        });

        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try {
                    if (!url.startsWith("http") || !hostOk(Uri.parse(url).getHost())) return;
                    android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse(url));
                    rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    rq.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, cd, mime));
                    ((android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                    Toast.makeText(MainActivity.this, "Scaricamento avviato (cartella Download)", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) { Toast.makeText(MainActivity.this, "Scaricamento non riuscito", Toast.LENGTH_SHORT).show(); }
            }
        });
    }

    private boolean gestisciUrl(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (sc.equals("http") || sc.equals("https")) {
            if (hostOk(u.getHost())) return false;
            Toast.makeText(this, "Collegamento esterno non aperto: " + u.getHost(), Toast.LENGTH_LONG).show();
            return true;
        }
        if (sc.equals("tel") || sc.equals("mailto") || sc.equals("sms") || sc.equals("whatsapp")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable e) { }
            return true;
        }
        return false; // blob:, data:, about:, javascript:
    }

    private void js(final String codice) {
        runOnUiThread(new Runnable() { @Override public void run() { if (web != null) web.evaluateJavascript(codice, null); } });
    }

    private void nascondiSplash() {
        if (splash != null && splash.getParent() != null) radice.removeView(splash);
    }

    private void mostraOffline() {
        offline = true;
        nascondiSplash();
        String html = "<html><body style='font-family:sans-serif;text-align:center;padding-top:18vh;color:#0f172a'>"
                + "<h2>Nessuna connessione</h2><p>Il tablet non raggiunge il server NVS.<br>Riprovo da solo ogni pochi secondi.</p>"
                + "<button style='font-size:1.3em;padding:14px 28px' onclick='NVSApp.ricarica()'>Riprova ora</button></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        h.postDelayed(new Runnable() {
            @Override public void run() { if (offline) web.loadUrl(Impostazioni.URL_SITO); }
        }, 12000);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        // altrimenti non fa nulla: in modalita' chiosco non si esce con il tasto indietro
    }

    // ------------------------------------------------------------------ permessi, file, foto

    private void gestisciPermesso(PermissionRequest r) {
        boolean ok = r.getOrigin() != null && hostOk(r.getOrigin().getHost());
        boolean video = false, altro = false;
        for (String x : r.getResources()) { if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)) video = true; else altro = true; }
        if (!ok || !video || altro) { r.deny(); return; }
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            permPendente = r;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, RC_CAM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == RC_CAM && permPendente != null) {
            PermissionRequest r = permPendente; permPendente = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); else r.deny();
        }
    }

    private boolean scegliFile(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = cb;
        fotoFile = null;
        try {
            Intent scelta = p.createIntent();
            List<Intent> extra = new ArrayList<>();
            String[] tipi = p.getAcceptTypes();
            boolean immagini = tipi == null || tipi.length == 0;
            if (tipi != null) for (String t : tipi) if (t == null || t.isEmpty() || t.startsWith("image")) immagini = true;
            if (immagini) {
                try {
                    File dir = new File(getCacheDir(), "foto");
                    dir.mkdirs();
                    fotoFile = File.createTempFile("foto_", ".jpg", dir);
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fp", fotoFile);
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    extra.add(cam);
                } catch (Throwable e) { fotoFile = null; }
            }
            Intent ch = Intent.createChooser(scelta, "Scegli");
            if (!extra.isEmpty()) ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toArray(new Intent[0]));
            startActivityForResult(ch, RC_FILE);
            return true;
        } catch (Throwable e) {
            fileCb = null;
            cb.onReceiveValue(null);
            return false;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != RC_FILE || fileCb == null) return;
        Uri[] r = null;
        if (result == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) r = WebChromeClient.FileChooserParams.parseResult(result, data);
            else if (fotoFile != null && fotoFile.length() > 0) r = new Uri[]{Uri.fromFile(fotoFile)};
        }
        fileCb.onReceiveValue(r);
        fileCb = null;
    }

    // ------------------------------------------------------------------ chiosco

    private void chiosco() {
        if (!Impostazioni.CHIOSCO || chioscoTentato) return;
        chioscoTentato = true;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(new ComponentName(this, AdminReceiver.class), new String[]{getPackageName()});
            }
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask();
        } catch (Throwable e) { /* senza blocco il resto funziona comunque */ }
    }

    /** Uscita dal chiosco: 7 pressioni di «volume giu'» entro 8 secondi, poi il codice. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Impostazioni.CHIOSCO) {
            long ora = System.currentTimeMillis();
            if (ora - primoTasto > 8000) { primoTasto = ora; tastiVolume = 0; }
            if (++tastiVolume >= 7) { tastiVolume = 0; chiediCodiceUscita(); return true; }
        }
        return super.onKeyDown(keyCode, ev);
    }

    private void chiediCodiceUscita() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setHint("Codice");
        new AlertDialog.Builder(this).setTitle("Uscire dalla modalità chiosco?").setView(in)
                .setPositiveButton("Esci", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (Impostazioni.CODICE_USCITA.equals(in.getText().toString().trim())) {
                            try { stopLockTask(); } catch (Throwable e) { }
                            finishAndRemoveTask();
                        } else Toast.makeText(MainActivity.this, "Codice errato", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Annulla", null).show();
    }

    // ------------------------------------------------------------------ lettura CIE (NFC)

    private void creaOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE6102A43);
        box.setPadding(60, 60, 60, 60);
        overlayTesto = new TextView(this);
        overlayTesto.setTextColor(Color.WHITE);
        overlayTesto.setTextSize(22);
        overlayTesto.setGravity(Gravity.CENTER);
        overlayTesto.setText("Appoggia la CIE sul retro del tablet\ne tienila ferma finché compare il risultato…");
        Button annulla = new Button(this);
        annulla.setText("Annulla");
        annulla.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fineLettura(); }
        });
        box.addView(overlayTesto);
        box.addView(annulla, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setClickable(true);
        box.setVisibility(View.GONE);
        overlay = box;
        radice.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void avviaLettura(String can) {
        if (can == null || !can.matches("\\d{6}")) { js("window.NVSCieNativo&&NVSCieNativo.errore('Il CAN sono 6 cifre.')"); return; }
        if (nfc == null) { js("window.NVSCieNativo&&NVSCieNativo.errore('Questo tablet non ha l\\'NFC.')"); return; }
        if (!nfc.isEnabled()) { js("window.NVSCieNativo&&NVSCieNativo.errore('L\\'NFC è spento: accendilo dalle impostazioni del tablet.')"); return; }
        canInAttesa = can;
        overlay.setVisibility(View.VISIBLE);
        try {
            nfc.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        } catch (Throwable e) { fineLettura(); js("window.NVSCieNativo&&NVSCieNativo.errore('NFC non utilizzabile.')"); return; }
        h.removeCallbacks(timeoutLettura);
        h.postDelayed(timeoutLettura, 60000);
    }

    private void fineLettura() {
        canInAttesa = null;
        h.removeCallbacks(timeoutLettura);
        if (overlay != null) overlay.setVisibility(View.GONE);
        try { if (nfc != null) nfc.disableReaderMode(this); } catch (Throwable e) { }
    }

    /** Chiamata (fuori dal thread dello schermo) quando la carta viene appoggiata. */
    @Override
    public void onTagDiscovered(final Tag tag) {
        final String can = canInAttesa;
        if (can == null || occupato) return;
        occupato = true;
        runOnUiThread(new Runnable() { @Override public void run() { overlayTesto.setText("Lettura in corso: non muovere la carta…"); } });
        try {
            final String json = CieReader.leggi(tag, can);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    fineLettura();
                    try { Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE); if (v != null) v.vibrate(200); } catch (Throwable e) { }
                }
            });
            js("window.NVSCieNativo&&NVSCieNativo.esito(" + JSONObject.quote(json) + ")");
        } catch (Throwable e) {
            final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            runOnUiThread(new Runnable() { @Override public void run() { fineLettura(); } });
            js("window.NVSCieNativo&&NVSCieNativo.errore(" + JSONObject.quote(m) + ")");
        } finally {
            occupato = false;
        }
    }

    // ------------------------------------------------------------------ ponte verso la pagina (oggetto «NVSApp»)

    private class Ponte {
        /** 0 = non disponibile, 1 = NFC spento, 2 = pronto. */
        @JavascriptInterface public int nfcStato() {
            if (!fidato() || nfc == null) return 0;
            return nfc.isEnabled() ? 2 : 1;
        }

        @JavascriptInterface public void leggiCIE(final String can) {
            if (!fidato()) return;
            runOnUiThread(new Runnable() { @Override public void run() { avviaLettura(can); } });
        }

        @JavascriptInterface public void annullaCIE() {
            if (!fidato()) return;
            runOnUiThread(new Runnable() { @Override public void run() { fineLettura(); } });
        }

        @JavascriptInterface public void stampa() {
            if (!fidato()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        String nome = "NVS_" + System.currentTimeMillis();
                        pm.print(nome, web.createPrintDocumentAdapter(nome), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                    } catch (Throwable e) { Toast.makeText(MainActivity.this, "Stampa non disponibile", Toast.LENGTH_LONG).show(); }
                }
            });
        }

        @JavascriptInterface public void ricarica() {
            runOnUiThread(new Runnable() { @Override public void run() { offline = false; web.loadUrl(Impostazioni.URL_SITO); } });
        }
    }
}
