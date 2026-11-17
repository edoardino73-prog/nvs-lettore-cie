package it.nvs.lettorecie.pc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Servizio locale: NVS (nel browser di questo PC) chiede la lettura della carta a questo programma.
 * Ascolta SOLO su questo computer (127.0.0.1) e risponde solo ai siti autorizzati: quelli dell'elenco e, per ogni altro sito,
 * solo dopo che l'operatore ha premuto «Consenti» in una finestra di questo programma.
 * Il CAN arriva dalla pagina di NVS, serve per la lettura e non viene salvato. Nulla esce dal computer.
 */
final class Servizio {
    private Servizio() { }

    static final int PORTA = 8765;
    private static final String[] PREDEFINITI = { "https://tablet-nuovavillasilvana.pages.dev" };

    private static final Set<String> consentiti = new LinkedHashSet<>();
    private static final Map<String, Long> rifiutati = new HashMap<>();

    private static Path fileOrigini() {
        String base = System.getenv("APPDATA");
        Path dir = Paths.get(base != null ? base : System.getProperty("user.home"), "NVS Lettore CIE");
        return dir.resolve("siti_autorizzati.txt");
    }

    static void avvia() {
        try {
            for (String o : PREDEFINITI) consentiti.add(o);
            try { for (String r : Files.readAllLines(fileOrigini(), StandardCharsets.UTF_8)) { r = r.trim(); if (r.startsWith("https://")) consentiti.add(r); } } catch (Exception nessunFile) { }
            HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORTA), 0);
            srv.createContext("/api/", Servizio::gestisci);
            srv.setExecutor(Executors.newCachedThreadPool());
            srv.start();
            Main.collegamento("Collegamento con NVS: ATTIVO (porta " + PORTA + "). Lascia aperto questo programma e usa «Importa da CIE» in NVS.", false);
        } catch (Throwable e) {
            Main.collegamento("Collegamento con NVS NON attivo (" + e.getMessage() + "): forse il programma e' gia' aperto. Funziona la lettura con gli appunti.", true);
        }
    }

    private static void rispondi(HttpExchange ex, int codice, String origin, String testo) throws IOException {
        byte[] b = testo == null ? new byte[0] : testo.getBytes(StandardCharsets.UTF_8);
        if (origin != null) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
            ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (b.length == 0) { ex.sendResponseHeaders(codice, -1); }
        else { ex.sendResponseHeaders(codice, b.length); try (OutputStream os = ex.getResponseBody()) { os.write(b); } }
    }

    /** Il sito e' autorizzato? Se non e' noto, lo chiede all'operatore con una finestra su questo PC. */
    private static boolean autorizzato(String origin) {
        synchronized (consentiti) {
            if (consentiti.contains(origin)) return true;
            Long quando = rifiutati.get(origin);
            if (quando != null && System.currentTimeMillis() - quando < 10 * 60 * 1000L) return false;
        }
        if (!origin.startsWith("https://")) return false;
        final CompletableFuture<Integer> scelta = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            Object[] opz = { "Consenti sempre", "Solo questa volta", "Rifiuta" };
            int r = JOptionPane.showOptionDialog(null,
                    "Il sito\n\n    " + origin + "\n\nvuole usare il lettore della carta d'identità di questo computer.\n\nConsenti solo se è il sito di NVS della tua struttura.",
                    "NVS Lettore CIE - richiesta di accesso", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opz, opz[2]);
            scelta.complete(r);
        });
        int r;
        try { r = scelta.get(40, TimeUnit.SECONDS); } catch (Exception e) { r = 2; }
        synchronized (consentiti) {
            if (r == 0) {
                consentiti.add(origin);
                try {
                    Path f = fileOrigini();
                    Files.createDirectories(f.getParent());
                    Files.write(f, (origin + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ignorata) { }
                return true;
            }
            if (r == 1) return true;
            rifiutati.put(origin, System.currentTimeMillis());
            return false;
        }
    }

    private static String corpo(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int n, tot = 0;
            while ((n = in.read(buf)) > 0 && tot < 4096) { out.write(buf, 0, n); tot += n; }
            return out.toString("UTF-8");
        }
    }

    private static void gestisci(HttpExchange ex) throws IOException {
        try {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String percorso = ex.getRequestURI().getPath();
            if (origin == null || !autorizzato(origin)) { rispondi(ex, 403, null, "{\"errore\":\"sito non autorizzato\"}"); return; }
            String metodo = ex.getRequestMethod();
            if ("OPTIONS".equals(metodo)) { rispondi(ex, 204, origin, null); return; }

            if ("/api/stato".equals(percorso)) {
                List<String> l = Main.nomiLettori();
                JSONObject o = new JSONObject();
                o.put("programma", "NVS Lettore CIE PC");
                o.put("lettori", new JSONArray(l));
                o.put("lettore_collegato", !l.isEmpty());
                rispondi(ex, 200, origin, o.toString());
                return;
            }
            if ("/api/leggi-cie".equals(percorso) && "POST".equals(metodo)) {
                String can;
                try { can = new JSONObject(corpo(ex)).optString("can", "").replaceAll("\\D", ""); } catch (Exception e) { can = ""; }
                JSONObject o;
                if (can.length() != 6) {
                    o = new JSONObject().put("errore", "Il CAN sono 6 cifre.");
                } else {
                    Main.collegamento("Lettura richiesta da NVS (" + origin + "): appoggia la carta sul lettore.", false);
                    try {
                        String json = Main.leggiCarta(can, 30000);
                        Main.stato("Letto (richiesta da NVS).", false);
                        rispondi(ex, 200, origin, json);
                        return;
                    } catch (Throwable e) {
                        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        Main.stato("Non riuscito: " + m, true);
                        o = new JSONObject().put("errore", m);
                    } finally {
                        Main.collegamento("Collegamento con NVS: ATTIVO (porta " + PORTA + ").", false);
                    }
                }
                rispondi(ex, 200, origin, o.toString());
                return;
            }
            rispondi(ex, 404, origin, "{\"errore\":\"non trovato\"}");
        } catch (Throwable e) {
            try { rispondi(ex, 500, null, "{\"errore\":\"errore interno\"}"); } catch (Throwable ignorata) { }
        } finally {
            ex.close();
        }
    }
}
