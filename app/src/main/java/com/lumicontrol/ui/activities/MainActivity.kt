package com.lumicontrol.ui.activities

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.lumicontrol.R
import com.lumicontrol.databinding.ActivityMainBinding
import com.lumicontrol.ui.viewmodels.BulbViewModel

/**
 * ════════════════════════════════════════════════════════════
 * MainActivity — Activité principale avec navigation par onglets
 * ════════════════════════════════════════════════════════════
 *
 * Héberge le NavHostFragment et la BottomNavigationView.
 * Gère les permissions Bluetooth au démarrage.
 *
 * Structure de navigation :
 *   Home (dashboard) → Color picker, Bulb detail
 *   Scenes           → Scene editor
 *   Schedule         → Schedule editor
 *   Settings
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: BulbViewModel by viewModels()

    // ─── Permission launcher ───
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            Snackbar.make(
                binding.root,
                "Permissions Bluetooth nécessaires pour utiliser l'application",
                Snackbar.LENGTH_LONG
            ).setAction("Paramètres") {
                openAppSettings()
            }.show()
        }
    }

    // ─── Bluetooth enable launcher ───
    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        checkPermissions()
        viewModel.initPresets()
        observeConnectionState()
    }

    private fun setupNavigation() {
        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = binding.bottomNavigationView
        bottomNav.setupWithNavController(navController)

        val appBarConfig = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.scenesFragment, R.id.scheduleFragment, R.id.settingsFragment)
        )
        setupActionBarWithNavController(navController, appBarConfig)
    }

    private fun checkPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        // Vérifier que le Bluetooth est activé
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && !btAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            btEnableLauncher.launch(enableBtIntent)
        }
    }

    private fun observeConnectionState() {
        viewModel.connectedBulbs.observe(this) { bulbs ->
            val count = bulbs.size
            supportActionBar?.subtitle = when (count) {
                0    -> "Aucune ampoule connectée"
                1    -> "1 ampoule connectée"
                else -> "$count ampoules connectées"
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> {
                startActivity(Intent(this, ScanActivity::class.java))
                true
            }
            R.id.action_all_off -> {
                viewModel.setAllPower(false)
                Snackbar.make(binding.root, "Toutes les lumières éteintes", Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_all_on -> {
                viewModel.setAllPower(true)
                Snackbar.make(binding.root, "Toutes les lumières allumées", Snackbar.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}

// ══════════════════════════════════════════════════════════
// ScanActivity — Scan et découverte des ampoules BLE
// ══════════════════════════════════════════════════════════

package com.lumicontrol.ui.activities

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumicontrol.databinding.ActivityScanBinding
import com.lumicontrol.models.Bulb
import com.lumicontrol.ui.adapters.ScanResultAdapter
import com.lumicontrol.ui.viewmodels.BulbViewModel

/**
 * Écran de scan BLE.
 * Affiche les appareils détectés et permet de les connecter.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val viewModel: BulbViewModel by viewModels()
    private lateinit var adapter: ScanResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Recherche d'ampoules"
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        setupButtons()
        observeData()

        // Démarrer le scan automatiquement
        viewModel.startScan()
    }

    private fun setupRecyclerView() {
        adapter = ScanResultAdapter(
            onConnect = { bulb -> connectBulb(bulb) },
            onHistory = { bulb -> connectBulb(bulb) }
        )
        binding.recyclerScanResults.apply {
            this.adapter = this@ScanActivity.adapter
            layoutManager = LinearLayoutManager(this@ScanActivity)
        }
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            if (viewModel.isScanning.value == true) {
                viewModel.stopScan()
            } else {
                viewModel.startScan()
            }
        }
    }

    private fun observeData() {
        viewModel.isScanning.observe(this) { scanning ->
            binding.btnScan.text = if (scanning) "Arrêter" else "Scanner"
            binding.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
            binding.tvStatus.text = if (scanning)
                "Recherche en cours..." else "Scan terminé"
        }

        viewModel.scanResults.observe(this) { results ->
            adapter.submitScanList(results)
            binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            binding.tvFoundCount.text = "${results.size} appareil(s) trouvé(s)"
        }

        // Historique des ampoules connues
        viewModel.allBulbs.observe(this) { known ->
            adapter.submitHistoryList(known)
        }
    }

    private fun connectBulb(bulb: Bulb) {
        binding.tvStatus.text = "Connexion à ${bulb.name}..."
        viewModel.connect(bulb) { success ->
            runOnUiThread {
                if (success) {
                    binding.tvStatus.text = "✅ ${bulb.name} connectée !"
                    // Retour au dashboard après connexion réussie
                    android.os.Handler(mainLooper).postDelayed({ finish() }, 1000)
                } else {
                    binding.tvStatus.text = "❌ Échec de connexion à ${bulb.name}"
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopScan()
    }
}
