// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.update;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.impl.LocalChangesUnderRoots;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitNotificationIdsHolder;
import git4idea.GitUtil;
import git4idea.branch.GitBranchPair;
import git4idea.branch.GitRebaseParams;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.i18n.GitBundle;
import git4idea.rebase.GitRebaseProcess;
import git4idea.rebase.GitRebaser;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Handles 'git pull --rebase'
 */
public final class GitRebaseUpdater extends GitUpdater {
  private static final Logger LOG = Logger.getInstance(GitRebaseUpdater.class.getName());
  private final @NotNull GitBranchPair myBranchPair;

  public GitRebaseUpdater(@NotNull Project project,
                          @NotNull Git git,
                          @NotNull GitRepository repository,
                          @NotNull GitBranchPair branchPair,
                          @NotNull ProgressIndicator progressIndicator,
                          @NotNull UpdatedFiles updatedFiles) {
    super(project, git, repository, progressIndicator, updatedFiles);
    myBranchPair = branchPair;
  }

  @Override
  public boolean isSaveNeeded() {
    Collection<Change> localChanges = LocalChangesUnderRoots.getChangesUnderRoots(Collections.singletonList(myRoot), myProject).get(myRoot);
    try {
      return !ContainerUtil.isEmpty(localChanges) ||
             GitUtil.hasLocalChanges(true, myProject, myRoot);
    }
    catch (VcsException e) {
      LOG.info("isSaveNeeded failed to check local changes", e);
      return true;
    }
  }

  @Override
  protected @NotNull GitUpdateResult doUpdate() {
    LOG.info("doUpdate ");
    if (!checkForRebasingPublishedCommits()) {
      return GitUpdateResult.CANCEL;
    }

    String remoteBranch = getRemoteBranchToMerge();
    List<String> params = Collections.singletonList(remoteBranch);
    GitUpdateResult result = new GitRebaser(myProject, myGit, myProgressIndicator).rebase(myRoot, params, null);
    if (result == GitUpdateResult.CANCEL) {
      new GitRebaser(myProject, myGit, myProgressIndicator).abortRebase(myRoot);
    }
    return result;
  }

  private boolean checkForRebasingPublishedCommits() {
    GitBranchPair sourceAndTarget = getSourceAndTarget();
    String currentRef = sourceAndTarget.getSource().getFullName();
    String baseRef = sourceAndTarget.getTarget().getFullName();
    GitRebaseParams.RebaseUpstream upstream = GitRebaseParams.RebaseUpstream.Companion.fromRefString(baseRef);

    if (GitRebaseProcess.isRebasingPublishedCommit(myRepository, upstream, currentRef)) {
      return GitRebaseProcess.askIfShouldRebasePublishedCommit();
    }
    return true;
  }

  private @NotNull String getRemoteBranchToMerge() {
    return myBranchPair.getTarget().getName();
  }

  @NotNull
  GitBranchPair getSourceAndTarget() {
    return myBranchPair;
  }

  @Override
  public String toString() {
    return "Rebase updater";
  }

  /**
   * Tries to execute {@code git merge --ff-only}.
   * @return true, if everything is successful; false for any error (to let a usual "fair" update deal with it).
   */
  public boolean fastForwardMerge() {
    LOG.info("Trying fast-forward merge for " + myRoot);
    GitRepository repository = GitRepositoryManager.getInstance(myProject).getRepositoryForRoot(myRoot);
    if (repository == null) {
      LOG.error("Repository is null for " + myRoot);
      return false;
    }
    try {
      markStart(repository);
    }
    catch (VcsException e) {
      LOG.info("Couldn't mark start for repository " + repository, e);
      return false;
    }

    GitCommandResult result = myGit.merge(repository, getRemoteBranchToMerge(), Collections.singletonList("--ff-only"));

    try {
      markEnd(repository);
    }
    catch (VcsException e) {
      // this is not critical, and update has already happened,
      // so we just notify the user about problems with collecting the updated changes.
      LOG.info("Couldn't mark end for repository " + repository, e);
      VcsNotifier.getInstance(myProject).
        notifyMinorWarning(GitNotificationIdsHolder.COLLECT_UPDATED_CHANGES_ERROR,
                           GitBundle.message("notification.title.couldnt.collect.updated.files.info"),
                           GitBundle.message("notification.content.couldnt.collect.updated.files.info", repository));
    }
    return result.success();
  }

}
