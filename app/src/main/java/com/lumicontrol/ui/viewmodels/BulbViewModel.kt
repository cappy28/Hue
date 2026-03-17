package com.lumicontrol.ui.viewmodels

import android.app.Application
import androidx.lifecycle.*
import com.lumicontrol.data.repository.BulbRepository
import com.lumicontrol.models.*
import kotlinx.coroutines.launch

class BulbViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BulbRepository(application)

    val allBulbs       = repo.getAllBulbs().asLiveData()
    val connectedBulbs = repo.connectedBulbs.asLiveData()
    val scanResults    = repo.scanResults.asLiveData()
    val isScanning     = repo.isScanning.asLiveData()
    val allScenes      = repo.getAllScenes().asLiveData()
    val allSchedules   = repo.getAllSchedules().asLiveData()
    val allGroups      = repo.getAllGroups().asLiveData()

    private val _selectedBulb = MutableLiveData<Bulb?>()
    val selectedBulb: LiveData<Bulb?> = _selectedBulb

    fun selectBulb(bulb: Bulb?) { _selectedBulb.value = bulb }
    fun startScan() = repo.startScan()
    fun stopScan() = repo.stopScan()
    fun connect(bulb: Bulb, onResult: (Boolean) -> Unit = {}) =
        viewModelScope.launch { repo.saveBulb(bulb); repo.connect(bulb, onResult) }
    fun disconnect(mac: String) = repo.disconnect(mac)
    fun setPower(mac: String, on: Boolean) = repo.setPower(mac, on)
    fun setColor(mac: String, r: Int, g: Int, b: Int) = repo.setColor(mac, r, g, b)
    fun setBrightness(mac: String, brightness: Int) = repo.setBrightness(mac, brightness)
    fun startEffect(mac: String, effect: LightEffect) = repo.startEffect(mac, effect)
    fun applySceneToAll(scene: Scene) = repo.applyScene(scene)
    fun setAllPower(on: Boolean) { connectedBulbs.value?.forEach { repo.setPower(it.macAddress, on) } }
    fun saveScene(scene: Scene) = viewModelScope.launch { repo.saveScene(scene) }
    fun deleteScene(scene: Scene) = viewModelScope.launch { repo.deleteScene(scene) }
    fun setFavorite(mac: String, fav: Boolean) = viewModelScope.launch { repo.setFavorite(mac, fav) }
    fun saveSchedule(s: Schedule) = viewModelScope.launch { repo.saveSchedule(s) }
    fun deleteSchedule(s: Schedule) = viewModelScope.launch { repo.deleteSchedule(s) }
    fun toggleSchedule(id: Long, en: Boolean) = viewModelScope.launch { repo.toggleSchedule(id, en) }
    fun saveGroup(g: BulbGroup) = viewModelScope.launch { repo.saveGroup(g) }
    fun initPresets() = viewModelScope.launch { repo.initPresets() }
    fun getFavorites() = repo.getFavorites().asLiveData()
}
