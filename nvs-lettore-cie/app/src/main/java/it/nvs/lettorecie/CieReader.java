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

    public static String leggi(Tag tag, String can) throws Exception {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) throw new Exception("La carta non e' una carta con chip di tipo atteso (ISO-DEP).");
        isoDep.setTimeout(20000);

        CardService cardService = CardService.getInstance(isoDep);
        cardService.open();
        PassportService service = new PassportService(cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false);
        service.open();

        boolean pace = false;
        try {
            CardAccessFile cardAccess = new CardAccessFile(
                    service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE));
            for (SecurityInfo info : cardAccess.getSecurityInfos()) {
                if (info instanceof PACEInfo) {
                    PACEInfo paceInfo = (PACEInfo) info;
                    service.doPACE(PACEKeySpec.createCANKey(can), paceInfo.getObjectIdentifier(),
                            PACEInfo.toParameterSpec(paceInfo.getParameterId()), paceInfo.getParameterId());
                    pace = true;
                    break;
                }
            }
        } catch (Exception e) {
            throw new Exception("La carta non ha accettato il CAN. Controlla le 6 cifre e riappoggia la carta. (" + e.getMessage() + ")");
        }
        if (!pace) throw new Exception("La carta non ha risposto al protocollo PACE: potrebbe non essere una CIE 3.0.");

        service.sendSelectApplet(true);
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
