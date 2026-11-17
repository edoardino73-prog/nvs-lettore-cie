package it.nvs.lettorecie;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import net.sf.scuba.smartcards.CardService;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jmrtd.PACEKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.MRZInfo;
import org.json.JSONObject;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Legge la parte "documento" (MRTD) del chip della CIE con il protocollo PACE e il CAN,
 * poi legge il file DG1 (i dati della zona a lettura ottica). Non usa il PIN della carta.
 * Restituisce un testo JSON nel formato "nvs-cie/1", che NVS importa nell'anagrafica.
 *
 * NON COLLAUDATO su una carta vera: vedi LEGGIMI.md.
 */
public class CieReader {

    private static final Pattern CF = Pattern.compile("[A-Z]{6}[0-9LMNPQRSTUV]{2}[A-EHLMPR-T][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]");

    static {
        // Il provider "BC" incluso in Android e' incompleto: si sostituisce con quello della libreria.
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /** Fase in corso: se qualcosa va storto, il messaggio dice dove (utile per capire il problema). */
    private static String fase = "";

    private static String dettaglio(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Throwable t = e; t != null && n < 4; t = t.getCause(), n++) {
            if (n > 0) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    public static String leggi(Tag tag, String can) throws Exception {
        try {
            return leggiInterno(tag, can);
        } catch (Throwable e) { // anche gli Error (classe mancante, metodo non trovato...) che altrimenti chiudono l'app
            StringBuilder st = new StringBuilder();
            StackTraceElement[] el = e.getStackTrace();
            for (int i = 0; i < el.length && i < 6; i++) st.append("\n  ").append(el[i].getClassName().replaceAll("^.*\\.", "")).append(".").append(el[i].getMethodName()).append(":").append(el[i].getLineNumber());
            throw new Exception("[" + fase + "] " + dettaglio(e) + st + "\n\nScambi con la carta:\n" + DiagCardService.ultimi());
        }
    }

    private static String leggiInterno(Tag tag, String can) throws Exception {
        fase = "apertura della carta";
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) throw new Exception("La carta non e' una carta con chip di tipo atteso (ISO-DEP).");
        isoDep.setTimeout(20000);

        CardService cardService = new DiagCardService(CardService.getInstance(isoDep));
        cardService.open();
        PassportService service = new PassportService(cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false);
        service.open();

        // 1) elenco dei protocolli supportati dalla carta (EF.CardAccess). Se non si legge subito, si seleziona prima l'applicazione documento.
        CardAccessFile cardAccess = null;
        fase = "lettura EF.CardAccess";
        try {
            cardAccess = new CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE));
        } catch (Exception primo) {
            fase = "selezione applicazione documento e nuova lettura EF.CardAccess";
            service.sendSelectApplet(false);
            cardAccess = new CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE));
        }

        // 2) PACE con il CAN
        fase = "PACE con il CAN";
        PACEInfo paceInfo = null;
        for (SecurityInfo info : cardAccess.getSecurityInfos()) {
            if (info instanceof PACEInfo) { paceInfo = (PACEInfo) info; break; }
        }
        if (paceInfo == null) throw new Exception("la carta non dichiara il protocollo PACE");
        service.doPACE(PACEKeySpec.createCANKey(can), paceInfo.getObjectIdentifier(),
                PACEInfo.toParameterSpec(paceInfo.getParameterId()), paceInfo.getParameterId());

        fase = "selezione applicazione documento dopo PACE";
        service.sendSelectApplet(true);
        fase = "lettura dei dati (DG1)";
        DG1File dg1 = new DG1File(service.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE));
        MRZInfo mrz = dg1.getMRZInfo();

        // Dati in piu': ogni file e' facoltativo, se manca o non si legge l'importazione prosegue con i dati base.
        JSONObject chip = new JSONObject();
        JSONArray presenti = new JSONArray();
        JSONArray letti = new JSONArray();
        JSONObject note = new JSONObject();
        letti.put("DG1");

        fase = "elenco dei file del chip (EF.COM)";
        try {
            byte[] com = leggiFile(service, PassportService.EF_COM);
            for (int t : tagElenco(com)) { presenti.put("DG" + t); }
            chip.put("ef_com_byte", com.length);
        } catch (Throwable e) { note.put("EF.COM", dettaglio(e)); }

        fase = "lettura DG11 (dati personali)";
        JSONObject d11 = null;
        try {
            byte[] b = leggiFile(service, PassportService.EF_DG11);
            letti.put("DG11");
            d11 = new JSONObject();
            d11.put("grezzo", ascii(b));
            Object f = new org.jmrtd.lds.icao.DG11File(new ByteArrayInputStream(b));
            d11.put("nome_completo", testo(f, "getNameOfHolder"));
            d11.put("altri_nomi", testo(f, "getOtherNames"));
            d11.put("codice_personale", testo(f, "getPersonalNumber"));
            d11.put("data_nascita_completa", testo(f, "getFullDateOfBirth"));
            d11.put("luogo_nascita", testo(f, "getPlaceOfBirth"));
            d11.put("luogo_nascita_parti", new JSONArray(parti(chiama(f, "getPlaceOfBirth"))));
            d11.put("residenza", testo(f, "getPermanentAddress"));
            d11.put("residenza_parti", new JSONArray(parti(chiama(f, "getPermanentAddress"))));
            d11.put("telefono", testo(f, "getTelephone"));
            d11.put("professione", testo(f, "getProfession"));
        } catch (Throwable e) { note.put("DG11", dettaglio(e)); }

        fase = "lettura DG12 (dati del documento)";
        JSONObject d12 = null;
        try {
            byte[] b = leggiFile(service, PassportService.EF_DG12);
            letti.put("DG12");
            d12 = new JSONObject();
            d12.put("grezzo", ascii(b));
            Object f = new org.jmrtd.lds.icao.DG12File(new ByteArrayInputStream(b));
            d12.put("autorita_rilascio", testo(f, "getIssuingAuthority"));
            d12.put("data_rilascio", testo(f, "getDateOfIssue"));
            d12.put("annotazioni", testo(f, "getEndorsementsAndObservations"));
        } catch (Throwable e) { note.put("DG12", dettaglio(e)); }

        fase = "lettura DG2 (fotografia)";
        JSONObject foto = null;
        try {
            byte[] b = leggiFile(service, PassportService.EF_DG2);
            letti.put("DG2");
            foto = estraiFoto(b);
        } catch (Throwable e) { note.put("DG2", dettaglio(e)); }

        chip.put("file_presenti", presenti);
        chip.put("file_letti", letti);
        chip.put("note", note);

        String opz1 = pulisci(mrz.getPersonalNumber());
        String opz2 = "";
        try { // presente solo nei documenti a 3 righe (TD1): si legge senza rischiare errori di compilazione
            Object o = mrz.getClass().getMethod("getOptionalData2").invoke(mrz);
            opz2 = pulisci(o == null ? "" : o.toString());
        } catch (Exception ignorata) { }

        String cf = "";
        for (String c : new String[]{opz1 + opz2, opz1, opz2}) {
            java.util.regex.Matcher m = CF.matcher(c);
            if (m.find()) { cf = m.group(); break; }
        }

        if (cf.isEmpty() && d11 != null) {
            java.util.regex.Matcher m = CF.matcher(d11.optString("codice_personale", "").replace(" ", "").toUpperCase(Locale.ROOT));
            if (m.find()) cf = m.group();
        }

        String sesso = String.valueOf(mrz.getGender()).toUpperCase(Locale.ROOT);
        String s = sesso.startsWith("F") ? "F" : (sesso.startsWith("M") ? "M" : "");

        JSONObject doc = new JSONObject();
        doc.put("tipo", "IDENT");
        doc.put("numero", pulisci(mrz.getDocumentNumber()));
        doc.put("scadenza", isoScadenza(mrz.getDateOfExpiry()));
        doc.put("stato_emittente", pulisci(mrz.getIssuingState()));
        if (d12 != null) {
            doc.put("rilasciato_da", d12.optString("autorita_rilascio", ""));
            doc.put("data_rilascio", dataIso(d12.optString("data_rilascio", "")));
        }

        JSONObject grezzo = new JSONObject();
        grezzo.put("personal_number", opz1);
        grezzo.put("optional2", opz2);

        JSONObject o = new JSONObject();
        o.put("formato", "nvs-cie/1");
        o.put("letto_il", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY).format(new Date()));
        o.put("cognome", pulisci(mrz.getPrimaryIdentifier()));
        o.put("nome", pulisci(mrz.getSecondaryIdentifier()));
        o.put("codice_fiscale", cf);
        o.put("data_nascita", isoNascita(mrz.getDateOfBirth()));
        o.put("sesso", s);
        o.put("cittadinanza", pulisci(mrz.getNationality()));
        o.put("documento", doc);
        o.put("mrz", grezzo);
        if (d11 != null) {
            String dnc = dataIso(d11.optString("data_nascita_completa", ""));
            if (!dnc.isEmpty()) o.put("data_nascita", dnc); // piu' affidabile: ha l'anno intero
            o.put("luogo_nascita", d11.optString("luogo_nascita", ""));
            o.put("luogo_nascita_parti", d11.optJSONArray("luogo_nascita_parti"));
            o.put("residenza", d11.optString("residenza", ""));
            o.put("residenza_parti", d11.optJSONArray("residenza_parti"));
            o.put("telefono", d11.optString("telefono", ""));
            chip.put("dg11", d11);
        }
        if (d12 != null) chip.put("dg12", d12);
        if (foto != null) o.put("foto", foto);
        o.put("chip", chip);
        return o.toString(2);
    }

    // ---- supporto per i file facoltativi del chip ----

    private static byte[] leggiFile(PassportService service, short fid) throws Exception {
        InputStream in = service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Dal contenuto di EF.COM ricava i numeri dei gruppi di dati presenti (tag 0x5C: lista di tag 0x61, 0x75, 0x6B...). */
    private static List<Integer> tagElenco(byte[] com) {
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i + 1 < com.length; i++) {
            if ((com[i] & 0xFF) == 0x5C) {
                int len = com[i + 1] & 0xFF;
                for (int k = 0; k < len && i + 2 + k < com.length; k++) {
                    int t = com[i + 2 + k] & 0xFF;
                    int dg = dgDaTag(t);
                    if (dg > 0 && !r.contains(dg)) r.add(dg);
                }
                break;
            }
        }
        return r;
    }

    private static int dgDaTag(int t) {
        switch (t) {
            case 0x61: return 1;  case 0x75: return 2;  case 0x63: return 3;  case 0x76: return 4;
            case 0x65: return 5;  case 0x66: return 6;  case 0x67: return 7;  case 0x68: return 8;
            case 0x69: return 9;  case 0x6A: return 10; case 0x6B: return 11; case 0x6C: return 12;
            case 0x6D: return 13; case 0x6E: return 14; case 0x6F: return 15; case 0x70: return 16;
            default: return 0;
        }
    }

    /** Il contenuto grezzo, solo i caratteri leggibili: serve a capire come la carta scrive i dati. */
    private static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && sb.length() < 700; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '|');
        }
        return sb.toString();
    }

    /** Chiama un metodo senza argomenti: se la libreria non lo ha, restituisce null senza fermare nulla. */
    private static Object chiama(Object o, String nome) {
        try {
            Method m = o.getClass().getMethod(nome);
            return m.invoke(o);
        } catch (Throwable e) { return null; }
    }

    private static String testo(Object o, String nome) {
        Object v = chiama(o, nome);
        List<String> p = parti(v);
        StringBuilder sb = new StringBuilder();
        for (String x : p) { if (sb.length() > 0) sb.append(", "); sb.append(x); }
        return sb.toString();
    }

    private static List<String> parti(Object v) {
        List<String> r = new ArrayList<>();
        if (v == null) return r;
        if (v instanceof Date) { r.add(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format((Date) v)); return r; }
        if (v instanceof Collection) {
            for (Object x : (Collection<?>) v) { String t = pulisci(String.valueOf(x)); if (!t.isEmpty()) r.add(t); }
            return r;
        }
        if (v.getClass().isArray() && v instanceof Object[]) {
            for (Object x : (Object[]) v) { String t = pulisci(String.valueOf(x)); if (!t.isEmpty()) r.add(t); }
            return r;
        }
        String t = pulisci(String.valueOf(v));
        if (!t.isEmpty()) r.add(t);
        return r;
    }

    /** "20241231", "2024-12-31", "241231" -> "2024-12-31" (i 6 cifre sono interpretati come scadenza/rilascio del 2000). */
    private static String dataIso(String v) {
        if (v == null) return "";
        String d = v.replaceAll("[^0-9]", "");
        if (d.length() == 8) return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        if (d.length() == 6) return (2000 + Integer.parseInt(d.substring(0, 2))) + "-" + d.substring(2, 4) + "-" + d.substring(4, 6);
        return "";
    }

    /**
     * Fotografia del DG2: sulla CIE e' di solito JPEG2000, che Chrome non sa mostrare.
     * Se e' JPEG normale si include (base64) cosi' NVS la usa; altrimenti si segnala soltanto formato e dimensione.
     */
    private static JSONObject estraiFoto(byte[] dg2) throws Exception {
        JSONObject f = new JSONObject();
        int ini = -1; String mime = "";
        for (int i = 0; i + 4 < dg2.length; i++) {
            if ((dg2[i] & 0xFF) == 0xFF && (dg2[i + 1] & 0xFF) == 0xD8 && (dg2[i + 2] & 0xFF) == 0xFF) { ini = i; mime = "image/jpeg"; break; }
            if ((dg2[i] & 0xFF) == 0xFF && (dg2[i + 1] & 0xFF) == 0x4F && (dg2[i + 2] & 0xFF) == 0xFF && (dg2[i + 3] & 0xFF) == 0x51) { ini = i; mime = "image/jp2-codestream"; break; }
            if (dg2[i] == 0 && dg2[i + 1] == 0 && dg2[i + 2] == 0 && (dg2[i + 3] & 0xFF) == 0x0C && dg2[i + 4] == 'j') { ini = i; mime = "image/jp2"; break; }
        }
        f.put("dg2_byte", dg2.length);
        if (ini < 0) { f.put("formato", "non riconosciuto"); return f; }
        int len = dg2.length - ini;
        f.put("formato", mime);
        f.put("immagine_byte", len);
        if ("image/jpeg".equals(mime) && len < 400000) {
            byte[] img = new byte[len];
            System.arraycopy(dg2, ini, img, 0, len);
            f.put("mime", mime);
            f.put("base64", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP));
        }
        return f;
    }

    private static String pulisci(String v) {
        return v == null ? "" : v.replace('<', ' ').trim();
    }

    /** yyMMdd -> yyyy-MM-dd. Nascita: se l'anno a 2 cifre e' maggiore di quello attuale e' del 1900, altrimenti del 2000. */
    private static String isoNascita(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) return "";
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int attuale = Calendar.getInstance().get(Calendar.YEAR) % 100;
        int anno = (yy > attuale ? 1900 : 2000) + yy;
        return anno + "-" + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
    }

    /** La scadenza e' sempre nel 2000. */
    private static String isoScadenza(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) return "";
        return (2000 + Integer.parseInt(yymmdd.substring(0, 2))) + "-" + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
    }
}
