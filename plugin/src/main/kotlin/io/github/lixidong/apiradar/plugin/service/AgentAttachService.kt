package io.github.lixidong.apiradar.plugin.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 负责把 api-radar-agent.jar 注入到目标 JVM。
 *
 * <p>实现：使用 JDK Attach API（{@code com.sun.tools.attach.VirtualMachine}）。
 * IDEA 使用 JBR，自带 jdk.attach 模块，可直接用。
 */
@Service(Service.Level.APP)
class AgentAttachService {

    private val log = thisLogger()

    /**
     * 列出本机所有可附加的 Java 进程。
     */
    fun listJavaProcesses(): List<VirtualMachineDescriptor> {
        return VirtualMachine.list()
    }

    /**
     * 将 agent 注入到指定 PID 的 JVM。
     * 启动参数从 [ApiRadarSettings] 读取，保证安装时与运行时一致。
     */
    fun attach(pid: String, port: Int) {
        val agentJar = extractAgentJar()
        val args = buildAgentArgs(port)
        log.info("attaching agent ${agentJar.toAbsolutePath()} to pid=$pid args=$args")

        val vm = VirtualMachine.attach(pid)
        try {
            vm.loadAgent(agentJar.toAbsolutePath().toString(), args)
            log.info("agent attached to pid=$pid")
        } finally {
            vm.detach()
        }
    }

    private fun buildAgentArgs(port: Int): String {
        val s = ApiRadarSettings.getInstance()
        val parts = mutableListOf(
            "port=$port",
            "maxBodyBytes=${s.maxBodyBytes}",
        )
        if (s.skipBodyOverBytes > 0) {
            parts.add("skipBodyOverBytes=${s.skipBodyOverBytes}")
        }
        if (s.includes.isNotEmpty()) {
            parts.add("includes=${s.includes.joinToString(";")}")
        }
        if (s.excludes.isNotEmpty()) {
            parts.add("excludes=${s.excludes.joinToString(";")}")
        }
        return parts.joinToString(",")
    }

    /**
     * 把 plugin 资源里的 agent jar 释放到临时目录，attach 时需要文件路径。
     */
    private fun extractAgentJar(): Path {
        val tmpDir = Path.of(PathManager.getTempPath(), "api-radar")
        Files.createDirectories(tmpDir)
        val target = tmpDir.resolve("api-radar-agent.jar")

        val resource: InputStream = javaClass.classLoader.getResourceAsStream("agent/api-radar-agent.jar")
            ?: throw IllegalStateException("agent jar not bundled in plugin: agent/api-radar-agent.jar")

        resource.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }
}
