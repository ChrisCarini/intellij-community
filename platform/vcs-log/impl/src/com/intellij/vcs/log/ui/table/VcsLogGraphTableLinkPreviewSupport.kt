// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.table

import com.intellij.lang.documentation.ide.impl.DocumentationManager
import com.intellij.lang.documentation.ide.impl.DocumentationManager.DocumentationOnHoverSession
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcs.log.ui.table.links.IssueLinkTag
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.concurrent.Callable
import java.util.function.Consumer

internal class VcsLogGraphTableLinkPreviewSupport(private val table: VcsLogGraphTable) : MouseAdapter() {

  private var session: LinkPreviewSession? = null

  override fun mouseMoved(e: MouseEvent) {
    if (table.getRowCount() == 0) return
    if (table.isResizingColumns) return

    val row = table.rowAtPoint(e.getPoint())
    val tag = table.getTagAt(e)
    if (tag is IssueLinkTag) {
      startShowingDoc(tag, row)
    }
    else
      session?.documentationSession?.mouseOutsideOfSourceArea()
  }

  override fun mouseExited(e: MouseEvent?) {
    session?.documentationSession?.mouseOutsideOfSourceArea()
  }

  private fun startShowingDoc(tag: IssueLinkTag, row: Int) {
    ReadAction
      .nonBlocking(Callable {
        Optional.ofNullable(tag.documentationTarget)
          .map { it.createPointer() }
      })
      .finishOnUiThread(ModalityState.any(), Consumer { pointerOptional ->
        val target = pointerOptional.map { it.dereference() }.orElse(null)
        if (target != null) {
          session?.let {session ->
            if (tag.issueId == session.issueId && row == session.row) {
              session.documentationSession.mouseWithinSourceArea()
              return@Consumer
            }
            if (!session.documentationSession.tryFinishImmediately()) {
              return@Consumer
            }
          }
          val project = tag.project
          if (project.isDisposed || !table.isShowing) return@Consumer
          val cellRect: Rectangle = table.getCellRect(row, 0, false)
          cellRect.x = 0
          cellRect.width = table.getWidth()
          project.service<DocumentationManager>()
            .showDocumentationOnHoverAround(listOf(target), project, table, cellRect, 350) {
              session = null
            }?.let {
              session = LinkPreviewSession(project, tag.issueId, row, it)
            }
        }
        else
          session?.documentationSession?.mouseOutsideOfSourceArea()
      }).submit(AppExecutorUtil.getAppExecutorService())
  }

  private data class LinkPreviewSession(
    val project: Project,
    val issueId: String,
    val row: Int,
    val documentationSession: DocumentationOnHoverSession,
  )

}