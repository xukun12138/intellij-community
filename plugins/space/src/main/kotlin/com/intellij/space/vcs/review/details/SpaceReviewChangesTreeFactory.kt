// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.space.vcs.review.details

import circlet.client.api.GitCommitChangeType
import circlet.client.api.GitFile
import circlet.client.api.isDirectory
import circlet.code.api.ChangeInReview
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ui.*
import com.intellij.space.vcs.review.details.diff.SpaceDiffFile
import com.intellij.space.vcs.review.details.diff.SpaceDiffVm
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.Processor
import com.intellij.util.ui.tree.TreeUtil
import runtime.reactive.Property
import javax.swing.JComponent

internal object SpaceReviewChangesTreeFactory {
  fun create(project: Project,
             parentPanel: JComponent,
             changesVm: SpaceReviewChangesVm,
             spaceDiffVm: Property<SpaceDiffVm>): JComponent {

    val tree = object : ChangesTree(project, false, false) {
      init {
        changesVm.changes.forEach(changesVm.lifetime) {
          it ?: return@forEach
          val builder = TreeModelBuilder(project, grouping)

          it.forEach { (repo, changesWithDiscussion) ->
            val repoNode = RepositoryNode(repo, true)

            val changes = changesWithDiscussion.changesInReview
            addChanges(builder, repoNode, changes)
            updateTreeModel(builder.build())

            if (isSelectionEmpty && !isEmpty) TreeUtil.selectFirstNode(this)
          }
        }
      }

      override fun rebuildTree() {
      }

      override fun getData(dataId: String) = super.getData(dataId) ?: VcsTreeModelData.getData(project, this, dataId)
    }
    tree.doubleClickHandler = Processor { e ->
      if (EditSourceOnDoubleClickHandler.isToggleEvent(tree, e)) return@Processor false

      val spaceDiffFile = SpaceDiffFile(spaceDiffVm.value, changesVm)
      FileEditorManager.getInstance(project).openFile(spaceDiffFile, true)
      true
    }

    tree.addSelectionListener {
        val selection = VcsTreeModelData.getListSelectionOrAll(tree).map { it as? ChangeInReview }
        // do not reset selection to zero
        if (!selection.isEmpty) changesVm.listSelection.value = selection
    }
    DataManager.registerDataProvider(parentPanel) {
      if (tree.isShowing) tree.getData(it) else null
    }
    tree.installPopupHandler(ActionManager.getInstance().getAction("space.review.changes.popup") as ActionGroup)
    return ScrollPaneFactory.createScrollPane(tree, true)
  }

  private fun addChanges(builder: TreeModelBuilder,
                         repositoryNode: ChangesBrowserNode<*>,
                         changesInReview: List<ChangeInReview>) {
    builder.insertSubtreeRoot(repositoryNode)

    changesInReview.forEach { changeInReview: ChangeInReview ->
      val filePath = getFilePath(changeInReview)
      builder.insertChangeNode(
        filePath,
        repositoryNode,
        ReviewChangeNode(changeInReview)
      )
    }
  }
}

internal class RepositoryNode(@NlsSafe val repositoryName: String,
                              val inCurrentProject: Boolean)
  : ChangesBrowserStringNode(repositoryName) {
  init {
    markAsHelperNode()
  }

  override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
    val style = if (inCurrentProject) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
    renderer.append(repositoryName, style)
  }
}

internal class ReviewChangeNode(changeInReview: ChangeInReview)
  : AbstractChangesBrowserFilePathNode<ChangeInReview>(changeInReview, getFileStatus(changeInReview)) {

  private val filePath: FilePath = getFilePath(changeInReview)

  override fun filePath(userObject: ChangeInReview): FilePath = filePath
}

private fun getFileStatus(changeInReview: ChangeInReview): FileStatus = when (changeInReview.change.changeType) {
  GitCommitChangeType.ADDED -> FileStatus.ADDED
  GitCommitChangeType.DELETED -> FileStatus.DELETED
  GitCommitChangeType.MODIFIED -> FileStatus.MODIFIED
}

fun getFilePath(changeInReview: ChangeInReview): FilePath {
  val path = when (changeInReview.change.changeType) {
    GitCommitChangeType.ADDED, GitCommitChangeType.MODIFIED -> changeInReview.change.new!!.path
    GitCommitChangeType.DELETED -> changeInReview.change.old!!.path
  }.trimStart('/', '\\')

  val isDirectory = when (changeInReview.change.changeType) {
    GitCommitChangeType.ADDED, GitCommitChangeType.MODIFIED -> changeInReview.change.new!!.isDirectory()
    GitCommitChangeType.DELETED -> changeInReview.change.old!!.isDirectory()
  }
  return LocalFilePath(path, isDirectory)
}

internal fun GitFile?.getFilePath(): FilePath? {
  this ?: return null
  return LocalFilePath(path.trimStart('/', '\\'), isDirectory())
}

internal fun getChangeFilePathInfo(changeInReview: ChangeInReview): ChangeFilePathInfo =
  when (changeInReview.change.changeType) {
    GitCommitChangeType.ADDED ->
      ChangeFilePathInfo(null, changeInReview.change.new.getFilePath())
    GitCommitChangeType.MODIFIED ->
      ChangeFilePathInfo(changeInReview.change.old.getFilePath(), changeInReview.change.new.getFilePath())
    GitCommitChangeType.DELETED ->
      ChangeFilePathInfo(changeInReview.change.old.getFilePath(), null)
  }

internal data class ChangeFilePathInfo(val old: FilePath?, val new: FilePath?)
