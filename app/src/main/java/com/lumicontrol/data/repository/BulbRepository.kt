package com.lumicontrol.data.repository

import android.content.Context
import com.lumicontrol.bluetooth.BleManager
import com.lumicontrol.data.database.AppDatabase
import com.lumicontrol.models.*
import kotlinx.coroutines.flow.Flow

class BulbRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val bulbDao = db.bulbDao()
    private val sceneDao = db.sceneDao()
    private val scheduleDao = db.scheduleDao()
    private val groupDao = db.groupDao()
    private val bleManager = BleManager.getInstance(context)

    val connectedBulbs = bleManager.connectedBulbs
    val scanResults = bleManager.scanResults
    val isScanning = bleManager.isScanning

    fun getAllBulbs(): Flow<List<Bulb>> = bulbDao.getAllBulbs()
    fun getFavorites(): Flow<List<Bulb>> = bulbDao.getFavorites()
    fun getAllScenes(): Flow<List<Scene>> = sceneDao.getAllScenes()
    fun getAllSchedules(): Flow<List<Schedule>> = scheduleDao.getAllSchedules()
    fun getAllGroups(): Flow<List<BulbGroup>> = groupDao.getAllGroups()

    suspend fun saveBulb(bulb: Bulb) = bulbDao.insertBulb(bulb)
    suspend fun deleteBulb(mac: String) { bulbDao.deleteBulbByMac(mac); bleManager.disconnect(mac) }
    suspend fun setFavorite(mac: String, fav: Boolean) = bulbDao.setFavorite(mac, fav)
    suspend fun saveScene(scene: Scene): Long = sceneDao.insertScene(scene)
    suspend fun deleteScene(scene: Scene) = sceneDao.deleteScene(scene)
    suspend fun saveSchedule(s: Schedule): Long = scheduleDao.insertSchedule(s)
    suspend fun deleteSchedule(s: Schedule) = scheduleDao.deleteSchedule(s)
    suspend fun toggleSchedule(id: Long, enabled: Boolean) = scheduleDao.setEnabled(id, enabled)
    suspend fun saveGroup(g: BulbGroup) = groupDao.insertGroup(g)
    suspend fun deleteGroup(g: BulbGroup) = groupDao.deleteGroup(g)

    suspend fun initPresets() {
        if (sceneDao.countPresets() == 0) {
            Scene.presets().forEach { sceneDao.insertScene(it) }
        }
    }

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun connect(bulb: Bulb, onResult: (Boolean) -> Unit = {}) {
        bleManager.connect(bulb, onResult)
    }

    fun disconnect(mac: String) = bleManager.disconnect(mac)
    fun setPower(mac: String, on: Boolean) = bleManager.setPower(mac, on)
    fun setColor(mac: String, r: Int, g: Int, b: Int) = bleManager.setColor(mac, r, g, b)
    fun setBrightness(mac: String, brightness: Int) = bleManager.setBrightness(mac, brightness)
    fun startEffect(mac: String, effect: LightEffect, speed: Long = 500) = bleManager.startEffect(mac, effect, speed)

    fun applyScene(scene: Scene) {
        scene.bulbStates.forEach { state ->
            val macs = if (state.macAddress == "*")
                bleManager.connectedBulbs.value.map { it.macAddress }
            else listOf(state.macAddress)

            macs.forEach { mac ->
                bleManager.setPower(mac, state.isOn)
                if (state.isOn) {
                    bleManager.setColor(mac, state.r, state.g, state.b)
                    bleManager.setBrightness(mac, state.brightness)
                }
            }
        }
    }
}
