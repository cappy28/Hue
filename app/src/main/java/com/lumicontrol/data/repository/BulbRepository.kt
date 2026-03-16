package com.lumicontrol.data.repository

import android.content.Context
import com.lumicontrol.bluetooth.BleManager
import com.lumicontrol.data.database.AppDatabase
import com.lumicontrol.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * ════════════════════════════════════════════════════════════
 * BulbRepository — Source unique de vérité pour les ampoules
 * ════════════════════════════════════════════════════════════
 *
 * Coordonne la base de données locale (Room) et le BleManager.
 * Le ViewModel n'interagit jamais directement avec la DB ou BLE.
 */
class BulbRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val bulbDao = db.bulbDao()
    private val sceneDao = db.sceneDao()
    private val scheduleDao = db.scheduleDao()
    private val groupDao = db.groupDao()
    private val bleManager = BleManager.getInstance(context)

    // ─── Ampoules ───

    fun getAllBulbs(): Flow<List<Bulb>> = bulbDao.getAllBulbs()
    fun getFavorites(): Flow<List<Bulb>> = bulbDao.getFavorites()
    val connectedBulbs = bleManager.connectedBulbs
    val scanResults = bleManager.scanResults
    val isScanning = bleManager.isScanning

    suspend fun saveBulb(bulb: Bulb) {
        bulbDao.insertBulb(bulb)
    }

    suspend fun deleteBulb(mac: String) {
        bulbDao.deleteBulbByMac(mac)
        bleManager.disconnect(mac)
    }

    suspend fun setFavorite(mac: String, fav: Boolean) {
        bulbDao.setFavorite(mac, fav)
    }

    // ─── Connexion BLE ───

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun connect(bulb: Bulb, onResult: (Boolean) -> Unit = {}) {
        bleManager.connect(bulb, onResult)
    }

    fun disconnect(mac: String) = bleManager.disconnect(mac)

    // ─── Commandes lumières ───

    fun setPower(mac: String, on: Boolean) = bleManager.setPower(mac, on)
    fun setColor(mac: String, r: Int, g: Int, b: Int) = bleManager.setColor(mac, r, g, b)
    fun setBrightness(mac: String, brightness: Int) = bleManager.setBrightness(mac, brightness)
    fun startEffect(mac: String, effect: LightEffect, speed: Long = 500) =
        bleManager.startEffect(mac, effect, speed)

    fun applyScene(scene: Scene, targetMac: String? = null) {
        scene.bulbStates.forEach { state ->
            val macs = if (state.macAddress == "*") {
                // Appliquer à toutes les ampoules connectées
                bleManager.connectedBulbs.value.map { it.macAddress }
            } else {
                listOf(state.macAddress)
            }
            macs.forEach { mac ->
                bleManager.setPower(mac, state.isOn)
                if (state.isOn) {
                    bleManager.setColor(mac, state.r, state.g, state.b)
                    bleManager.setBrightness(mac, state.brightness)
                }
            }
        }
    }

    // ─── Scènes ───

    fun getAllScenes(): Flow<List<Scene>> = sceneDao.getAllScenes()

    suspend fun saveScene(scene: Scene): Long = sceneDao.insertScene(scene)

    suspend fun deleteScene(scene: Scene) = sceneDao.deleteScene(scene)

    suspend fun initPresets() {
        if (sceneDao.countPresets() == 0) {
            Scene.presets().forEach { sceneDao.insertScene(it) }
        }
    }

    // ─── Planifications ───

    fun getAllSchedules(): Flow<List<Schedule>> = scheduleDao.getAllSchedules()

    suspend fun saveSchedule(schedule: Schedule): Long = scheduleDao.insertSchedule(schedule)

    suspend fun deleteSchedule(schedule: Schedule) = scheduleDao.deleteSchedule(schedule)

    suspend fun toggleSchedule(id: Long, enabled: Boolean) = scheduleDao.setEnabled(id, enabled)

    // ─── Groupes ───

    fun getAllGroups(): Flow<List<BulbGroup>> = groupDao.getAllGroups()

    suspend fun saveGroup(group: BulbGroup) = groupDao.insertGroup(group)

    suspend fun deleteGroup(group: BulbGroup) = groupDao.deleteGroup(group)
}

