package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Filters
import maestro.Maestro
import maestro.TreeNode
import maestro.orchestra.Orchestra
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import maestro.orchestra.MaestroCommand
import maestro.orchestra.yaml.FlowParseException
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlCommandReader

private data class RunCommandRequest(
    val yaml: String,
    val dryRun: Boolean?,
)

private data class FormatCommandsRequest(
    val commands: List<String>,
)

private data class FormattedFlow(
    val config: String,
    val commands: String,
)

object DeviceService {

    private const val MAX_SCREENSHOTS = 10

    private val SCREENSHOT_DIR = getScreenshotDir()

    private val savedScreenshots = mutableListOf<File>()

    private var lastViewHierarchy: TreeNode? = null

    @Volatile
    private var cachedDeviceInfo: DeviceInfo? = null

    fun routes(routing: Routing, maestro: Maestro) {
        routing.post("/api/run-command") {
            val request = call.parseBody<RunCommandRequest>()
            try {
                val commands = MaestroFlowParser.parseCommand(Paths.get(""), "", request.yaml)
                if (request.dryRun != true) {
                    executeCommands(maestro, commands)
                }
                val response = jacksonObjectMapper().writeValueAsString(commands)
                call.respond(response)
            } catch (e: FlowParseException) {
                call.respond(HttpStatusCode.BadRequest, listOfNotNull(e.errorMessage, e.docs).joinToString("\n"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Failed to run command")
            }
        }
        routing.post("/api/format-flow") {
            val request = call.parseBody<FormatCommandsRequest>()
            val commands = request.commands.map { YamlCommandReader.readSingleCommand(Paths.get(""), "", it) }
            val inferredAppId = commands.flatten().firstNotNullOfOrNull { it.launchAppCommand?.appId }
            val commandsString = YamlCommandReader.formatCommands(request.commands)
            val formattedFlow = FormattedFlow("appId: $inferredAppId", commandsString)
            val response = jacksonObjectMapper().writeValueAsString(formattedFlow)
            call.respondText(response)
        }
        // Ktor SSE sample project: https://github.com/ktorio/ktor-samples/blob/main/sse/src/main/kotlin/io/ktor/samples/sse/SseApplication.kt
        routing.get("/api/device-screen/sse") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                while (true) {
                    try {
                        val deviceScreen = getDeviceScreen(maestro)
                        writeStringUtf8("data: $deviceScreen\n\n")
                        flush()
                    } catch (_: Exception) {
                        // Ignoring the exception to prevent SSE stream from dying
                    }
                    delay(100)
                }
            }
        }
        routing.get("/api/last-view-hierarchy") {
            if (lastViewHierarchy == null) {
                call.respond(HttpStatusCode.NotFound, "No view hierarchy available")
            } else {
                val response = jacksonObjectMapper().writeValueAsString(lastViewHierarchy)
                call.respond(response)
            }
        }
        routing.static("/screenshot") {
            staticRootFolder = SCREENSHOT_DIR.toFile()
            files(".")
        }
    }

    private fun executeCommands(maestro: Maestro, commands: List<MaestroCommand>) {
        runBlocking {
            var failure: Throwable? = null
            val result = Orchestra(maestro, onCommandFailed = { _, _, throwable ->
                failure = throwable
                Orchestra.ErrorResolution.FAIL
            }).runFlow(commands)
            if (failure != null) {
                throw RuntimeException("Command execution failed")
            }
        }
    }

