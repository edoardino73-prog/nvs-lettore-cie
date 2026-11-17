package it.nvs.lettorecie.pc;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

import java.io.ByteArrayOutputStream;

/**
 * Si interpone tra la libreria e la carta per due motivi:
 * 1) se la carta risponde "6Cxx" (lunghezza attesa diversa) riprova il comando con la lunghezza giusta, e se risponde "61xx" legge il resto con GET RESPONSE;
 * 2) ricorda gli ultimi scambi (solo intestazioni e codici di risposta, non i dati) per capire che cosa non va quando la lettura fallisce.
 */
public class DiagCardService extends CardService {

    private static final StringBuilder LOG = new StringBuilder();
    private final CardService in;

    public DiagCardService(CardService in) { this.in = in; LOG.setLength(0); }

    public static String ultimi() {
        String s = LOG.toString();
        return s.length() > 900 ? "..." + s.substring(s.length() - 900) : s;
    }

    private static String hex(int v) { return String.format("%02X", v & 0xFF); }

    private static void log(CommandAPDU c, ResponseAPDU r) {
        LOG.append(">").append(hex(c.getCLA())).append(hex(c.getINS())).append(hex(c.getP1())).append(hex(c.getP2()))
           .append(" Nc=").append(c.getNc()).append(" Ne=").append(c.getNe())
           .append(" <").append(String.format("%04X", r.getSW())).append(" n=").append(r.getData().length).append("\n");
    }

    public void open() throws CardServiceException { in.open(); }

    public boolean isOpen() { return in.isOpen(); }

    public void close() { in.close(); }

    public byte[] getATR() { try { return in.getATR(); } catch (Exception e) { return new byte[0]; } }

    public boolean isConnectionLost(Exception e) { return in.isConnectionLost(e); }

    public boolean isExtendedAPDULengthSupported() { return in.isExtendedAPDULengthSupported(); }

    public ResponseAPDU transmit(CommandAPDU c) throws CardServiceException {
        if ((c.getINS() & 0xFF) == 0x86 && c.getNc() > 255 && c.getNe() < 65536) { // GENERAL AUTHENTICATE con dati lunghi (PACE con DH a 2048 bit)
            c = new CommandAPDU(c.getCLA(), c.getINS(), c.getP1(), c.getP2(), c.getData(), 65536);
        }
        ResponseAPDU r = in.transmit(c);
        log(c, r);
        if ((r.getSW() & 0xFF00) == 0x6C00) { // lunghezza attesa sbagliata: la carta indica quella giusta
            int le = r.getSW() & 0xFF;
            // 6C00 = "serve una lunghezza che non sta in un byte": la risposta e' piu' lunga di 256 byte
            // (es. chiave pubblica DH a 2048 bit = 264 byte). Si chiede "piu' che si puo'": Le = 65536.
            if (le == 0) le = (c.getNe() < 256) ? 256 : 65536;
            CommandAPDU c2 = new CommandAPDU(c.getCLA(), c.getINS(), c.getP1(), c.getP2(), c.getData(), le);
            LOG.append("(riprovo con Ne=").append(c2.getNe()).append(")\n");
            r = in.transmit(c2);
            log(c2, r);
        }
        if ((r.getSW() & 0xFF00) == 0x6100) { // altri dati disponibili
            ByteArrayOutputStream tutto = new ByteArrayOutputStream();
            int guardia = 0;
            while ((r.getSW() & 0xFF00) == 0x6100 && guardia++ < 16) {
                byte[] d = r.getData();
                tutto.write(d, 0, d.length);
                int n = r.getSW() & 0xFF;
                CommandAPDU gr = new CommandAPDU(0x00, 0xC0, 0x00, 0x00, n == 0 ? 256 : n);
                r = in.transmit(gr);
                log(gr, r);
            }
            byte[] d = r.getData();
            tutto.write(d, 0, d.length);
            tutto.write((r.getSW() >> 8) & 0xFF);
            tutto.write(r.getSW() & 0xFF);
            r = new ResponseAPDU(tutto.toByteArray());
        }
        return r;
    }
}
