package io.github.lixidong.apiradar.plugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import io.github.lixidong.apiradar.plugin.service.AgentAttachService
import io.github.lixidong.apiradar.plugin.service.EventBus
import com.sun.tools.attach.VirtualMachineDescriptor

class AttachAgentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val attachService = service<AgentAttachService>()
        val eventBus = service<EventBus>()

        val processes = attachService.listJavaProcesses()
            .filter { !it.displayName().contains("intellij", ignoreCase = true) }
            .filter { !it.displayName().contains("idea", ignoreCase = true) }

        if (processes.isEmpty()) {
            Messages.showWarningDialog(project, "未发现可附加的 Java 进程", "API Radar")
            return
        }

        val labels = processes.map { "${it.id()}  ${displayShortName(it)}" }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("选择目标 Java 进程")
            .setItemChosenCallback { selected ->
                val idx = labels.indexOf(selected)
                if (idx < 0) return@setItemChosenCallback
                val target = processes[idx]
                doAttach(target, attachService, eventBus, project)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun doAttach(
        target: VirtualMachineDescriptor,
        attachService: AgentAttachService,
        eventBus: EventBus,
        project: com.intellij.openapi.project.Project,
    ) {
        try {
            val port = if (eventBus.port == 0) eventBus.start() else eventBus.port
            attachService.attach(target.id(), port)
            Messages.showInfoMessage(
                project,
                "已注入 Agent 到 PID ${target.id()}\n上报端口: $port\n\n" +
                        "可在 Settings → Tools → API Radar 调整阈值与 URL 过滤。",
                "API Radar"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Attach 失败: ${e.message}\n\n如果使用 JDK 21，请确保目标项目启动参数包含:\n  -Djdk.attach.allowAttachSelf=true",
                "API Radar"
            )
        }
    }

    private fun displayShortName(vm: VirtualMachineDescriptor): String {
        val full = vm.displayName()
        // 简化显示：取主类或 jar 名
        return full.substringAfterLast('.').take(120)
    }
}
