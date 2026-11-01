package com.labsage.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineCitationLinksTest {

    private fun citation(ref: String) = MessageReference(
        ref = ref, chunkId = "ck", source = "s.md", title = "t", contentHash = "h"
    )

    @Test
    fun rewritesRegisteredMarkersInline() {
        val result = inlineCitationLinks(
            "采样协议要求[1]每 500 ms 落盘，结论见[2]。",
            listOf(citation("1"), citation("2"))
        )
        assertEquals(
            "采样协议要求[1](labsage://citation/1)每 500 ms 落盘，结论见[2](labsage://citation/2)。",
            result
        )
    }

    @Test
    fun leavesUnregisteredAndUnrelatedBracketsAlone() {
        val content = "未登记编号[9]、数组 [1, 2]、普通文本不变。"
        assertEquals(content, inlineCitationLinks(content, listOf(citation("1"))))
    }

    @Test
    fun doesNotTouchExistingMarkdownLinks() {
        val content = "已有链接[1](https://example.com/a)不受影响；裸标记[1]才改写。"
        val result = inlineCitationLinks(content, listOf(citation("1")))
        assertEquals(
            "已有链接[1](https://example.com/a)不受影响；裸标记[1](labsage://citation/1)才改写。",
            result
        )
    }

    @Test
    fun replacesEveryOccurrence() {
        val result = inlineCitationLinks("[1] 和 [1]", listOf(citation("1")))
        assertEquals(
            "[1](labsage://citation/1) 和 [1](labsage://citation/1)",
            result
        )
    }

    @Test
    fun skipsUnsafeRefsAndEmptyCitations() {
        // ref 含 URL 特殊字符 → 整体跳过，避免拼接出歧义链接
        val content = "标记[a b]保持原样"
        assertEquals(content, inlineCitationLinks(content, listOf(citation("a b"))))
        assertEquals(content, inlineCitationLinks(content, emptyList()))
    }

    @Test
    fun referenceStyleMarkerIsNotRewritten() {
        // markdown 引用块语法 "…[1]:" 或脚注 "[1]:" 不动
        val content = "见脚注[1]: 说明"
        val result = inlineCitationLinks(content, listOf(citation("1")))
        assertEquals("见脚注[1]: 说明", result)
    }
}
