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

import java.security.Security;
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
        } catch (Exception e) {
            throw new Exception("[" + fase + "] " + dettaglio(e) + "\n\nScambi con la carta:\n" + DiagCardService.ultimi());
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

        String sesso = String.valueOf(mrz.getGender()).toUpperCase(Locale.ROOT);
        String s = sesso.startsWith("F") ? "F" : (sesso.startsWith("M") ? "M" : "");

        JSONObject doc = new JSONObject();
        doc.put("tipo", "IDENT");
        doc.put("numero", pulisci(mrz.getDocumentNumber()));
        doc.put("scadenza", isoScadenza(mrz.getDateOfExpiry()));
        doc.put("stato_emittente", pulisci(mrz.getIssuingState()));

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
        return o.toString(2);
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
