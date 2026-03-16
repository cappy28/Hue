package com.lumicontrol.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.lumicontrol.R
import com.lumicontrol.databinding.FragmentHomeBinding
import com.lumicontrol.models.Bulb
import com.lumicontrol.models.LightEffect
import com.lumicontrol.ui.adapters.BulbCardAdapter
import com.lumicontrol.ui.viewmodels.BulbViewModel

/**
 * ════════════════════════════════════════════════════════════
 * HomeFragment — Dashboard principal des ampoules
 * ════════════════════════════════════════════════════════════
 *
 * Affiche :
 *  - Carte de contrôle global (tout allumer/éteindre)
 *  - Grille des ampoules connectées
 *  - Sélecteur rapide de scènes
 *  - Contrôles de groupe
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BulbViewModel by activityViewModels()
    private lateinit var bulbAdapter: BulbCardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBulbGrid()
        setupGlobalControls()
        setupSceneChips()
        observeData()
    }

    private fun setupBulbGrid() {
        bulbAdapter = BulbCardAdapter(
            onBulbClick     = { bulb -> showBulbControls(bulb) },
            onPowerToggle   = { bulb, on -> viewModel.setPower(bulb.macAddress, on) },
            onFavorite      = { bulb -> viewModel.setFavorite(bulb.macAddress, !bulb.isFavorite) },
            onLongClick     = { bulb -> showBulbMenu(bulb) }
        )

        binding.recyclerBulbs.apply {
            adapter = bulbAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
            setHasFixedSize(true)
        }
    }

    private fun setupGlobalControls() {
        binding.btnAllOn.setOnClickListener {
            viewModel.setAllPower(true)
        }
        binding.btnAllOff.setOnClickListener {
            viewModel.setAllPower(false)
        }

        // Slider de luminosité globale
        binding.sliderGlobalBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.connectedBulbs.value?.forEach { bulb ->
                    viewModel.setBrightness(bulb.macAddress, value.toInt())
                }
            }
        }
    }

    private fun setupSceneChips() {
        // Les chips de scènes prédéfinies (Relax, Lecture, Fête...)
        // Générées dynamiquement depuis la DB
        viewModel.allScenes.observe(viewLifecycleOwner) { scenes ->
            val presets = scenes.filter { it.isPreset }
            binding.chipGroupScenes.removeAllViews()
            presets.forEach { scene ->
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = "${scene.iconEmoji} ${scene.name}"
                    isCheckable = false
                    setOnClickListener { viewModel.applySceneToAll(scene) }
                }
                binding.chipGroupScenes.addView(chip)
            }
        }
    }

    private fun observeData() {
        viewModel.connectedBulbs.observe(viewLifecycleOwner) { bulbs ->
            bulbAdapter.submitList(bulbs)
            updateEmptyState(bulbs.isEmpty())
            updateSummary(bulbs)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerBulbs.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.cardGlobalControl.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateSummary(bulbs: List<Bulb>) {
        val onCount = bulbs.count { it.isOn }
        binding.tvSummary.text = when {
            bulbs.isEmpty() -> ""
            onCount == 0    -> "${bulbs.size} ampoule(s) • Toutes éteintes"
            onCount == bulbs.size -> "${bulbs.size} ampoule(s) • Toutes allumées"
            else -> "${bulbs.size} ampoule(s) • $onCount allumée(s)"
        }
    }

    /** Ouvre le panneau de contrôle complet d'une ampoule. */
    private fun showBulbControls(bulb: Bulb) {
        viewModel.selectBulb(bulb)
        val fragment = ColorFragment.newInstance(bulb.macAddress)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Menu contextuel pour une ampoule (renommer, supprimer, effets...). */
    private fun showBulbMenu(bulb: Bulb) {
        val options = arrayOf("Renommer", "Effets dynamiques", "Ajouter à un groupe", "Déconnecter", "Supprimer")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(bulb.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(bulb)
                    1 -> showEffectsDialog(bulb)
                    2 -> showGroupDialog(bulb)
                    3 -> viewModel.disconnect(bulb.macAddress)
                    4 -> { viewModel.disconnect(bulb.macAddress) /* + delete from db */ }
                }
            }
            .show()
    }

    private fun showRenameDialog(bulb: Bulb) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(bulb.name)
            hint = "Nom de l'ampoule"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Renommer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // Mettre à jour via ViewModel
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEffectsDialog(bulb: Bulb) {
        val effects = LightEffect.values().map { "${it.emoji} ${it.label}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Effets pour ${bulb.name}")
            .setItems(effects) { _, which ->
                viewModel.startEffect(bulb.macAddress, LightEffect.values()[which])
            }
            .show()
    }

    private fun showGroupDialog(bulb: Bulb) {
        val groups = viewModel.allGroups.value ?: return
        val groupNames = (listOf("Aucun groupe") + groups.map { "${it.iconEmoji} ${it.name}" }).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Assigner un groupe")
            .setItems(groupNames) { _, _ -> /* assigner groupId */ }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ══════════════════════════════════════════════════════════
// ColorFragment — Contrôle avancé d'une ampoule
// ══════════════════════════════════════════════════════════

package com.lumicontrol.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lumicontrol.databinding.FragmentColorBinding
import com.lumicontrol.ui.viewmodels.BulbViewModel
import com.skydoves.colorpickerview.ColorPickerView
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

/**
 * Panneau de contrôle avancé pour une ampoule :
 *  - Color picker (roue chromique)
 *  - Slider luminosité
 *  - Slider température de couleur
 *  - Palettes de couleurs favorites
 *  - Effets dynamiques
 *  - Bouton on/off
 */
class ColorFragment : Fragment() {

    companion object {
        private const val ARG_MAC = "mac_address"
        fun newInstance(mac: String) = ColorFragment().apply {
            arguments = Bundle().apply { putString(ARG_MAC, mac) }
        }
    }

    private var _binding: FragmentColorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BulbViewModel by activityViewModels()

    private var macAddress: String = ""
    private var isApplyingColor = false // Éviter les boucles de feedback

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentColorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        macAddress = arguments?.getString(ARG_MAC) ?: return

        setupColorPicker()
        setupSliders()
        setupPowerButton()
        setupColorPresets()
        setupEffectButtons()
        observeBulb()
    }

    private fun setupColorPicker() {
        binding.colorPickerView.setColorListener(ColorEnvelopeListener { envelope, fromUser ->
            if (!fromUser || isApplyingColor) return@ColorEnvelopeListener
            val color = envelope.color
            viewModel.setColor(
                macAddress,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            binding.viewColorPreview.setBackgroundColor(color)
        })
    }

    private fun setupSliders() {
        // Luminosité
        binding.sliderBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setBrightness(macAddress, value.toInt())
                binding.tvBrightnessValue.text = "${value.toInt()}%"
            }
        }

        // Température de couleur (blanc chaud ↔ froid)
        binding.sliderTemperature.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val kelvin = value.toInt()
                val (r, g, b) = kelvinToRgb(kelvin)
                viewModel.setColor(macAddress, r, g, b)
                binding.tvTemperatureValue.text = "${kelvin}K"
            }
        }
    }

    private fun setupPowerButton() {
        binding.fabPower.setOnClickListener {
            val bulb = viewModel.selectedBulb.value ?: return@setOnClickListener
            val newState = !bulb.isOn
            viewModel.setPower(macAddress, newState)
            updatePowerButton(newState)
        }
    }

    private fun setupColorPresets() {
        // Couleurs favorites prédéfinies
        val presetColors = listOf(
            0xFFFF0000.toInt(),  // Rouge
            0xFF00FF00.toInt(),  // Vert
            0xFF0000FF.toInt(),  // Bleu
            0xFFFFFF00.toInt(),  // Jaune
            0xFFFF00FF.toInt(),  // Magenta
            0xFF00FFFF.toInt(),  // Cyan
            0xFFFFFFFF.toInt(),  // Blanc
            0xFFFFA500.toInt(),  // Orange
            0xFF800080.toInt(),  // Violet
            0xFFFF6B9D.toInt()   // Rose
        )

        presetColors.forEach { color ->
            val btn = View(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(80, 80)
                setBackgroundColor(color)
                setOnClickListener {
                    viewModel.setColor(macAddress, Color.red(color), Color.green(color), Color.blue(color))
                    binding.viewColorPreview.setBackgroundColor(color)
                }
            }
            binding.layoutColorPresets.addView(btn)
        }
    }

    private fun setupEffectButtons() {
        binding.chipFade.setOnClickListener {
            viewModel.startEffect(macAddress, com.lumicontrol.models.LightEffect.FADE)
        }
        binding.chipStrobe.setOnClickListener {
            viewModel.startEffect(macAddress, com.lumicontrol.models.LightEffect.STROBE)
        }
        binding.chipColorCycle.setOnClickListener {
            viewModel.startEffect(macAddress, com.lumicontrol.models.LightEffect.COLOR_CYCLE)
        }
        binding.chipBreathing.setOnClickListener {
            viewModel.startEffect(macAddress, com.lumicontrol.models.LightEffect.BREATHING)
        }
        binding.chipMusicSync.setOnClickListener {
            viewModel.startEffect(macAddress, com.lumicontrol.models.LightEffect.MUSIC_SYNC)
        }
    }

    private fun observeBulb() {
        viewModel.selectedBulb.observe(viewLifecycleOwner) { bulb ->
            bulb ?: return@observe
            binding.tvBulbName.text = bulb.name
            binding.sliderBrightness.value = bulb.brightness.toFloat()
            binding.tvBrightnessValue.text = "${bulb.brightness}%"
            updatePowerButton(bulb.isOn)
            binding.viewColorPreview.setBackgroundColor(bulb.getColor())
        }
    }

    private fun updatePowerButton(on: Boolean) {
        binding.fabPower.setImageResource(
            if (on) com.lumicontrol.R.drawable.ic_power_on
            else com.lumicontrol.R.drawable.ic_power_off
        )
        binding.fabPower.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) requireContext().getColor(com.lumicontrol.R.color.power_on)
            else requireContext().getColor(com.lumicontrol.R.color.power_off)
        )
    }

    /**
     * Convertit une température de couleur Kelvin en RGB approximatif.
     * Algorithme de Tanner Helland.
     */
    private fun kelvinToRgb(kelvin: Int): Triple<Int, Int, Int> {
        val temp = kelvin / 100f
        val r: Int
        val g: Int
        val b: Int

        r = if (temp <= 66) 255
        else (329.698727446 * Math.pow((temp - 60).toDouble(), -0.1332047592)).toInt().coerceIn(0, 255)

        g = if (temp <= 66)
            (99.4708025861 * Math.log(temp.toDouble()) - 161.1195681661).toInt().coerceIn(0, 255)
        else
            (288.1221695283 * Math.pow((temp - 60).toDouble(), -0.0755148492)).toInt().coerceIn(0, 255)

        b = when {
            temp >= 66 -> 255
            temp <= 19 -> 0
            else -> (138.5177312231 * Math.log((temp - 10).toDouble()) - 305.0447927307).toInt().coerceIn(0, 255)
        }

        return Triple(r, g, b)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
