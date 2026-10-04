package dev.aigw.app.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.aigw.app.gatewayEngine

/**
 * 控制中心的网关开关磁贴。
 *
 * 状态以引擎实时查询为准（isRunning），不维护本地布尔——磁贴可能被系统
 * 在任意时刻刷新（下拉控制中心时），引擎是唯一的真值来源。
 * 点击切换：开 → 拉起前台服务 + 启动引擎；关 → 停引擎 + 撤服务。
 */
class GatewayTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val engine = runCatching { gatewayEngine }.getOrNull() ?: return
        val tile = qsTile ?: return
        if (engine.isRunning()) {
            engine.stop()
            applicationContext.stopService(Intent(applicationContext, GatewayService::class.java))
            engine.requestLog.info("控制中心磁贴停止了网关")
        } else {
            // 磁贴点击允许直接拉前台服务（用户交互），引擎启动失败再撤
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, GatewayService::class.java),
            )
            val ok = runCatching { engine.start() }.isSuccess
            if (!ok) {
                applicationContext.stopService(Intent(applicationContext, GatewayService::class.java))
            }
            engine.requestLog.info(if (ok) "控制中心磁贴启动了网关" else "磁贴启动网关失败")
        }
        updateTile()
        // 主界面可能正开着：让它的状态也刷新
        sendBroadcast(Intent(ACTION_GATEWAY_TOGGLED).setPackage(packageName))
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = runCatching { gatewayEngine.isRunning() }.getOrDefault(false)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (running) {
            val port = runCatching { gatewayEngine.settings().port }.getOrDefault(0)
            tile.subtitle = if (port > 0) "端口 $port" else null
        } else {
            tile.subtitle = null
        }
        tile.updateTile()
    }

    companion object {
        /** 服务启停后通知系统刷新本磁贴。 */
        const val ACTION_GATEWAY_TOGGLED = "dev.aigw.app.GATEWAY_TOGGLED"
    }
}
