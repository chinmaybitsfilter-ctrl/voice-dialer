package com.chinmay.voicedialer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_VOICE = "com.chinmay.voicedialer.VOICE_CALL"
        private const val CALL_DELAY_MS = 2500L
        private const val PREF_AUTO_LISTEN = "auto_listen"
    }

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var heardText: TextView
    private lateinit var micFab: ExtendedFloatingActionButton
    private lateinit var adapter: ContactAdapter
    private lateinit var nicknames: NicknameStore

    private var allContacts: List<Contact> = emptyList()
    private var contactsLoaded = false
    private var listenAfterLoad = false
    private var permissionDeniedOnce = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCall: Runnable? = null
    private var pendingSnackbar: Snackbar? = null
    private val io = Executors.newSingleThreadExecutor()

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // ---------------- permissions & speech launchers ----------------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (has(Manifest.permission.READ_CONTACTS)) {
                loadContacts()
            } else {
                permissionDeniedOnce = true
                showStatus(getString(R.string.need_contacts))
            }
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val results = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            if (results.isNotEmpty()) handleSpeech(results)
        }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        heardText = findViewById(R.id.heardText)
        micFab = findViewById(R.id.micFab)
        nicknames = NicknameStore(this)

        adapter = ContactAdapter(
            onCall = { pickNumberAndCall(it, null, viaVoice = false) },
            onLongPress = { editNickname(it) }
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        search.doAfterTextChanged { applyFilter() }
        micFab.setOnClickListener { startListening() }
        status.setOnClickListener {
            if (!has(Manifest.permission.READ_CONTACTS)) {
                if (permissionDeniedOnce &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
                ) openAppSettings() else requestPermissions()
            }
        }

        tts = TextToSpeech(this, this)

        listenAfterLoad = intent?.action == ACTION_VOICE ||
            (savedInstanceState == null && prefs.getBoolean(PREF_AUTO_LISTEN, false))
        requestPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_VOICE) startListening()
    }

    override fun onResume() {
        super.onResume()
        // User may have granted permission from Settings.
        if (!contactsLoaded && has(Manifest.permission.READ_CONTACTS)) loadContacts()
    }

    override fun onStop() {
        super.onStop()
        // Android blocks starting calls from the background, and the user has clearly moved on.
        cancelPendingCall()
    }

    override fun onDestroy() {
        cancelPendingCall()
        tts?.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.action_auto_listen).isChecked = prefs.getBoolean(PREF_AUTO_LISTEN, false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { loadContacts(); true }
        R.id.action_auto_listen -> {
            item.isChecked = !item.isChecked
            prefs.edit().putBoolean(PREF_AUTO_LISTEN, item.isChecked).apply()
            true
        }
        R.id.action_help -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------- contacts ----------------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filterNot { has(it) }
        if (needed.isEmpty()) loadContacts() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun loadContacts() {
        if (!has(Manifest.permission.READ_CONTACTS)) return
        showStatus(getString(R.string.loading))
        io.execute {
            val list = runCatching { ContactRepository.load(applicationContext) }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allContacts = list
                contactsLoaded = true
                applyFilter()
                if (listenAfterLoad) {
                    listenAfterLoad = false
                    startListening()
                }
            }
        }
    }

    private fun applyFilter() {
        if (!contactsLoaded) return
        val q = search.text?.toString()?.trim().orEmpty()
        val qDigits = q.filter { it.isDigit() }
        val filtered = if (q.isEmpty()) allContacts else allContacts.filter { c ->
            c.name.contains(q, ignoreCase = true) ||
                (c.nickname?.contains(q, ignoreCase = true) == true) ||
                (qDigits.length >= 3 && c.numbers.any { n -> n.number.filter { it.isDigit() }.contains(qDigits) })
        }
        adapter.submit(filtered)
        toolbar.subtitle = resources.getQuantityString(R.plurals.contact_count, allContacts.size, allContacts.size)
        when {
            allContacts.isEmpty() -> showStatus(getString(R.string.no_contacts))
            filtered.isEmpty() -> showStatus(getString(R.string.no_matches, q))
            else -> status.visibility = View.GONE
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun editNickname(c: Contact) {
        val input = EditText(this).apply {
            setText(c.nickname.orEmpty())
            hint = getString(R.string.nickname_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.nickname_title, c.name))
            .setMessage(R.string.nickname_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> saveNickname(c, input.text.toString()) }
            .setNeutralButton(R.string.remove) { _, _ -> saveNickname(c, null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveNickname(c: Contact, nickname: String?) {
        nicknames.set(c.id, nickname)
        val clean = nickname?.trim()?.takeIf { it.isNotEmpty() }
        allContacts = allContacts.map { if (it.id == c.id) it.copy(nickname = clean) else it }
        applyFilter()
    }

    // ---------------- voice assistant ----------------

    private fun startListening() {
        cancelPendingCall()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.say_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_recognizer, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSpeech(results: List<String>) {
        heardText.text = getString(R.string.heard, results.first())
        heardText.visibility = View.VISIBLE

        when (val r = VoiceCommandParser.resolve(results, allContacts)) {
            is Resolution.Dial -> confirmAndCall(r.number.chunked(5).joinToString(" "), r.number)
            is Resolution.Single -> pickNumberAndCall(r.contact, r.numberType, viaVoice = true)
            is Resolution.Ambiguous -> chooseContact(r)
            is Resolution.NoMatch -> {
                val msg = if (allContacts.isEmpty()) getString(R.string.no_contacts)
                else getString(R.string.not_found, r.heard)
                speak(msg)
                Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                    .setAnchorView(micFab)
                    .setAction(R.string.try_again) { startListening() }
                    .show()
            }
        }
    }

    private fun chooseContact(r: Resolution.Ambiguous) {
        speak(getString(R.string.which_one))
        val labels = r.candidates.map { c ->
            c.nickname?.let { "${c.name}  (“$it”)" } ?: c.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.which_one)
            .setItems(labels) { _, i -> pickNumberAndCall(r.candidates[i], r.numberType, viaVoice = false) }
            .setNeutralButton(R.string.try_again) { _, _ -> startListening() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * viaVoice = true → a single spoken match: announce and give a short window to cancel.
     * viaVoice = false → the user tapped / picked explicitly: call straight away.
     */
    private fun pickNumberAndCall(c: Contact, numberType: Int?, viaVoice: Boolean) {
        val nums = c.numbers
        if (nums.isEmpty()) return

        val ofType = numberType?.let { t -> nums.filter { it.type == t } }.orEmpty()
        val chosen = when {
            nums.size == 1 -> nums[0]
            ofType.size == 1 -> ofType[0]
            else -> null
        }
        if (chosen != null) {
            if (viaVoice) confirmAndCall(c.name, chosen.number) else placeCall(chosen.number)
            return
        }

        val options = ofType.ifEmpty { nums }
        if (viaVoice) speak(getString(R.string.which_number, c.name))
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(options.map { "${it.label}:  ${it.number}" }.toTypedArray()) { _, i ->
                placeCall(options[i].number)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmAndCall(label: String, number: String) {
        cancelPendingCall()
        speak(getString(R.string.calling_spoken, label))
        val bar = Snackbar.make(root, getString(R.string.calling_bar, label), Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(micFab)
            .setAction(R.string.cancel) {
                cancelPendingCall()
                speak(getString(R.string.cancelled))
            }
        bar.show()
        pendingSnackbar = bar
        val task = Runnable {
            pendingCall = null
            pendingSnackbar?.dismiss()
            pendingSnackbar = null
            placeCall(number)
        }
        pendingCall = task
        handler.postDelayed(task, CALL_DELAY_MS)
    }

    private fun cancelPendingCall() {
        pendingCall?.let { handler.removeCallbacks(it) }
        pendingCall = null
        pendingSnackbar?.dismiss()
        pendingSnackbar = null
    }

    private fun placeCall(number: String) {
        val uri = Uri.fromParts("tel", number, null)
        // Without CALL_PHONE we fall back to opening the dialer with the number filled in.
        val action = if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        try {
            startActivity(Intent(action, uri))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.call_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- text-to-speech ----------------

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) {
            val res = tts?.setLanguage(Locale.getDefault())
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voicedialer")
    }
}