// ══════════════════════════════════════════════════════════
// ViewModel
// ══════════════════════════════════════════════════════════

package com.lumicontrol.ui.viewmodels

import android.app.Application
import androidx.lifecycle.*
import com.lumicontrol.data.repository.BulbRepository
import com.lumicontrol.models.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel principal — partagé par tous les fragments du dashboard.
 * Survit aux rotations d'écran et aux changements de configuration.
 */
class BulbViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BulbRepository(application)

    // ─── Données observables ───
    val allBulbs        = repo.getAllBulbs().asLiveData()
    val favorites       = repo.getFavorites().asLiveData()
    val connectedBulbs  = repo.connectedBulbs.asLiveData()
    val scanResults     = repo.scanResults.asLiveData()
    val isScanning      = repo.isScanning.asLiveData()
    val allScenes       = repo.getAllScenes().asLiveData()
    val allSchedules    = repo.getAllSchedules().asLiveData()
    val allGroups       = repo.getAllGroups().asLiveData()

    // ─── État de l'ampoule sélectionnée ───
    private val _selectedBulb = MutableLiveData<Bulb?>()
    val selectedBulb: LiveData<Bulb?> = _selectedBulb

    fun selectBulb(bulb: Bulb?) {
        _selectedBulb.value = bulb
    }

    // ─── Scan BLE ───
    fun startScan()  = repo.startScan()
    fun stopScan()   = repo.stopScan()

    // ─── Connexion ───
    fun connect(bulb: Bulb, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            repo.saveBulb(bulb) // Sauvegarder dans l'historique
            repo.connect(bulb, onResult)
        }
    }

    fun disconnect(mac: String) = repo.disconnect(mac)

    // ─── Commandes ───
    fun setPower(mac: String, on: Boolean)          = repo.setPower(mac, on)
    fun setColor(mac: String, r: Int, g: Int, b: Int) = repo.setColor(mac, r, g, b)
    fun setBrightness(mac: String, brightness: Int)  = repo.setBrightness(mac, brightness)
    fun startEffect(mac: String, effect: LightEffect) = repo.startEffect(mac, effect)

    fun applySceneToAll(scene: Scene) = repo.applyScene(scene)

    fun setAllPower(on: Boolean) {
        connectedBulbs.value?.forEach { bulb ->
            repo.setPower(bulb.macAddress, on)
        }
    }

    // ─── Persistance ───
    fun saveScene(scene: Scene) = viewModelScope.launch { repo.saveScene(scene) }
    fun deleteScene(scene: Scene) = viewModelScope.launch { repo.deleteScene(scene) }
    fun setFavorite(mac: String, fav: Boolean) = viewModelScope.launch { repo.setFavorite(mac, fav) }
    fun saveSchedule(s: Schedule) = viewModelScope.launch { repo.saveSchedule(s) }
    fun deleteSchedule(s: Schedule) = viewModelScope.launch { repo.deleteSchedule(s) }
    fun toggleSchedule(id: Long, en: Boolean) = viewModelScope.launch { repo.toggleSchedule(id, en) }
    fun saveGroup(g: BulbGroup) = viewModelScope.launch { repo.saveGroup(g) }
    fun deleteGroup(g: BulbGroup) = viewModelScope.launch { repo.deleteGroup(g) }

    fun initPresets() = viewModelScope.launch { repo.initPresets() }

    // ─── Groupes ───
    fun getAllBulbsInGroup(groupId: Long) =
        allBulbs.value?.filter { it.groupId == groupId } ?: emptyList()

    override fun onCleared() {
        super.onCleared()
        // Ne pas déconnecter ici — le service BLE reste actif
    }
}
