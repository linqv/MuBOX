package org.mubox.reader.video.player

internal class FakeMpvEngine : MpvEngine {
    val commands = mutableListOf<List<String>>()
    val loadedFiles = mutableListOf<String>()
    val requiresSurfaceValues = mutableListOf<Boolean>()
    val stringProperties = mutableMapOf<String, String>()
    val intProperties = mutableMapOf<String, Int>()
    val doubleProperties = mutableMapOf<String, Double>()
    val booleanProperties = mutableMapOf<String, Boolean>()
    val commandFailures = mutableSetOf<List<String>>()
    val booleanPropertyFailures = mutableSetOf<String>()
    var destroyCalls = 0

    private val stringPropertyHistory = mutableMapOf<String, MutableList<String>>()
    private val intPropertyHistory = mutableMapOf<String, MutableList<Int>>()
    private val doublePropertyHistory = mutableMapOf<String, MutableList<Double>>()
    private val optionHistoryMap = mutableMapOf<String, MutableList<String>>()

    override fun loadFile(uri: String) {
        loadedFiles += uri
        commands += listOf("loadfile", uri)
    }

    override fun loadFile(uri: String, requiresSurface: Boolean, afterLoadfile: () -> Unit) {
        requiresSurfaceValues += requiresSurface
        loadFile(uri)
        afterLoadfile()
    }

    override fun command(vararg args: String) {
        if (args.toList() in commandFailures) {
            throw RuntimeException("command failed: ${args.joinToString(" ")}")
        }
        commands += args.toList()
    }

    override fun setPropertyString(name: String, value: String) {
        stringProperties[name] = value
        stringPropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        if (name in booleanPropertyFailures) {
            throw RuntimeException("boolean property failed: $name")
        }
        booleanProperties[name] = value
    }

    override fun setPropertyInt(name: String, value: Int) {
        intProperties[name] = value
        intPropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setPropertyDouble(name: String, value: Double) {
        doubleProperties[name] = value
        doublePropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setOptionString(name: String, value: String) {
        optionHistoryMap.getOrPut(name) { mutableListOf() } += value
    }

    override fun destroy() {
        destroyCalls += 1
    }

    fun stringPropertyHistory(name: String): List<String> = stringPropertyHistory[name].orEmpty()

    fun intPropertyHistory(name: String): List<Int> = intPropertyHistory[name].orEmpty()

    fun doublePropertyHistory(name: String): List<Double> = doublePropertyHistory[name].orEmpty()

    fun optionHistory(name: String): List<String> = optionHistoryMap[name].orEmpty()
}
