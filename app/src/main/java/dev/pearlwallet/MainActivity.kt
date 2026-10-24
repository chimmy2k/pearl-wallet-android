package dev.pearlwallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.PreparedSend
import dev.pearlwallet.logic.TxEntry
import dev.pearlwallet.logic.WalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var host: FrameLayout
    private lateinit var secure: SecureStore
    private lateinit var bb: Blockbook
    private lateinit var wallet: WalletStore

    private var snap: WalletStore.Snapshot? = null
    private var receiveIndex = 0

    /** Guards against double-tap / re-entrancy on the send path. */
    private var sending = false
    private var reviewing = false

    /** Which screen is on display — drives hardware/gesture back. */
    private enum class Screen { WELCOME, MNEMONIC, IMPORT, HOME, RECEIVE, SEND, SETTINGS, ADDRESSES }

    private var screen = Screen.WELCOME

    /** Set while a confirmation dialog is up, so back dismisses it instead of navigating. */
    private var dialogOpen = false

    /** Back twice within this window to leave the app from Home. */
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        host = findViewById(R.id.host)

        secure = SecureStore(this)
        bb = Blockbook(secure.indexerUrl)
        wallet = WalletStore(secure, bb)

        // Wordlist is packaged in assets; if it is missing the app is unusable, so fail
        // loudly instead of dying with an opaque NPE on the first derivation.
        try {
            Bip39.loadWordlist(
                assets.open("bip39_english.txt").bufferedReader().readLines()
                    .filter { it.isNotBlank() }
            )
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Instalasi rusak")
                .setMessage("File kata BIP39 tidak ada di dalam APK. Pasang ulang aplikasi.")
                .setCancelable(false)
                .setPositiveButton("Tutup") { _, _ -> finish() }
                .show()
            return
        }

        if (wallet.loadSaved()) showHome() else showWelcome()
        installBackHandler()
    }

    /**
     * Hardware / gesture back. Dismisses a dialog first, then walks the screen
     * hierarchy down to Home, and only leaves the app on a deliberate double-back
     * from Home (a wallet must never close on a stray swipe).
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dialogOpen) return          // dialog handles its own dismissal
                when (screen) {
                    Screen.RECEIVE, Screen.SEND, Screen.SETTINGS, Screen.ADDRESSES -> showHome()
                    Screen.IMPORT -> showWelcome()
                    Screen.MNEMONIC -> toast("Catat dulu 12 kata ini — tidak bisa kembali")
                    Screen.HOME -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackAt < 2000) {
                            finish()
                        } else {
                            lastBackAt = now
                            toast("Tekan kembali sekali lagi untuk keluar")
                        }
                    }
                    Screen.WELCOME -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------ screens

    private fun swap(layout: Int): View {
        host.removeAllViews()
        val v = layoutInflater.inflate(layout, host, false)
        host.addView(v)
        return v
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showWelcome() {
        screen = Screen.WELCOME
        val v = swap(R.layout.screen_welcome)
        v.findViewById<TextView>(R.id.txtVersion).text =
            "v${BuildConfig.VERSION_NAME} · mainnet · BIP86"
        v.findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { createWallet() }
        v.findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { showImport() }
    }

    private fun createWallet() {
        val m = try {
            wallet.createNew()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Gagal membuat dompet")
                .setMessage(
                    "Keystore perangkat menolak menyimpan seed.\n\n${e.message}\n\n" +
                        "Coba lagi, atau pulihkan dompet yang sudah ada."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        showMnemonic(m)
    }

    private fun showMnemonic(mnemonic: String) {
        screen = Screen.MNEMONIC
        val v = swap(R.layout.screen_mnemonic)
        val grid = v.findViewById<LinearLayout>(R.id.wordGrid)
        val words = mnemonic.split(" ")
        for (row in 0 until (words.size + 1) / 2) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0..1) {
                val idx = row * 2 + col
                if (idx >= words.size) break
                val tv = TextView(this).apply {
                    text = String.format("%2d. %s", idx + 1, words[idx])
                    setTextColor(getColor(R.color.pearl))
                    textSize = 14f
                    setPadding(0, 10, 0, 10)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                line.addView(tv)
            }
            grid.addView(line)
        }
        val chk = v.findViewById<CheckBox>(R.id.chkSaved)
        val btn = v.findViewById<MaterialButton>(R.id.btnContinue)
        chk.setOnCheckedChangeListener { _, checked -> btn.isEnabled = checked }
        btn.setOnClickListener { showHome() }
    }

    private fun showImport() {
        screen = Screen.IMPORT
        val v = swap(R.layout.screen_import)
        val edt = v.findViewById<EditText>(R.id.edtMnemonic)
        val err = v.findViewById<TextView>(R.id.txtError)
        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showWelcome() }
        v.findViewById<MaterialButton>(R.id.btnRestore).setOnClickListener {
            val input = edt.text.toString()
            if (!Bip39.isValid(input)) {
                err.text = "Seed tidak valid (jumlah kata atau checksum salah)"
                return@setOnClickListener
            }
            wallet.restore(input)
            showHome()
        }
    }

    private fun showHome() {
        screen = Screen.HOME
        val v = swap(R.layout.screen_home)
        v.findViewById<TextView>(R.id.txtAddress).text = wallet.receiveAddress(0)
        v.findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener { refresh(v, force = true) }
        v.findViewById<MaterialButton>(R.id.btnReceive).setOnClickListener {
            receiveIndex = 0
            showReceive()
        }
        v.findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { showSend() }
        v.findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { showSettings() }
        refresh(v, force = false)
    }

    private fun refresh(v: View, force: Boolean) {
        val sync = v.findViewById<TextView>(R.id.txtSync)
        sync.text = "sinkron…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    wallet.refresh(force)
                } catch (e: Exception) {
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (result == null) {
                sync.text = "gagal sync"
                return@launch
            }
            snap = result
            v.findViewById<TextView>(R.id.txtBalance).text =
                Tx.satToPrl(result.confirmedSat + result.unconfirmedSat)
            sync.text = buildString {
                append("${result.ms} ms")
                append(" · ${result.txsOnChain} tx")
                if (result.unconfirmedSat != 0L) append(" · ada pending")
            }
            bindTx(v, result.history, result.txsOnChain)
        }
    }

    private fun bindTx(v: View, list: List<TxEntry>, txCount: Int) {
        val rv = v.findViewById<RecyclerView>(R.id.listTx)
        val empty = v.findViewById<TextView>(R.id.txtEmpty)
        if (list.isEmpty()) {
            rv.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = if (txCount > 0)
                "Indexer mencatat $txCount transaksi tapi daftarnya kosong.\nCoba Segarkan."
            else
                "Belum ada transaksi"
            return
        }
        rv.visibility = View.VISIBLE
        empty.visibility = View.GONE
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = TxAdapter(list)
    }

    private fun showReceive() {
        screen = Screen.RECEIVE
        val v = swap(R.layout.screen_receive)
        val addr = wallet.receiveAddress(receiveIndex)
        v.findViewById<TextView>(R.id.txtAddress).text = addr
        v.findViewById<TextView>(R.id.txtIndex).text = "Alamat #$receiveIndex"
        v.findViewById<ImageView>(R.id.imgQr).setImageBitmap(qr(addr))
        v.findViewById<MaterialButton>(R.id.btnPrev).setOnClickListener {
            if (receiveIndex > 0) {
                receiveIndex--
                showReceive()
            }
        }
        v.findViewById<MaterialButton>(R.id.btnNext).setOnClickListener {
            // cap at the scanned window so the index cannot run away
            if (receiveIndex < wallet.gap) {
                receiveIndex++
                showReceive()
            } else {
                toast("Sudah di batas ${wallet.gap} alamat")
            }
        }
        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showHome() }
        v.findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            copy(addr)
            toast("Alamat disalin")
        }
    }

    private fun showSend() {
        screen = Screen.SEND
        val s = snap
        if (s == null) {
            toast("Tunggu sinkron dulu")
            return
        }
        val v = swap(R.layout.screen_send)
        val edtTo = v.findViewById<EditText>(R.id.edtTo)
        val edtAmt = v.findViewById<EditText>(R.id.edtAmount)
        val status = v.findViewById<TextView>(R.id.txtStatus)
        val feeInfo = v.findViewById<TextView>(R.id.txtFeeInfo)
        val group = v.findViewById<RadioGroup>(R.id.feeGroup)
        val available = v.findViewById<TextView>(R.id.txtAvailable)

        available.text = "Tersedia: ${Tx.satToPrl(wallet.totalSpendable(s))} PRL"

        fun blocks(): Int = when (group.checkedRadioButtonId) {
            R.id.feeSlow -> 6
            R.id.feeFast -> 1
            else -> 3
        }

        fun updateFee() {
            lifecycleScope.launch {
                val rate = withContext(Dispatchers.IO) {
                    try {
                        bb.feePerKb(blocks())
                    } catch (e: Exception) {
                        10_000L
                    }
                }
                val est = Tx.estimateFee(2, 2, rate)
                feeInfo.text = "≈ ${Tx.satToPrl(est)} PRL (${rate} sat/kB)"
            }
        }
        group.setOnCheckedChangeListener { _, _ -> updateFee() }
        updateFee()

        v.findViewById<MaterialButton>(R.id.btnMax).setOnClickListener { btn ->
            if (reviewing) return@setOnClickListener
            reviewing = true
            btn.isEnabled = false
            lifecycleScope.launch {
                try {
                    val rate = withContext(Dispatchers.IO) {
                        try {
                            bb.feePerKb(blocks())
                        } catch (e: Exception) {
                            10_000L
                        }
                    }
                    // MAX must be based on coins that still exist, not the cached list
                    val fresh = withContext(Dispatchers.IO) {
                        try {
                            wallet.refresh(force = true)
                        } catch (e: Exception) {
                            s
                        }
                    }
                    snap = fresh
                    val max = wallet.maxSendable(fresh, rate)
                    if (isFinishing || isDestroyed) return@launch
                    available.text = "Tersedia: ${Tx.satToPrl(wallet.totalSpendable(fresh))} PRL"
                    edtAmt.setText(Tx.satToPrl(max))
                } finally {
                    reviewing = false
                    if (isFinishing || isDestroyed) return@launch
                    btn.isEnabled = true
                }
            }
        }

        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showHome() }

        v.findViewById<MaterialButton>(R.id.btnReview).setOnClickListener { btn ->
            if (reviewing) return@setOnClickListener
            val to = edtTo.text.toString().trim()
            val amtSat = Tx.prlToSat(edtAmt.text.toString())
            if (amtSat == null || amtSat <= 0) {
                status.text = "Jumlah tidak valid"
                return@setOnClickListener
            }
            val destOk = try {
                Pearl.scriptPubKey(to)
                true
            } catch (e: Exception) {
                false
            }
            if (!destOk) {
                status.text = "Alamat tidak valid"
                return@setOnClickListener
            }

            // Decimal-slip check. The operator's report was "I typed 0.01 and 0.001 went
            // out" — a factor-of-ten error, not a change problem. Warn, never block:
            // sending 0.001 deliberately is legitimate. The dialog is asynchronous, so the
            // continuation must live in the positive-button callback, not after show().
            val slip = detectDecimalSlip(edtAmt.text.toString(), amtSat)
            if (slip != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Periksa jumlah")
                    .setMessage(slip)
                    .setNegativeButton("Ubah") { _, _ -> }
                    .setPositiveButton("Lanjut") { _, _ ->
                        startReview(btn, status, to, amtSat, blocks())
                    }
                    .show()
            } else {
                startReview(btn, status, to, amtSat, blocks())
            }
        }

        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showHome() }
    }

    /** Fee lookup, forced refresh, then prepare + confirm. Split out so the
     *  decimal-slip dialog can resume into it from a button callback. */
    private fun startReview(
        btn: View,
        status: TextView,
        to: String,
        amtSat: Long,
        blocks: Int
    ) {
        if (reviewing) return
        val base = snap ?: run {
            status.text = "Tunggu sinkron dulu"
            return
        }
        reviewing = true
        btn.isEnabled = false
        status.text = "menyiapkan…"
        lifecycleScope.launch {
            try {
                val rate = withContext(Dispatchers.IO) {
                    try {
                        bb.feePerKb(blocks)
                    } catch (e: Exception) {
                        10_000L
                    }
                }
                // refresh first: a cached snapshot may reference coins already spent
                val fresh = withContext(Dispatchers.IO) {
                    try {
                        wallet.refresh(force = true)
                    } catch (e: Exception) {
                        base
                    }
                }
                val prepared = withContext(Dispatchers.IO) {
                    try {
                        wallet.prepareSend(fresh, to, amtSat, rate)
                    } catch (e: Exception) {
                        status.text = e.message ?: "gagal menyiapkan"
                        null
                    }
                }
                if (prepared == null) return@launch
                snap = fresh
                status.text = ""
                confirmAndBroadcast(prepared, to)
            } finally {
                reviewing = false
                if (isFinishing || isDestroyed) return@launch
                btn.isEnabled = true
            }
        }
    }

    /**
     * Confirmation gate. Nothing is signed until the operator ticks the box, and the
     * dialog shows the FULL destination address plus every output the signed bytes
     * actually contain — not a summary written by the app.
     */
    private fun confirmAndBroadcast(p: PreparedSend, to: String) {
        val v = layoutInflater.inflate(R.layout.dialog_confirm_send, null)
        v.findViewById<TextView>(R.id.txtTo).text = p.destAddress.ifEmpty { to }
        v.findViewById<TextView>(R.id.txtAmount).text = "${Tx.satToPrl(p.amountSat)} PRL"

        // self-send notice: without this the operator cannot tell why the balance barely moved
        val warn = v.findViewById<TextView>(R.id.txtSelfWarn)
        if (p.selfSend) {
            warn.visibility = View.VISIBLE
            warn.text = "Alamat tujuan ini milik kamu sendiri.\n" +
                "Efeknya cuma biaya ${Tx.satToPrl(p.feeSat)} PRL — dana tidak pindah ke mana-mana."
        } else {
            warn.visibility = View.GONE
        }

        // The change address MUST be visible, otherwise the operator opens an explorer,
        // sees the bulk of the balance on an unknown address and thinks the funds left.
        val changeAddrView = v.findViewById<TextView>(R.id.txtChangeAddr)
        val changeView = v.findViewById<TextView>(R.id.txtChange)
        if (p.changeSat > 0) {
            changeAddrView.text = p.changeAddress
            changeView.text = "${Tx.satToPrl(p.changeSat)} PRL"
        } else {
            changeAddrView.text = "—"
            changeView.text = "tidak ada kembalian (sisa masuk biaya)"
        }

        v.findViewById<TextView>(R.id.txtFee).text =
            "Biaya ke miner : ${Tx.satToPrl(p.feeSat)} PRL  (${String.format("%.1f", p.satPerVb)} sat/vB)"

        v.findViewById<TextView>(R.id.txtTotal).text =
            "Dana terpakai  : ${Tx.satToPrl(p.totalIn)} PRL dari ${p.inputs} koin"

        // Raw satoshi, thousands-separated. This is the one line that makes a
        // decimal-slip (0.01 vs 0.001) impossible to miss before signing:
        // 0.01 PRL = 1.000.000 sat, 0.001 PRL = 100.000 sat.
        v.findViewById<TextView>(R.id.txtSat).text =
            "= ${String.format(Locale.US, "%,d", p.amountSat).replace(',', '.')} satoshi"

        val chk = v.findViewById<CheckBox>(R.id.chkConfirm)

        dialogOpen = true
        val dlg = AlertDialog.Builder(this)
            .setTitle("Konfirmasi kirim")
            .setView(v)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Tanda tangan & kirim", null)   // wired below so we can gate it
            .setOnDismissListener { dialogOpen = false }
            .create()

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            chk.setOnCheckedChangeListener { _, checked -> btn.isEnabled = checked }
            btn.setOnClickListener {
                if (sending) return@setOnClickListener
                sending = true
                btn.isEnabled = false
                chk.isEnabled = false
                dlg.setCancelable(false)
                lifecycleScope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) {
                            try {
                                bb.broadcast(p.rawHex)
                            } catch (e: Exception) {
                                e.message ?: "gagal broadcast"
                            }
                        }
                        wallet.invalidate()
                        dlg.dismiss()
                        if (isFinishing || isDestroyed) return@launch
                        val ok = res == p.txid
                        if (ok) showReceipt(p, res) else showFailure(res)
                    } finally {
                        sending = false
                    }
                }
            }
        }
        dlg.show()
    }

    /** Post-send receipt: exactly where every satoshi went, plus the new total. */
    private fun showReceipt(p: PreparedSend, txid: String) {
        val body = buildString {
            append("TERKIRIM KE TUJUAN\n")
            append("${Tx.satToPrl(p.amountSat)} PRL\n")
            append("${p.destAddress}\n\n")

            if (p.changeSat > 0) {
                append("DIKEMBALIKAN KE DOMPET KAMU\n")
                append("${Tx.satToPrl(p.changeSat)} PRL\n")
                append("${p.changeAddress}\n\n")
            } else {
                append("TANPA KEMBALIAN — seluruh dana terpakai\n\n")
            }

            append("BIAYA MINER\n")
            append("${Tx.satToPrl(p.feeSat)} PRL\n\n")

            if (p.selfSend) {
                append("Catatan: tujuan adalah alamat kamu sendiri,\n")
                append("jadi yang benar-benar berkurang hanya biaya.\n\n")
            }

            append("txid\n$txid")
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle("Terkirim")
            .setMessage(body)
            .setCancelable(false)
            .setNeutralButton("Salin txid") { _, _ ->
                copy(txid)
                toast("txid disalin")
            }
            .setPositiveButton("OK") { _, _ -> showHome() }
            .show()
    }

    private fun showFailure(msg: String) {
        AlertDialog.Builder(this@MainActivity)
            .setTitle("Gagal mengirim")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> showHome() }
            .show()
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        val v = swap(R.layout.screen_settings)
        val edtIdx = v.findViewById<EditText>(R.id.edtIndexer)
        val edtGap = v.findViewById<EditText>(R.id.edtGap)
        edtIdx.setText(secure.indexerUrl)
        edtGap.setText(secure.gapLimit.toString())
        v.findViewById<TextView>(R.id.txtIndexerHint).text =
            "Default: ${SecureStore.DEFAULT_INDEXER}"
        v.findViewById<TextView>(R.id.txtAbout).text = buildString {
            append("Pearl Wallet Android ${BuildConfig.VERSION_NAME}\n")
            append("Taproot BIP86 · m/86'/808276'/0'\n")
            append("Alamat: prl1 (bech32m)\n")
            append("Seed: BIP39 12 kata, AES-256-GCM di Android Keystore\n")
            append("Kripto: secp256k1 + BIP340, implementasi murni Kotlin\n")
            append("Diverifikasi lawan consensus engine Pearl (txscript)")
        }

        v.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            secure.indexerUrl = edtIdx.text.toString()
            secure.gapLimit = edtGap.text.toString().toIntOrNull() ?: 12
            bb.setBase(secure.indexerUrl)
            wallet.invalidate()
            toast("Disimpan")
        }
        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showHome() }
        v.findViewById<MaterialButton>(R.id.btnAddresses).setOnClickListener { showAddresses() }
        v.findViewById<MaterialButton>(R.id.btnShowSeed).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Seed 12 kata")
                .setMessage(wallet.mnemonic)
                .setPositiveButton("Tutup", null)
                .show()
        }
        v.findViewById<MaterialButton>(R.id.btnWipe).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Hapus dompet?")
                .setMessage("Seed akan dihapus dari perangkat. Tanpa backup 12 kata, dana tidak bisa dipulihkan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    wallet.wipe()
                    showWelcome()
                }
                .show()
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Returns a warning string when the typed amount looks like a decimal slip,
     * or null when it is fine. Never blocks — the caller shows it as an extra
     * confirmation, because sending 0.001 on purpose is legitimate.
     *
     * The on-chain evidence for this: the operator typed 0.01 and the tx carried
     * 100_000 sat (= 0.001), a clean factor of ten. Nothing in the UI showed the
     * satoshi value, so the mistake stayed invisible until the explorer.
     */
    private fun detectDecimalSlip(raw: String, amtSat: Long): String? {
        val s = raw.trim().replace(",", ".")

        // precision echo — 0.01 must mean 1.000.000 sat, not 100.000
        val parsed = Tx.prlToSat(s) ?: return "Jumlah tidak bisa dibaca: '$s'"
        if (parsed != amtSat) {
            return "Jumlah tidak konsisten: '$s' seharusnya $parsed sat, bukan $amtSat sat"
        }

        val satFmt = String.format(Locale.US, "%,d", amtSat).replace(',', '.')
        val decimals = s.substringAfter('.', "").length

        // the specific shape that bit the operator: few decimals + a value that is a
        // round power-of-ten fraction of a coin
        if (decimals in 1..3 && amtSat in listOf(10_000L, 100_000L, 1_000_000L)) {
            val likely = when (amtSat) {
                100_000L -> "0,001 PRL = $satFmt sat. Kalau maksud kamu 0,01 PRL, itu 1.000.000 sat — kurang satu nol."
                10_000L -> "0,0001 PRL = $satFmt sat. Kalau maksud kamu 0,001 PRL, itu 100.000 sat — kurang satu nol."
                else -> "1.000.000 sat = 0,01 PRL. Pastikan ini memang jumlah yang kamu mau."
            }
            return likely
        }
        return null
    }

    /**
     * "Alamat saya" — every derived address that holds a balance, with the exact
     * explorer-visible string. Purpose: after a send, the operator can look up the
     * change address on prlscan and see it listed here, proving the funds came back
     * instead of leaving. This directly addresses the "everything got transferred" scare.
     */
    private fun showAddresses() {
        screen = Screen.ADDRESSES
        val v = swap(R.layout.screen_addresses)
        val s = snap
        v.findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { showSettings() }

        val info = v.findViewById<TextView>(R.id.txtInfo)
        val list = v.findViewById<RecyclerView>(R.id.listAddr)
        list.layoutManager = LinearLayoutManager(this)

        if (s == null) {
            info.text = "Belum ada data. Buka Beranda dulu agar saldo tersinkron."
            list.adapter = AddressAdapter(emptyList())
            return
        }

        val rows = wallet.addressesWithBalance(s)
        info.text = buildString {
            append("Total ${rows.size} alamat berisi dana.\n")
            append("Alamat \"kembalian\" adalah tempat sisa transaksi kamu disimpan — ")
            append("kalau kamu lihat alamat ini di prlscan, itu tetap milik kamu.")
        }
        list.adapter = AddressAdapter(rows)
    }

    private inner class AddressAdapter(private val items: List<WalletStore.AddressBalance>) :
        RecyclerView.Adapter<AddressAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tag: TextView = v.findViewById(R.id.txtTag)
            val addr: TextView = v.findViewById(R.id.txtAddr)
            val bal: TextView = v.findViewById(R.id.txtBal)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_address, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.tag.text = a.role + " · " + a.utxoCount + " koin"
            holder.addr.text = a.address
            holder.bal.text = Tx.satToPrl(a.balanceSat) + " PRL"
            holder.itemView.setOnClickListener {
                copy(a.address)
                toast("Alamat disalin")
            }
        }
    }

    /**
     * QR as a single IntArray -> Bitmap. The naive version called setPixel() 409k times,
     * which is 409k JNI crossings on the UI thread (visible stall). One createBitmap call
     * over a pixel array is ~50x faster.
     */
    private fun qr(text: String): Bitmap {
        val size = 640
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (bits.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("prl", text))
    }

    private inner class TxAdapter(private val items: List<TxEntry>) :
        RecyclerView.Adapter<TxAdapter.VH>() {

        private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID"))

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val kind: TextView = v.findViewById(R.id.txtKind)
            val amount: TextView = v.findViewById(R.id.txtAmount)
            val peer: TextView = v.findViewById(R.id.txtPeer)
            val whenT: TextView = v.findViewById(R.id.txtWhen)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tx, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val incoming = t.deltaSat >= 0
            val self = t.deltaSat in -1_000L..0L && t.peer.isEmpty()

            holder.kind.text = when {
                t.isPending -> if (incoming) "Masuk · pending" else "Keluar · pending"
                self -> "Biaya"
                incoming -> "Masuk"
                else -> "Keluar"
            }
            holder.kind.setTextColor(
                getColor(
                    when {
                        t.isPending -> R.color.muted
                        incoming -> R.color.accent
                        else -> R.color.pearl
                    }
                )
            )

            holder.amount.text = (if (incoming && t.deltaSat != 0L) "+" else "") +
                Tx.satToPrl(t.deltaSat) + " PRL"
            holder.amount.setTextColor(
                getColor(
                    when {
                        t.isPending -> R.color.muted
                        incoming -> R.color.accent
                        else -> R.color.danger
                    }
                )
            )

            holder.peer.text = if (t.peer.isEmpty()) t.txid.take(24) + "…" else t.peer

            holder.whenT.text = buildString {
                if (t.blockTime > 0) append(fmt.format(Date(t.blockTime * 1000)))
                else append("menunggu konfirmasi")
                when {
                    t.confirmations == 1 -> append(" · 1 konfirmasi")
                    t.confirmations > 1 -> append(" · ${t.confirmations} konfirmasi")
                }
                if (t.feeSat > 0) append(" · biaya ${Tx.satToPrl(t.feeSat)}")
            }

            // tapping a row reveals the full txid so it can be checked in an explorer
            holder.itemView.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (incoming) "Transaksi masuk" else "Transaksi keluar")
                    .setMessage(
                        "txid\n${t.txid}\n\n" +
                            "jumlah   ${Tx.satToPrl(t.deltaSat)} PRL\n" +
                            "biaya    ${Tx.satToPrl(t.feeSat)} PRL\n" +
                            "blok     ${if (t.height > 0) t.height.toString() else "-"}\n" +
                            "konfirmasi ${t.confirmations}\n\n" +
                            "pihak lain\n${t.peer.ifEmpty { "-" }}"
                    )
                    .setPositiveButton("Salin txid") { _, _ ->
                        copy(t.txid)
                        toast("txid disalin")
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }
    }
}
