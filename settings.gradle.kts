rootProject.name = "SportCDN-Cloudstream"

val disabled = listOf("__Temel", "__PlayerTest", "ExampleProvider")

File(rootDir, "extensions").eachDir { dir ->
    val buildFile = File(dir, "build.gradle.kts")
    if (!disabled.contains(dir.name) && buildFile.exists()) {
        val content = buildFile.readText()
        val isInactive = content.contains("status\\s*=\\s*0".toRegex())

        if (!isInactive) {
            include(":extensions:${dir.name}")
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}