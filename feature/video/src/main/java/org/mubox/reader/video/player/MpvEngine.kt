package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVLib

interface MpvEngine {
    fun loadFile(uri: String) {
        command("loadfile", uri)
    }

    /**
     * [requiresSurface] 为 false 时（听视频模式，vid=no 无需视频输出），
     * 即使 Surface 尚未附加也直接下发 loadfile，避免切集被推迟到回到视频界面。
     */
    fun loadFile(uri: String, requiresSurface: Boolean = true, afterLoadfile: () -> Unit) {
        loadFile(uri)
        afterLoadfile()
    }

    fun command(vararg args: String)
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyInt(name: String, value: Int) = Unit
    fun setPropertyDouble(name: String, value: Double) = Unit
    fun setOptionString(name: String, value: String) = Unit
    fun destroy()
}

class ViewBackedMpvEngine(
    private val view: MpvFileLoader,
) : MpvEngine {
    override fun loadFile(uri: String) {
        view.playFileWhenReady(uri) {}
    }

    override fun loadFile(uri: String, requiresSurface: Boolean, afterLoadfile: () -> Unit) {
        view.playFileWhenReady(uri, requiresSurface, afterLoadfile)
    }

    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }

    override fun setPropertyString(name: String, value: String) {
        MPVLib.setPropertyString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun setPropertyInt(name: String, value: Int) {
        MPVLib.setPropertyInt(name, value)
    }

    override fun setPropertyDouble(name: String, value: Double) {
        MPVLib.setPropertyDouble(name, value)
    }

    override fun setOptionString(name: String, value: String) {
        MPVLib.setOptionString(name, value)
    }

    override fun destroy() {
        view.destroy()
    }
}
