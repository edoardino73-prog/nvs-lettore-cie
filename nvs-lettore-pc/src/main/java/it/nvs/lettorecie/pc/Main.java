package it.nvs.lettorecie.pc;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.TerminalCardService;

import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
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
        boolean daNvs = args.length > 0 && args[0].toLowerCase().startsWith("nvscie:");
        if (daNvs) { // avviato dal pulsante di NVS: se il programma e' gia' aperto non si apre una seconda copia
            try (java.net.ServerSocket prova = new java.net.ServerSocket()) {
                prova.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), Servizio.PORTA));
            } catch (java.io.IOException giaAperto) { return; }
        }
        registraProtocollo();
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignorata) { }
        SwingUtilities.invokeLater(Main::finestra);
        Servizio.avvia();
    }

    /**
     * Registra in Windows (solo per l'utente corrente, senza amministratore) l'indirizzo «nvscie://» che apre questo programma:
     * serve al pulsante «Avvia lettore PC» di NVS. Si rifa' a ogni avvio, cosi' segue il programma se si sposta la cartella.
     */
    private static void registraProtocollo() {
        try {
            String exe = ProcessHandle.current().info().command().orElse("");
            String m = exe.toLowerCase();
            if (!m.endsWith(".exe") || m.endsWith("\\java.exe") || m.endsWith("\\javaw.exe")) return;
            String cmd = ("\"" + exe + "\" \"%1\"").replace("\\", "\\\\").replace("\"", "\\\"");
            String reg = "Windows Registry Editor Version 5.00\r\n\r\n"
                    + "[HKEY_CURRENT_USER\\Software\\Classes\\nvscie]\r\n@=\"URL:NVS Lettore CIE\"\r\n\"URL Protocol\"=\"\"\r\n\r\n"
                    + "[HKEY_CURRENT_USER\\Software\\Classes\\nvscie\\shell\\open\\command]\r\n@=\"" + cmd + "\"\r\n";
            java.nio.file.Path f = java.nio.file.Files.createTempFile("nvscie", ".reg");
            byte[] testo = reg.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            byte[] tutto = new byte[testo.length + 2];
            tutto[0] = (byte) 0xFF; tutto[1] = (byte) 0xFE;
            System.arraycopy(testo, 0, tutto, 2, testo.length);
            java.nio.file.Files.write(f, tutto);
            new ProcessBuilder("reg", "import", f.toString()).redirectErrorStream(true).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            java.nio.file.Files.deleteIfExists(f);
        } catch (Throwable ignorata) { /* senza registrazione il programma funziona lo stesso, si apre a mano */ }
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
        if (!BLOCCO.tryLock(4, java.util.concurrent.TimeUnit.SECONDS)) throw new Exception("Una lettura e' gia' in corso.");
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

    private static boolean badgeSulLettore = false;

    /**
     * Timbratura con badge NFC (NTAG, Mifare e simili): attende fino a attesaMs un badge NUOVO sul lettore e ne restituisce il codice
     * identificativo (UID) in esadecimale maiuscolo. null = nessun badge nuovo (o lettore occupato con una lettura CIE).
     * Un badge lasciato sul lettore conta una volta sola: va tolto prima di poterne passare un altro (o lo stesso).
     */
    static String leggiUid(int attesaMs) throws Exception {
        if (!BLOCCO.tryLock()) return null;
        try {
            List<CardTerminal> lettori = new ArrayList<>(TerminalFactory.getDefault().terminals().list());
            if (lettori.isEmpty()) throw new Exception("Nessun lettore di carte trovato: collega il lettore NFC USB.");
            CardTerminal t = scegliLettore(lettori);
            if (badgeSulLettore) {
                if (t.isCardPresent()) { t.waitForCardAbsent(attesaMs); return null; }
                badgeSulLettore = false;
            }
            if (!t.isCardPresent() && !t.waitForCardPresent(attesaMs)) return null;
            badgeSulLettore = true;
            Thread.sleep(120);
            Card c = t.connect("*");
            try {
                ResponseAPDU r = c.getBasicChannel().transmit(new CommandAPDU(new byte[]{(byte) 0xFF, (byte) 0xCA, 0, 0, 0}));
                if (r.getSW() != 0x9000 || r.getData().length == 0)
                    throw new Exception("Il lettore non fornisce il codice di questa tessera (risposta " + Integer.toHexString(r.getSW()).toUpperCase() + ").");
                StringBuilder h = new StringBuilder();
                for (byte b : r.getData()) h.append(String.format("%02X", b & 0xFF));
                stato("Badge letto: " + h + " (timbratura NFC).", false);
                return h.toString();
            } finally {
                try { c.disconnect(false); } catch (Throwable ignorata) { }
            }
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
