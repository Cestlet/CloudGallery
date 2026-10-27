package com.cloudgallery.app.data

/** Metadata only: no coordinates and no platform actions. */
internal data class ImportActionNode(
    val labels: List<String> = emptyList(), val clickable: Boolean = false,
    val enabled: Boolean = true, val visible: Boolean = true,
    val packageName: String = "com.xingin.xhs", val children: List<Int> = emptyList(),
    val resourceId: String = "", val className: String = ""
)
internal enum class ImportAction(val labels: Set<String>) {
    SHARE(setOf("分享", "Share")), COPY(setOf("复制链接", "Copy link"))
}

/** A button or dedicated wrapper with exactly one action and only the target label. */
internal fun importActionTarget(nodes: List<ImportActionNode>, action: ImportAction): Int? {
    fun matches(index: Int): Boolean {
        val root = nodes.getOrNull(index) ?: return false
        if (!root.clickable || !root.enabled || !root.visible) return false
        val visited = mutableSetOf<Int>()
        var foundLabel = action == ImportAction.SHARE &&
            root.resourceId == "com.xingin.xhs:id/moreOperateIV" &&
            root.className == "android.widget.ImageView" && root.children.isEmpty()
        fun visit(i: Int): Boolean {
            if (!visited.add(i) || visited.size > 12) return false
            val node = nodes.getOrNull(i) ?: return false
            if (node.packageName != "com.xingin.xhs" || !node.enabled || !node.visible) return false
            if (i != index && node.clickable) return false
            for (label in node.labels.map(String::trim).filter(String::isNotEmpty)) {
                if (label !in action.labels) return false
                foundLabel = true
            }
            return node.children.all(::visit)
        }
        return visit(index) && foundLabel
    }
    val targets = nodes.indices.filter(::matches).toMutableSet()
    // XHS omits child labels in the service tree: a labelled non-clickable
    // Button owns one clickable child. Some versions expose a plain View/ViewGroup
    // instead: COPY may use that anchor only when its sole child is a clickable
    // leaf. Never infer a share action from an unlabelled child or mixed container.
    nodes.forEachIndexed { index, root ->
        val labels = root.labels.map(String::trim).filter(String::isNotEmpty)
        if (root.clickable || root.children.size != 1 ||
            labels.isEmpty() || labels.any { it !in action.labels } || !root.enabled || !root.visible) return@forEachIndexed
        val child = nodes.getOrNull(root.children.single()) ?: return@forEachIndexed
        val dedicatedCopyLeaf = action == ImportAction.COPY && child.clickable && child.children.isEmpty()
        if (root.className != "android.widget.Button" && !dedicatedCopyLeaf) return@forEachIndexed
        val visited = mutableSetOf<Int>()
        val clicks = mutableListOf<Int>()
        fun inspect(i: Int): Boolean {
            if (!visited.add(i) || visited.size > 12) return false
            val node = nodes.getOrNull(i) ?: return false
            if (node.packageName != "com.xingin.xhs" || !node.enabled || !node.visible ||
                node.labels.map(String::trim).any { it.isNotEmpty() && it !in action.labels }) return false
            if (node.clickable) clicks.add(i)
            return node.children.all(::inspect)
        }
        if (inspect(index) && clicks.size == 1) targets.add(clicks.single())
    }
    return targets.singleOrNull()
}

/** Local diagnostic labels are restricted to known controls, never note/user text. */
internal fun importActionDiagnostic(nodes: List<ImportActionNode>): String {
    val controls = ImportAction.entries.flatMap { it.labels }.toSet() +
        setOf("更多", "More", "朋友圈", "分享到朋友圈", "微信", "QQ", "微博", "取消", "Cancel")
    val matches = ImportAction.entries.joinToString(" ") { action ->
        "${action.name.lowercase()}Target=${importActionTarget(nodes, action) ?: "none"}"
    }
    return matches + "\n" + nodes.mapIndexed { index, node ->
        val labels = node.labels.map(String::trim).filter(String::isNotEmpty)
        val type = when {
            node.className.isBlank() -> "unknown"
            node.className.length <= 120 && Regex("^(?:android|androidx)\\.[A-Za-z0-9_.$]+$").matches(node.className) -> node.className
            else -> "custom"
        }
        "$index labels=${labels.filter { it in controls }} otherLabels=${labels.count { it !in controls }} " +
            "click=${node.clickable} enabled=${node.enabled} visible=${node.visible} " +
            "xhs=${node.packageName == "com.xingin.xhs"} class=$type children=${node.children}"
    }.joinToString("\n").take(48_000)
}