    private fun treeToElements(tree: TreeNode): List<UIElement> {
        fun gatherElements(tree: TreeNode, list: MutableList<TreeNode>): List<TreeNode> {
            tree.children.forEach { child ->
                gatherElements(child, list)
            }
            list.add(tree)
            return list
        }

        fun TreeNode.attribute(key: String): String? {
            val value = attributes[key]
            if (value.isNullOrEmpty()) return null
            return value
        }

        val elements = gatherElements(tree, mutableListOf())
            .sortedWith(Filters.INDEX_COMPARATOR)

        // Pre-group elements by text and resourceId for O(n) index lookup
        val textGroups = mutableMapOf<String, MutableList<TreeNode>>()
        val resourceIdGroups = mutableMapOf<String, MutableList<TreeNode>>()
        for (element in elements) {
            element.attribute("text")?.let { text ->
                textGroups.getOrPut(text) { mutableListOf() }.add(element)
            }
            element.attribute("resource-id")?.let { resId ->
                resourceIdGroups.getOrPut(resId) { mutableListOf() }.add(element)
            }
        }

        val ids = mutableMapOf<String, Int>()
        return elements.map { element ->
            val bounds = element.bounds()
            val text = element.attribute("text")
            val hintText = element.attribute("hintText")
            val accessibilityText = element.attribute("accessibilityText")
            val resourceId = element.attribute("resource-id")
            val textIndex = if (text == null) {
                null
            } else {
                val group = textGroups[text]!!
                if (group.size < 2) null else group.indexOf(element)
            }
            val resourceIdIndex = if (resourceId == null) {
                null
            } else {
                val group = resourceIdGroups[resourceId]!!
                if (group.size < 2) null else group.indexOf(element)
            }
            fun createElementId(): String {
                val parts = listOfNotNull(resourceId, resourceIdIndex, text, textIndex)
                val fallbackId = bounds?.let { (x, y, w, h) -> "$x,$y,$w,$h" } ?: UUID.randomUUID().toString()
                val id = if (parts.isEmpty()) fallbackId else parts.joinToString("-")
                val index = ids.compute(id) { _, i -> (i ?: 0) + 1}
                return if (index == 1) id else "$id-$index"
            }
            val id = createElementId()
            UIElement(id, bounds, resourceId, resourceIdIndex, text, hintText, accessibilityText, textIndex)
        }
    }

    private fun getDeviceScreen(maestro: Maestro): String {
        val deviceInfo = cachedDeviceInfo ?: maestro.deviceInfo().also { cachedDeviceInfo = it }

        val (tree, screenshotFile) = runBlocking {
            val hierarchyDeferred = async(Dispatchers.IO) { maestro.viewHierarchy().root }
            val screenshotDeferred = async(Dispatchers.IO) { takeScreenshot(maestro) }
            Pair(hierarchyDeferred.await(), screenshotDeferred.await())
        }

        lastViewHierarchy = tree
        synchronized(DeviceService) {
            savedScreenshots.add(screenshotFile)
            while (savedScreenshots.size > MAX_SCREENSHOTS) {
                savedScreenshots.removeFirst().delete()
            }
        }

        val url = tree.attributes["url"]
        val elements = treeToElements(tree)
        val deviceScreen = DeviceScreen(deviceInfo.platform, "/screenshot/${screenshotFile.name}", deviceInfo.widthGrid, deviceInfo.heightGrid, elements, url)
        return jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writeValueAsString(deviceScreen)
    }

    private val BOUNDS_PATTERN = Pattern.compile("\\[([0-9-]+),([0-9-]+)]\\[([0-9-]+),([0-9-]+)]")

    private fun TreeNode.bounds(): UIElementBounds? {
        val boundsString = attributes["bounds"] ?: return null
        val m = BOUNDS_PATTERN.matcher(boundsString)
        if (!m.matches()) {
            System.err.println("Warning: Bounds text does not match expected pattern: $boundsString")
            return null
        }

        val l = m.group(1).toIntOrNull() ?: return null
        val t = m.group(2).toIntOrNull() ?: return null
        val r = m.group(3).toIntOrNull() ?: return null
        val b = m.group(4).toIntOrNull() ?: return null

        return UIElementBounds(
            x = l,
            y = t,
            width = r - l,
            height = b - t,
        )
    }

    private fun takeScreenshot(maestro: Maestro): File {
        val name = "${UUID.randomUUID()}.png"
        val screenshotFile = SCREENSHOT_DIR.resolve(name).toFile()
        screenshotFile.deleteOnExit()
        try {
            maestro.takeScreenshot(screenshotFile, true)
        } catch (ignore: Exception) {
            // ignore intermittent screenshot errors
        }
        return screenshotFile
    }

    private fun getScreenshotDir(): Path {
        val home = System.getProperty("user.home")
        val parent = if (home.isNullOrBlank()) createTempDirectory() else Path(home)
        val screenshotDir = parent.resolve(".maestro/studio/screenshots")
        screenshotDir.createDirectories()
        return screenshotDir
    }
}
