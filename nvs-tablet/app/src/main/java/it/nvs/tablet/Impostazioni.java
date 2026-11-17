package it.nvs.tablet;

/** Le uniche impostazioni dell'app. Per cambiarle si modifica questo file e si ricostruisce l'APK. */
final class Impostazioni {
    private Impostazioni() { }

    /** Indirizzo del sito del tablet NVS (quello pubblicato con PUBBLICA_TUTTO.bat). */
    static final String URL_SITO = "https://tablet-nuovavillasilvana.pages.dev/";

    /** Siti che possono aprirsi nell'app e usare il lettore CIE. Ogni altro indirizzo viene rifiutato. */
    static final String[] SITI_CONSENTITI = { "tablet-nuovavillasilvana.pages.dev" };

    /** true = modalita' chiosco: l'app si blocca a tutto schermo e non si esce senza il codice. */
    static final boolean CHIOSCO = true;

    /** Codice per uscire dalla modalita' chiosco (premere 7 volte «volume giu'» e scriverlo). CAMBIALO prima di usare l'app. */
    static final String CODICE_USCITA = "4682";
}
