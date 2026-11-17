package it.nvs.lettorecie.pc;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.TerminalCardService;

import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * NVS Lettore CIE per PC: legge la carta d'identita' elettronica con un lettore NFC/contactless USB (standard PC/SC)
 * e il CAN (6 cifre), e copia i dati negli appunti nel formato "nvs-cie/1" che NVS importa (Anagrafica > Importa da CIE).
 * Il CAN non viene salvato. Il PIN della carta non serve.
 */
public class Main {

    private static JTextField campoCan;
    private static JLabel stato, collegamento;
    private static JTextArea risultato;
    private static JButton btnLeggi, btnCopia;
    private static String ultimo = "";

    public static void main(String[] args) throws Exception {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignorata) { }
        SwingUtilities.invokeLater(Main::finestra);
        Servizio.avvia();
    }

    private static void finestra() {
        JFrame f = new JFrame("NVS Lettore CIE (PC)");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JLabel t = new JLabel("<html><b style='font-size:15px'>Lettura della carta d'identità elettronica</b><br>"
                + "1) Scrivi il CAN (6 cifre stampate sul fronte della carta).<br>"
                + "2) Premi «Leggi la carta» e appoggia la CIE sul lettore, ferma e piatta.<br>"
                + "3) I dati vanno negli appunti: in NVS, Anagrafica, «Importa da CIE», «Leggi dagli appunti».</html>");
        p.add(t, BorderLayout.NORTH);

        JPanel centro = new JPanel(new BorderLayout(6, 6));
        JPanel riga = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        riga.add(new JLabel("CAN:"));
        campoCan = new JTextField(8);
        campoCan.setFont(campoCan.getFont().deriveFont(Font.BOLD, 20f));
        riga.add(campoCan);
        btnLeggi = new JButton("Leggi la carta");
        btnLeggi.setFont(btnLeggi.getFont().deriveFont(Font.BOLD, 15f));
        riga.add(btnLeggi);
        btnCopia = new JButton("Copia di nuovo negli appunti");
        btnCopia.setEnabled(false);
        riga.add(btnCopia);
        centro.add(riga, BorderLayout.NORTH);
        stato = new JLabel(" ");
        collegamento = new JLabel(" ");
        JPanel giu = new JPanel(new GridLayout(2, 1));
        giu.add(stato);
        giu.add(collegamento);
        centro.add(giu, BorderLayout.CENTER);
        p.add(centro, BorderLayout.CENTER);

        risultato = new JTextArea(18, 70);
        risultato.setEditable(false);
        risultato.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(risultato), BorderLayout.SOUTH);

        btnLeggi.addActionListener(e -> avvia());
        campoCan.addActionListener(e -> avvia());
        btnCopia.addActionListener(e -> { copia(ultimo); stato("Copiato negli appunti.", false); });

        f.setContentPane(p);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        campoCan.requestFocusInWindow();
    }

    static void stato(String testo, boolean errore) {
        SwingUtilities.invokeLater(() -> {
            stato.setText("<html>" + testo.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
            stato.setForeground(errore ? new Color(0xB91C1C) : new Color(0x0F172A));
        });
    }

    private static void copia(String testo) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(testo), null); } catch (Exception ignorata) { }
    }

    private static CardTerminal scegliLettore(List<CardTerminal> tutti) {
        for (CardTerminal t : tutti) {
            String n = t.getName().toLowerCase();
            if (n.contains("contactless") || n.contains("picc") || n.contains("nfc")) return t;
        }
        return tutti.get(0);
    }

    private static void avvia() {
        final String can = campoCan.getText().replaceAll("\\D", "");
        if (can.length() != 6) { stato("Scrivi i 6 cifre del CAN.", true); return; }
        btnLeggi.setEnabled(false);
        risultato.setText("");
        stato("Cerco il lettore…", false);
        new Thread(() -> {
            try {
                String json = leggiCarta(can, 30000);
                ultimo = json;
                copia(json);
                SwingUtilities.invokeLater(() -> { risultato.setText(vista(json)); risultato.setCaretPosition(0); btnCopia.setEnabled(true); });
                stato("Letto. Dati copiati negli appunti: ora apri NVS, Anagrafica, Importa da CIE.", false);
            } catch (Throwable e) {
                stato("Non riuscito: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), true);
            } finally {
                SwingUtilities.invokeLater(() -> { btnLeggi.setEnabled(true); campoCan.setText(""); });
            }
        }, "lettura").start();
    }

    private static final java.util.concurrent.locks.ReentrantLock BLOCCO = new java.util.concurrent.locks.ReentrantLock();

    /** Elenco dei lettori di carte collegati (vuoto se nessuno o se il servizio Smart card non risponde). */
    static List<String> nomiLettori() {
        List<String> r = new ArrayList<>();
        try { for (CardTerminal t : TerminalFactory.getDefault().terminals().list()) r.add(t.getName()); } catch (Throwable ignorata) { }
        return r;
    }

    /** Aspetta la carta sul lettore, la legge col CAN e restituisce il testo JSON "nvs-cie/1". Una lettura alla volta. */
    static String leggiCarta(String can, int attesaMs) throws Exception {
        if (!BLOCCO.tryLock()) throw new Exception("Una lettura e' gia' in corso.");
        try {
            stato("Cerco il lettore…", false);
            List<CardTerminal> lettori = new ArrayList<>(TerminalFactory.getDefault().terminals().list());
            if (lettori.isEmpty()) throw new Exception("Nessun lettore di carte trovato: collega il lettore NFC USB.");
            CardTerminal t = scegliLettore(lettori);
            stato("Appoggia la CIE sul lettore «" + t.getName() + "» e tienila ferma…", false);
            if (!t.isCardPresent() && !t.waitForCardPresent(attesaMs)) throw new Exception("Nessuna carta appoggiata sul lettore (tempo scaduto).");
            Thread.sleep(400); // lascia che la carta si stabilizzi sull'antenna
            stato("Lettura in corso: non muovere la carta…", false);
            CardService svc = new TerminalCardService(t);
            try { return CieCore.leggi(svc, can); } finally { try { svc.close(); } catch (Throwable ignorata) { } }
        } finally {
            BLOCCO.unlock();
        }
    }

    /** Sullo schermo la fotografia (testo lunghissimo) non si mostra; negli appunti c'e' completa. */
    static void collegamento(String testo, boolean errore) {
        SwingUtilities.invokeLater(() -> {
            if (collegamento == null) return;
            collegamento.setText("<html>" + testo.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
            collegamento.setForeground(errore ? new Color(0xB91C1C) : new Color(0x15803D));
        });
    }

    private static String vista(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONObject f = o.optJSONObject("foto");
            if (f != null && f.has("base64")) f.put("base64", "(fotografia inclusa)");
            return o.toString(2);
        } catch (Throwable e) { return json; }
    }
}
