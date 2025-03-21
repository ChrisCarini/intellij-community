// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.codeStyle.arrangement.std;

import com.intellij.psi.codeStyle.arrangement.ArrangementSettings;
import com.intellij.psi.codeStyle.arrangement.ArrangementUtil;
import com.intellij.psi.codeStyle.arrangement.group.ArrangementGroupingRule;
import com.intellij.psi.codeStyle.arrangement.match.ArrangementMatchRule;
import com.intellij.psi.codeStyle.arrangement.match.ArrangementSectionRule;
import com.intellij.psi.codeStyle.arrangement.match.StdArrangementMatchRule;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StdArrangementSettings implements ArrangementSettings {
  private final @NotNull List<ArrangementSectionRule> mySectionRules     = new ArrayList<>();
  private final @NotNull List<ArrangementGroupingRule> myGroupings       = new ArrayList<>();

  // cached values
  protected final @NotNull List<StdArrangementMatchRule> myRulesByPriority = Collections.synchronizedList(new ArrayList<>());

  public StdArrangementSettings() {
  }

  public StdArrangementSettings(@NotNull List<? extends ArrangementSectionRule> rules) {
    this(Collections.emptyList(), rules);
  }

  public StdArrangementSettings(@NotNull List<? extends ArrangementGroupingRule> groupingRules,
                                @NotNull List<? extends ArrangementSectionRule> sectionRules) {
    myGroupings.addAll(groupingRules);
    mySectionRules.addAll(sectionRules);
  }

  public static StdArrangementSettings createByMatchRules(@NotNull List<? extends ArrangementGroupingRule> groupingRules,
                                                          @NotNull List<? extends StdArrangementMatchRule> matchRules) {
    final List<ArrangementSectionRule> sectionRules = new ArrayList<>();
    for (StdArrangementMatchRule rule : matchRules) {
      sectionRules.add(ArrangementSectionRule.create(rule));
    }
    return new StdArrangementSettings(groupingRules, sectionRules);
  }

  protected @NotNull List<ArrangementGroupingRule> cloneGroupings() {
    final ArrayList<ArrangementGroupingRule> groupings = new ArrayList<>();
    for (ArrangementGroupingRule grouping : myGroupings) {
      groupings.add(grouping.clone());
    }
    return groupings;
  }

  protected @NotNull List<ArrangementSectionRule> cloneSectionRules() {
    final ArrayList<ArrangementSectionRule> rules = new ArrayList<>();
    for (ArrangementSectionRule rule : mySectionRules) {
      rules.add(rule.clone());
    }
    return rules;
  }

  @Override
  public @NotNull ArrangementSettings clone() {
    return new StdArrangementSettings(cloneGroupings(), cloneSectionRules());
  }

  @Override
  public @NotNull List<ArrangementGroupingRule> getGroupings() {
    return myGroupings;
  }

  @Override
  public @NotNull List<ArrangementSectionRule> getSections() {
    return mySectionRules;
  }

  @Override
  public @NotNull List<StdArrangementMatchRule> getRules() {
    return ArrangementUtil.collectMatchRules(mySectionRules);
  }

  @Override
  public @NotNull List<? extends ArrangementMatchRule> getRulesSortedByPriority() {
    synchronized (myRulesByPriority) {
      if (myRulesByPriority.isEmpty()) {
        for (ArrangementSectionRule rule : mySectionRules) {
          myRulesByPriority.addAll(rule.getMatchRules());
        }
        ContainerUtil.sort(myRulesByPriority);
      }
    }
    return myRulesByPriority;
  }

  public void addRule(@NotNull StdArrangementMatchRule rule) {
    addSectionRule(rule);
    myRulesByPriority.clear();
  }

  public void addSectionRule(@NotNull StdArrangementMatchRule rule) {
    mySectionRules.add(ArrangementSectionRule.create(rule));
  }

  public void addGrouping(@NotNull ArrangementGroupingRule rule) {
    myGroupings.add(rule);
  }

  @Override
  public int hashCode() {
    int result = mySectionRules.hashCode();
    result = 31 * result + myGroupings.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    StdArrangementSettings settings = (StdArrangementSettings)o;

    if (!myGroupings.equals(settings.myGroupings)) return false;
    if (!mySectionRules.equals(settings.mySectionRules)) return false;

    return true;
  }
}
