package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVLib

interface MpvEngine {
    fun loadFile(uri: String) {
        command("loadfile", uri)
    }

    fun loadFile(uri: String, afterLoadfile: () -> Unit) {
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

object RealMpvEngine : MpvEngine {
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
        MPVLib.destroy()
    }
}

class ViewBackedMpvEngine(
    private val view: MpvFileLoader,
) : MpvEngine {
    override fun loadFile(uri: String) {
        view.playFileWhenReady(uri) {}
    }

    override fun loadFile(uri: String, afterLoadfile: () -> Unit) {
        view.playFileWhenReady(uri, afterLoadfile)
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
