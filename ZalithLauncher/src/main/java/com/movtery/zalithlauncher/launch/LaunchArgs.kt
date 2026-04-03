package com.movtery.zalithlauncher.launch

import android.content.Context
import androidx.collection.ArrayMap
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.plugins.renderer.ApkRendererPlugin
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.LibPath
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.AWTCanvasView
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.Runtime
import net.kdt.pojavlaunch.utils.JSONUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.io.File

class LaunchArgs(
    private val context: Context,
    private val account: MinecraftAccount,
    private val gameDirPath: File,
    private val minecraftVersion: Version,
    private val versionInfo: JMinecraftVersionList.Version,
    private val versionFileName: String,
    private val runtime: Runtime,
    private val launchClassPath: String
) {
    companion object {
        private const val MOBILE_GLUES_PACKAGE = "com.fcl.plugin.mobileglues"

        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            val argsList: MutableList<String> = ArrayList()

            argsList.add("-Djava.awt.headless=false")
            argsList.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT)
            argsList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
            argsList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
            argsList.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
            if (isJava8) {
                argsList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
            } else {
                argsList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
                argsList.add("-javaagent:" + LibPath.CACIO_17_AGENT.absolutePath)
                argsList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.util=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.net=ALL-UNNAMED")
            }

            val cacioClassPath = StringBuilder()
            cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")
            val cacioFiles = if (isJava8) LibPath.CACIO_8 else LibPath.CACIO_17
            cacioFiles.listFiles()?.forEach {
                if (it.name.endsWith(".jar")) {
                    cacioClassPath.append(":").append(it.absolutePath)
                }
            }

            argsList.add(cacioClassPath.toString())
            return argsList
        }
    }

    fun getAllArgs(): List<String> {
        enforceMobileGluesRequirement()

        val argsList: MutableList<String> = ArrayList()
        val lwjglComponent = Tools.resolveLWJGLComponentForLaunch(minecraftVersion, versionInfo)

        argsList.addAll(getJavaArgs(lwjglComponent))
        argsList.addAll(getMinecraftJVMArgs())
        argsList.add("-cp")
        argsList.add("${Tools.getLWJGLClassPathForLaunch(minecraftVersion, versionInfo)}:$launchClassPath")

        if (runtime.javaVersion > 8) {
            argsList.add("--add-exports")
            val pkg = versionInfo.mainClass.substring(0, versionInfo.mainClass.lastIndexOf("."))
            argsList.add("$pkg/$pkg=ALL-UNNAMED")
        }

        argsList.add(versionInfo.mainClass)
        argsList.addAll(getMinecraftClientArgs())
        return argsList
    }

    private fun enforceMobileGluesRequirement() {
        if (!requiresMobileGlues(versionInfo)) {
            return
        }

        if (!isMobileGluesInstalled()) {
            throw IllegalStateException(
                "Mobile Glues is required for Minecraft versions above 1.16.5, but it is not installed."
            )
        }

        if (!isMobileGluesSelected()) {
            throw IllegalStateException(
                "Mobile Glues is required for Minecraft versions above 1.16.5, but it is not selected as the active renderer."
            )
        }
    }

    private fun isMobileGluesInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MOBILE_GLUES_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isMobileGluesSelected(): Boolean {
        val plugin = RendererPluginManager.selectedRendererPlugin as? ApkRendererPlugin
        return plugin?.packageName == MOBILE_GLUES_PACKAGE
    }

    private fun requiresMobileGlues(version: JMinecraftVersionList.Version): Boolean {
        val rawVersion = version.id?.trim().orEmpty()
        val match = Regex("""^1\.(\d+)(?:\.(\d+))?$""").matchEntire(rawVersion) ?: return true

        val minor = match.groupValues[1].toIntOrNull() ?: return true
        val patch = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

        return when {
            minor < 16 -> false
            minor > 16 -> true
            else -> patch > 5
        }
    }

    private fun getJavaArgs(lwjglComponent: String): List<String> {
        val argsList: MutableList<String> = ArrayList()

        if (AccountUtils.isOtherLoginAccount(account)) {
            if (account.otherBaseUrl.contains("auth.mc-user.com")) {
                argsList.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=${account.otherBaseUrl.replace("https://auth.mc-user.com:233/", "")}")
                argsList.add("-Dnide8auth.client=true")
            } else {
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=${account.otherBaseUrl}")
            }
        }

        argsList.addAll(getCacioJavaArgs(runtime.javaVersion == 8))

        val is7 = VersionNumber.compare(VersionNumber.asVersion(versionInfo.id ?: "0.0").canonical, "1.12") < 0
        val configFilePath = if (is7) LibPath.LOG4J_XML_1_7 else LibPath.LOG4J_XML_1_12
        argsList.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")

        val lwjglNativeDir = File(PathManager.DIR_FILE, "$lwjglComponent/natives/arm64-v8a")
        val nativePathParts = ArrayList<String>()

        if (lwjglNativeDir.exists()) {
            nativePathParts.add(lwjglNativeDir.absolutePath)
            argsList.add("-Dorg.lwjgl.librarypath=${lwjglNativeDir.absolutePath}")
        }

        val versionSpecificNativesDir = File(PathManager.DIR_CACHE, "natives/${minecraftVersion.getVersionName()}")
        if (versionSpecificNativesDir.exists()) {
            nativePathParts.add(versionSpecificNativesDir.absolutePath)
            argsList.add("-Djna.boot.library.path=${versionSpecificNativesDir.absolutePath}")
        }

        nativePathParts.add(PathManager.DIR_NATIVE_LIB)
        argsList.add("-Djava.library.path=${nativePathParts.joinToString(":")}")

        return argsList
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val resolvedVersionInfo = Tools.getVersionInfo(minecraftVersion, true)

        val varArgMap: MutableMap<String, String?> = android.util.ArrayMap()
        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = resolvedVersionInfo.id
        varArgMap["natives_directory"] = PathManager.DIR_NATIVE_LIB

        val minecraftArgs: MutableList<String> = ArrayList()
        resolvedVersionInfo.arguments?.let {
            fun String.addIgnoreListIfHas(): String {
                return if (startsWith("-DignoreList=")) "$this,$versionFileName.jar" else this
            }

            it.jvm?.forEach { arg ->
                if (arg is String) {
                    val normalized = arg.addIgnoreListIfHas()
                    if (!isConflictingNativeJvmArg(normalized)) {
                        minecraftArgs.add(normalized)
                    }
                }
            }
        }
        return JSONUtils.insertJSONValueList(minecraftArgs.toTypedArray(), varArgMap)
    }

    private fun isConflictingNativeJvmArg(arg: String): Boolean {
        return arg.startsWith("-Djava.library.path=") ||
                arg.startsWith("-Dorg.lwjgl.librarypath=") ||
                arg.startsWith("-Djna.boot.library.path=") ||
                arg.startsWith("-Djna.tmpdir=") ||
                arg.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=") ||
                arg.startsWith("-Dio.netty.native.workdir=")
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val verArgMap: MutableMap<String, String> = ArrayMap()
        verArgMap["auth_session"] = account.accessToken
        verArgMap["auth_access_token"] = account.accessToken
        verArgMap["auth_player_name"] = account.username
        verArgMap["auth_uuid"] = account.profileId.replace("-", "")
        verArgMap["auth_xuid"] = account.xuid
        verArgMap["assets_root"] = ProfilePathHome.getAssetsHome()
        verArgMap["assets_index_name"] = versionInfo.assets
        verArgMap["game_assets"] = ProfilePathHome.getAssetsHome()
        verArgMap["game_directory"] = gameDirPath.absolutePath
        verArgMap["user_properties"] = "{}"
        verArgMap["user_type"] = "msa"
        verArgMap["version_name"] = versionInfo.inheritsFrom ?: versionInfo.id

        setLauncherInfo(verArgMap)

        val minecraftArgs: MutableList<String> = ArrayList()
        versionInfo.arguments?.game?.forEach {
            if (it is String) {
                minecraftArgs.add(it)
            }
        }

        return JSONUtils.insertJSONValueList(
            splitAndFilterEmpty(
                versionInfo.minecraftArguments ?: Tools.fromStringArray(minecraftArgs.toTypedArray())
            ),
            verArgMap
        )
    }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        verArgMap["launcher_version"] = ZHTools.getVersionName()
        verArgMap["version_type"] = minecraftVersion.getCustomInfo()
            .takeIf { it.isNotEmpty() && it.isNotBlank() }
            ?: versionInfo.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        val list: MutableList<String> = ArrayList()
        arg.split(" ").forEach {
            if (it.isNotEmpty()) {
                list.add(it)
            }
        }
        return list.toTypedArray()
    }
}
