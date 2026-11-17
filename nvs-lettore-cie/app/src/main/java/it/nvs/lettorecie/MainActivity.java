package it.nvs.lettorecie;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Schermata unica: si scrive il CAN, si appoggia la carta, i dati compaiono e vengono copiati negli appunti.
 * Il CAN non viene salvato da nessuna parte. I dati letti restano solo sullo schermo e negli appunti.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private NfcAdapter nfc;
    private EditText editCan;
    private TextView txtStato, txtRisultato;
    private Button btnCopia, btnCondividi;
    private String ultimo = "";
    private volatile boolean occupato = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // niente istantanee della schermata nell'elenco delle app recenti: ci sono dati personali
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        editCan = findViewById(R.id.editCan);
        txtStato = findViewById(R.id.txtStato);
        txtRisultato = findViewById(R.id.txtRisultato);
        btnCopia = findViewById(R.id.btnCopia);
        btnCondividi = findViewById(R.id.btnCondividi);
        nfc = NfcAdapter.getDefaultAdapter(this);

        btnCopia.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copia(true); }
        });
        btnCondividi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, ultimo);
                startActivity(Intent.createChooser(i, "Condividi i dati letti"));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc == null) { stato("Questo dispositivo non ha l'NFC.", true); return; }
        if (!nfc.isEnabled()) { stato("L'NFC e' spento: accendilo dalle impostazioni del tablet.", true); return; }
        nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    /** Chiamata quando una carta viene appoggiata (non e' il thread dello schermo). */
    @Override
    public void onTagDiscovered(final Tag tag) {
        if (occupato) return;
        final String can = editCan.getText().toString().trim();
        if (!can.matches("\\d{6}")) {
            stato("Prima scrivi il CAN: sono 6 cifre.", true);
            return;
        }
        occupato = true;
        stato("Lettura in corso: non muovere la carta...", false);
        try {
            final String json = CieReader.leggi(tag, can);
            ultimo = json;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    txtRisultato.setText(json);
                    txtRisultato.setVisibility(View.VISIBLE);
                    btnCopia.setVisibility(View.VISIBLE);
                    btnCondividi.setVisibility(View.VISIBLE);
                    copia(false);
                    stato("Letto. Dati copiati negli appunti: ora apri NVS, Anagrafica, Importa da CIE.", false);
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(200);
                }
            });
        } catch (Exception e) {
            stato("Non riuscito: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), true);
        } finally {
            occupato = false;
        }
    }

    private void copia(boolean mostraAvviso) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && !ultimo.isEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("Dati CIE", ultimo));
            if (mostraAvviso) Toast.makeText(this, "Copiato negli appunti", Toast.LENGTH_SHORT).show();
        }
    }

    private void stato(final String testo, final boolean errore) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                txtStato.setText(testo);
                txtStato.setTextColor(errore ? 0xFFB91C1C : 0xFF0F172A);
            }
        });
    }
}
