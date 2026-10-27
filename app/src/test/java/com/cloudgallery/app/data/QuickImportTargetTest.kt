package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test

class QuickImportTargetTest {
    private fun button(label: String) = ImportActionNode(listOf(label), clickable = true)
    @Test fun selectsCopyButtonBesideMomentsWithoutSelectingTheirParent() {
        val nodes = listOf(ImportActionNode(clickable = true, children = listOf(1, 2)), button("朋友圈"), button("复制链接"))
        assertEquals(2, importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun rejectsSharedClickableContainerWithNonClickableLabels() {
        val nodes = listOf(ImportActionNode(clickable = true, children = listOf(1, 2)), ImportActionNode(listOf("朋友圈")), ImportActionNode(listOf("复制链接")))
        assertNull(importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun allowsDedicatedCopyWrapperWithLabelAndUnlabelledIcon() {
        val nodes = listOf(ImportActionNode(clickable = true, children = listOf(1, 2)), ImportActionNode(listOf("复制链接")), ImportActionNode())
        assertEquals(0, importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun rejectsNestedUnknownClickableIcon() {
        val nodes = listOf(ImportActionNode(clickable = true, children = listOf(1, 2)), ImportActionNode(listOf("复制链接")), ImportActionNode(clickable = true))
        assertNull(importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun rejectsAmbiguousDuplicateButtons() {
        assertNull(importActionTarget(listOf(button("复制链接"), button("复制链接")), ImportAction.COPY))
    }
    @Test fun ignoresInvisibleDisabledAndForeignButtons() {
        for (node in listOf(button("复制链接").copy(visible = false), button("复制链接").copy(enabled = false), button("复制链接").copy(packageName = "com.tencent.mm")))
            assertNull(importActionTarget(listOf(node), ImportAction.COPY))
    }
    @Test fun rejectsForeignDescendant() {
        assertNull(importActionTarget(listOf(ImportActionNode(clickable = true, children = listOf(1)), ImportActionNode(listOf("复制链接"), packageName = "com.tencent.mm")), ImportAction.COPY))
    }
    @Test fun neverInfersShareFromPositionMoreOrMomentLabels() {
        for (label in listOf("", "更多", "分享到朋友圈", "朋友圈", "微信", "Share to Moments"))
            assertNull(importActionTarget(listOf(button(label)), ImportAction.SHARE))
    }
    @Test fun requiresExactCopyLabelEvenWhenTextAndDescriptionDiffer() {
        assertNull(importActionTarget(listOf(button("复制链接").copy(labels = listOf("复制链接", "朋友圈"))), ImportAction.COPY))
        assertNull(importActionTarget(listOf(button("复制链接并分享")), ImportAction.COPY))
    }
    @Test fun shareAndCopyAreSeparateActions() {
        assertEquals(0, importActionTarget(listOf(button("分享")), ImportAction.SHARE))
        assertNull(importActionTarget(listOf(button("分享")), ImportAction.COPY))
        assertEquals(0, importActionTarget(listOf(button("Copy link")), ImportAction.COPY))
    }
    @Test fun rejectsCyclesAndInvalidReferences() {
        assertNull(importActionTarget(listOf(button("复制链接").copy(children = listOf(0))), ImportAction.COPY))
        assertNull(importActionTarget(listOf(button("复制链接").copy(children = listOf(5))), ImportAction.COPY))
    }
    @Test fun rejectsLargeContainers() {
        val nodes = listOf(button("复制链接").copy(children = (1..12).toList())) + List(12) { ImportActionNode() }
        assertNull(importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun diagnosticNeverIncludesNoteTextUserNamesOrLinks() {
        val report = importActionDiagnostic(listOf(ImportActionNode(listOf("复制链接", "Alice", "secret note text", "https://xhslink.com/private"))))
        assertTrue(report.contains("复制链接"))
        assertTrue(report.contains("otherLabels=3"))
        for (privateText in listOf("Alice", "secret note text", "https://")) assertFalse(report.contains(privateText))
    }
    @Test fun acceptsOnlyVerifiedShareIdAndClassWithoutText() {
        val share = ImportActionNode(clickable = true, resourceId = "com.xingin.xhs:id/moreOperateIV", className = "android.widget.ImageView")
        assertEquals(0, importActionTarget(listOf(share), ImportAction.SHARE))
        assertNull(importActionTarget(listOf(share), ImportAction.COPY))
        for (wrong in listOf(share.copy(resourceId = "com.xingin.xhs:id/0_resource_name_obfuscated"), share.copy(className = "android.view.ViewGroup"), share.copy(packageName = "com.tencent.mm")))
            assertNull(importActionTarget(listOf(wrong), ImportAction.SHARE))
    }
    @Test fun selectsRealCopyChildInsideNonClickableSemanticButton() {
        val nodes = listOf(
            ImportActionNode(listOf("复制链接"), children = listOf(1)),
            ImportActionNode(clickable = true, children = listOf(2)),
            ImportActionNode(children = listOf(3, 4)), ImportActionNode(), ImportActionNode(listOf("复制链接")))
        assertEquals(1, importActionTarget(nodes, ImportAction.COPY))
    }
    @Test fun selectsServiceCompressedCopyChildByItsDedicatedButtonAnchor() {
        val nodes = listOf(ImportActionNode(listOf("复制链接"), children = listOf(1), className = "android.widget.Button"), ImportActionNode(clickable = true))
        assertEquals(1, importActionTarget(nodes, ImportAction.COPY))
        assertEquals(1, importActionTarget(listOf(nodes[0].copy(className = "android.view.ViewGroup"), nodes[1]), ImportAction.COPY))
    }
    @Test fun anchorCannotContainMultipleShareTargetsOrClickableChildren() {
        val root = ImportActionNode(listOf("复制链接"), children = listOf(1), className = "android.widget.Button")
        val child = ImportActionNode(clickable = true, children = listOf(2))
        assertNull(importActionTarget(listOf(root, child, ImportActionNode(listOf("朋友圈"))), ImportAction.COPY))
        assertNull(importActionTarget(listOf(root, child, ImportActionNode(clickable = true)), ImportAction.COPY))
        assertNull(importActionTarget(listOf(root.copy(children = listOf(1, 2)), ImportActionNode(clickable = true), ImportActionNode()), ImportAction.COPY))
    }
    @Test fun replaysVivoApi36MenuAndSelectsOnlyNode17() {
        // Exact 23-node shape supplied by the user; unknown labels stay unknown.
        val nodes = MutableList(23) { ImportActionNode() }
        nodes[0] = ImportActionNode(children = listOf(1, 2, 3, 13))
        nodes[1] = ImportActionNode(listOf("other-label"))
        nodes[2] = button("other-label")
        nodes[3] = ImportActionNode(children = listOf(4, 5, 7, 9, 11))
        nodes[13] = ImportActionNode(children = listOf(14, 16, 18, 19, 21))
        for (index in listOf(4, 6, 8, 10, 12, 15, 17, 18, 20, 22)) nodes[index] = ImportActionNode(clickable = true)
        for (index in listOf(5, 7, 9, 11, 14, 19, 21)) nodes[index] = ImportActionNode(listOf("other-label"), children = listOf(index + 1))
        for (type in listOf("", "android.view.View", "android.view.ViewGroup", "android.widget.Button")) {
            nodes[16] = ImportActionNode(listOf("复制链接"), children = listOf(17), className = type)
            assertEquals(17, importActionTarget(nodes, ImportAction.COPY))
            assertNull(importActionTarget(nodes, ImportAction.SHARE))
        }
    }
    @Test fun plainCopyAnchorStillRejectsSocialDisabledForeignAndAmbiguousChildren() {
        val anchor = ImportActionNode(listOf("复制链接"), children = listOf(1), className = "android.view.ViewGroup")
        val leaf = ImportActionNode(clickable = true)
        for (bad in listOf(leaf.copy(labels = listOf("朋友圈")), leaf.copy(labels = listOf("微信")),
            leaf.copy(enabled = false), leaf.copy(visible = false), leaf.copy(packageName = "com.tencent.mm"),
            leaf.copy(clickable = false))) assertNull(importActionTarget(listOf(anchor, bad), ImportAction.COPY))
        for (bad in listOf(anchor.copy(enabled = false), anchor.copy(visible = false), anchor.copy(packageName = "com.tencent.mm"),
            anchor.copy(labels = listOf("复制链接", "朋友圈")))) assertNull(importActionTarget(listOf(bad, leaf), ImportAction.COPY))
        assertNull(importActionTarget(listOf(anchor, leaf, anchor.copy(children = listOf(3)), leaf), ImportAction.COPY))
    }
    @Test fun plainWrapperCannotBroadenToShareOrTraverseUnknownContainers() {
        val copy = ImportActionNode(listOf("复制链接"), children = listOf(1), className = "android.view.ViewGroup")
        val leaf = ImportActionNode(clickable = true)
        assertNull(importActionTarget(listOf(copy.copy(labels = listOf("分享")), leaf), ImportAction.SHARE))
        assertNull(importActionTarget(listOf(copy.copy(children = listOf(1, 2)), leaf, ImportActionNode()), ImportAction.COPY))
        assertNull(importActionTarget(listOf(copy, leaf.copy(children = listOf(2)), ImportActionNode()), ImportAction.COPY))
        assertNull(importActionTarget(listOf(copy.copy(children = listOf(0))), ImportAction.COPY))
    }
    @Test fun diagnosticShowsClassAndSelectedTargetWithoutCustomText() {
        val nodes = listOf(ImportActionNode(listOf("复制链接"), children = listOf(1), className = "android.view.ViewGroup"),
            ImportActionNode(clickable = true, className = "private note https://example.com Alice"))
        val diagnostic = importActionDiagnostic(nodes)
        assertTrue(diagnostic.contains("copyTarget=1"))
        assertTrue(diagnostic.contains("class=android.view.ViewGroup"))
        assertTrue(diagnostic.contains("class=custom"))
        assertFalse(diagnostic.contains("private note"))
        assertFalse(diagnostic.contains("https://"))
        assertFalse(diagnostic.contains("Alice"))
    }
}
