using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class ProvaLettore
{
    [DllImport("winscard.dll")] static extern int SCardEstablishContext(int scope, IntPtr r1, IntPtr r2, out IntPtr ctx);
    [DllImport("winscard.dll")] static extern int SCardReleaseContext(IntPtr ctx);
    [DllImport("winscard.dll", EntryPoint = "SCardListReadersW", CharSet = CharSet.Unicode)]
    static extern int SCardListReaders(IntPtr ctx, string groups, IntPtr readers, ref int len);
    [DllImport("winscard.dll", EntryPoint = "SCardConnectW", CharSet = CharSet.Unicode)]
    static extern int SCardConnect(IntPtr ctx, string reader, int share, int protocols, out IntPtr card, out int active);
    [DllImport("winscard.dll")] static extern int SCardDisconnect(IntPtr card, int disp);
    [DllImport("winscard.dll")]
    static extern int SCardTransmit(IntPtr card, IntPtr sendPci, byte[] send, int sendLen, IntPtr recvPci, byte[] recv, ref int recvLen);
    [DllImport("winscard.dll", EntryPoint = "SCardStatusW", CharSet = CharSet.Unicode)]
    static extern int SCardStatus(IntPtr card, StringBuilder name, ref int nameLen, out int state, out int proto, byte[] atr, ref int atrLen);

    static StringBuilder rapporto = new StringBuilder();
    public static string Rapporto { get { return rapporto.ToString(); } }

    static void P(string s) { Console.WriteLine(s); rapporto.AppendLine(s); }

    static string Hex(byte[] b, int n)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < b.Length; i++) sb.Append(b[i].ToString("X2"));
        return sb.ToString();
    }

    static string Errore(int rc)
    {
        uint u = unchecked((uint)rc);
        switch (u)
        {
            case 0x8010001D: return "il servizio «Smart card» di Windows non e' attivo";
            case 0x8010002E: return "nessun lettore PC/SC trovato";
            case 0x8010000C: return "nessuna carta sul lettore";
            case 0x80100069: return "carta rimossa o non rilevata";
            case 0x8010000B: return "la carta e' usata da un altro programma";
            case 0x80100016: return "carta non risponde (non e' una carta compatibile?)";
            case 0x8010002F: return "comunicazione col lettore interrotta";
            case 0x80100010: return "tempo scaduto";
            default: return "codice 0x" + u.ToString("X8");
        }
    }

    /// <summary>Invia un comando alla carta e restituisce la risposta (dati + SW1SW2).</summary>
    static string Invia(IntPtr card, int proto, string etichetta, byte[] cmd, out int sw, out byte[] dati)
    {
        sw = -1; dati = new byte[0];
        IntPtr pci = Marshal.AllocHGlobal(8);
        Marshal.WriteInt32(pci, 0, proto);
        Marshal.WriteInt32(pci, 4, 8);
        byte[] rec = new byte[70000];
        int rl = rec.Length;
        int rc = SCardTransmit(card, pci, cmd, cmd.Length, IntPtr.Zero, rec, ref rl);
        Marshal.FreeHGlobal(pci);
        if (rc != 0) { P("   " + etichetta + ": ERRORE (" + Errore(rc) + ")"); return null; }
        if (rl < 2) { P("   " + etichetta + ": risposta troppo corta"); return null; }
        sw = (rec[rl - 2] << 8) | rec[rl - 1];
        dati = new byte[rl - 2];
        Array.Copy(rec, dati, rl - 2);
        P("   " + etichetta + ": SW=" + sw.ToString("X4") + (dati.Length > 0 ? "  (" + dati.Length + " byte)" : ""));
        return "ok";
    }

    public static void Esegui()
    {
        P("PROVA LETTORE NFC / CIE  -  " + DateTime.Now.ToString("dd/MM/yyyy HH:mm:ss"));
        P("");
        IntPtr ctx;
        int rc = SCardEstablishContext(2, IntPtr.Zero, IntPtr.Zero, out ctx);
        if (rc != 0) { P("1) Servizio PC/SC: ERRORE - " + Errore(rc)); return; }
        P("1) Servizio PC/SC di Windows: attivo");

        int len = 0;
        rc = SCardListReaders(ctx, null, IntPtr.Zero, ref len);
        if (rc != 0 || len <= 2)
        {
            P("2) Lettori riconosciuti come smart card: NESSUNO (" + Errore(rc) + ")");
            P("   Significa che il lettore non e' del tipo standard (e' probabilmente un lettore \"a tastiera\", che legge solo il numero di serie) oppure manca il driver.");
            SCardReleaseContext(ctx);
            return;
        }
        IntPtr buf = Marshal.AllocHGlobal(len * 2);
        rc = SCardListReaders(ctx, null, buf, ref len);
        string tutto = Marshal.PtrToStringUni(buf, len);
        Marshal.FreeHGlobal(buf);
        List<string> lettori = new List<string>();
        foreach (string r in tutto.Split('\0')) if (r.Length > 0) lettori.Add(r);
        P("2) Lettori riconosciuti come smart card: " + lettori.Count);
        foreach (string r in lettori) P("   - " + r);
        P("");

        P("3) Appoggia la CIE sul lettore e tienila ferma (attendo fino a 40 secondi)...");
        IntPtr card = IntPtr.Zero; int proto = 0; string usato = null; int ultimoRc = 0;
        DateTime fine = DateTime.Now.AddSeconds(40);
        while (DateTime.Now < fine && card == IntPtr.Zero)
        {
            foreach (string r in lettori)
            {
                IntPtr c; int att;
                int x = SCardConnect(ctx, r, 2, 3, out c, out att);
                if (x == 0) { card = c; proto = att; usato = r; break; }
                ultimoRc = x;
            }
            if (card == IntPtr.Zero) System.Threading.Thread.Sleep(500);
        }
        if (card == IntPtr.Zero)
        {
            P("   Carta NON rilevata: " + Errore(ultimoRc));
            SCardReleaseContext(ctx);
            return;
        }
        P("   Carta rilevata sul lettore: " + usato + "  (protocollo " + (proto == 1 ? "T=0" : (proto == 2 ? "T=1" : proto.ToString())) + ")");

        StringBuilder nome = new StringBuilder(256); int nl = 256, stato, pr; byte[] atr = new byte[64]; int al = 64;
        if (SCardStatus(card, nome, ref nl, out stato, out pr, atr, ref al) == 0) P("   ATR: " + Hex(atr, al));
        P("");

        int sw; byte[] d;
        byte[] aid = new byte[] { 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01 };

        P("4) Comandi base della CIE (parte documento):");
        byte[] sel = new byte[5 + aid.Length]; sel[1] = 0xA4; sel[2] = 0x04; sel[3] = 0x0C; sel[4] = (byte)aid.Length; Array.Copy(aid, 0, sel, 5, aid.Length);
        Invia(card, proto, "SELECT applicazione documento (comando corto)", sel, out sw, out d);
        bool corto = sw == 0x9000;

        // EF.CardAccess: elenco dei protocolli (qui c'e' PACE)
        byte[] fid = new byte[] { 0x00, 0xA4, 0x02, 0x0C, 0x02, 0x01, 0x1C };
        Invia(card, proto, "SELECT EF.CardAccess", fid, out sw, out d);
        byte[] leggi = new byte[] { 0x00, 0xB0, 0x00, 0x00, 0x00 };
        Invia(card, proto, "LEGGI EF.CardAccess", leggi, out sw, out d);
        if (sw == 0x9000 && d.Length > 0) P("   contenuto: " + Hex(d, 40) + (d.Length > 40 ? "..." : ""));
        bool pace = false;
        // OID di PACE inizia con 04 00 7F 00 07 02 02 04
        for (int i = 0; i + 6 < d.Length; i++) if (d[i] == 0x04 && d[i + 1] == 0x00 && d[i + 2] == 0x7F && d[i + 3] == 0x00 && d[i + 4] == 0x07 && d[i + 5] == 0x02 && d[i + 6] == 0x02) pace = true;
        P("   PACE dichiarato dalla carta: " + (pace ? "SI" : "non visto"));
        P("");

        P("5) Prova dei comandi LUNGHI (servono a PACE, il protocollo che usa il CAN):");
        // stessa SELECT ma in forma estesa (Lc a 3 byte)
        byte[] ext = new byte[7 + aid.Length]; ext[1] = 0xA4; ext[2] = 0x04; ext[3] = 0x0C; ext[4] = 0x00; ext[5] = 0x00; ext[6] = (byte)aid.Length; Array.Copy(aid, 0, ext, 7, aid.Length);
        int swE; byte[] dE;
        string r1 = Invia(card, proto, "SELECT in forma estesa", ext, out swE, out dE);
        bool lungo = (r1 != null && swE == 0x9000);
        // comando con 264 byte di dati (come il passaggio di PACE): GENERAL AUTHENTICATE di prova, ci si aspetta un errore della carta (non del lettore)
        byte[] grande = new byte[7 + 264]; grande[0] = 0x10; grande[1] = 0x86; grande[4] = 0x00; grande[5] = 0x01; grande[6] = 0x08;
        grande[7] = 0x7C; grande[8] = 0x82; grande[9] = 0x01; grande[10] = 0x04; grande[11] = 0x81; grande[12] = 0x82; grande[13] = 0x01; grande[14] = 0x00;
        int swG; byte[] dG;
        string r2 = Invia(card, proto, "comando da 264 byte (prova)", grande, out swG, out dG);
        bool grandeOk = (r2 != null);
        P("");
        P("RISULTATO:");
        P("   Lettore standard (PC/SC): SI");
        P("   Carta letta e risponde ai comandi: " + (corto ? "SI" : "NO / da verificare (SW " + sw.ToString("X4") + ")"));
        P("   Comandi lunghi accettati dal lettore: " + (lungo || grandeOk ? "SI" : "NO (potrebbe non bastare per leggere la CIE col CAN)"));
        SCardDisconnect(card, 0);
        SCardReleaseContext(ctx);
    }
}
