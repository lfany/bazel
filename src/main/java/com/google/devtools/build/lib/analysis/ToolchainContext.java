// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis;

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.SkylarkIndexable;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Contains toolchain-related information needed for a {@link RuleContext}. */
@Immutable
@ThreadSafe
@SkylarkModule(
  name = "ToolchainContext",
  category = SkylarkModuleCategory.BUILTIN,
  doc = "Stores toolchains available to a given rule."
)
public class ToolchainContext {
  public static ToolchainContext create(
      List<Label> requiredToolchains, ImmutableBiMap<Label, Label> resolvedLabels) {
    ToolchainContext toolchainContext =
        new ToolchainContext(requiredToolchains, new ResolvedToolchainLabels(resolvedLabels));
    return toolchainContext;
  }

  private final ImmutableList<Label> requiredToolchains;

  // Map from toolchain type labels to actual resolved toolchain labels.
  private final ResolvedToolchainLabels resolvedToolchainLabels;

  // Stores the actual ToolchainInfo provider for each toolchain type.
  private ResolvedToolchainProviders resolvedToolchainProviders =
      ResolvedToolchainProviders.empty();

  private ToolchainContext(
      List<Label> requiredToolchains, ResolvedToolchainLabels resolvedToolchainLabels) {
    this.requiredToolchains = ImmutableList.copyOf(requiredToolchains);
    this.resolvedToolchainLabels = resolvedToolchainLabels;
  }

  public void resolveToolchains(OrderedSetMultimap<Attribute, ConfiguredTarget> prerequisiteMap) {
    if (!this.requiredToolchains.isEmpty()) {
      this.resolvedToolchainProviders =
          ResolvedToolchainProviders.create(resolvedToolchainLabels, prerequisiteMap);
    }
  }

  @Nullable
  public SkylarkIndexable getResolvedToolchainProviders() {
    return resolvedToolchainProviders;
  }

  public ImmutableSet<Label> getResolvedToolchainLabels() {
    return resolvedToolchainLabels.getToolchainLabels();
  }

  /** Tracks the mapping from toolchain type label to the label of the actual resolved toolchain. */
  private static class ResolvedToolchainLabels {
    private final ImmutableBiMap<Label, Label> toolchainLabels;

    private ResolvedToolchainLabels(ImmutableBiMap<Label, Label> toolchainLabels) {
      this.toolchainLabels = toolchainLabels;
    }

    public Label getType(Label toolchainLabel) {
      return toolchainLabels.inverse().get(toolchainLabel);
    }

    public Label getResolvedToolchainLabel(Label toolchainType) {
      return toolchainLabels.get(toolchainType);
    }

    public ImmutableSet<Label> getToolchainLabels() {
      return toolchainLabels.values();
    }
  }

  /** Tracks the mapping from toolchain type label to {@link ToolchainInfo} provider. */
  private static class ResolvedToolchainProviders implements SkylarkValue, SkylarkIndexable {
    private static ResolvedToolchainProviders empty() {
      return new ResolvedToolchainProviders(ImmutableMap.<Label, ToolchainInfo>of());
    }

    private static ResolvedToolchainProviders create(
        ResolvedToolchainLabels resolvedToolchainLabels,
        OrderedSetMultimap<Attribute, ConfiguredTarget> prerequisiteMap) {
      // Find the prerequisites associated with the $toolchains attribute.
      Optional<Attribute> toolchainAttribute =
          prerequisiteMap
              .keys()
              .stream()
              .filter(attribute -> attribute.getName().equals(PlatformSemantics.TOOLCHAINS_ATTR))
              .findFirst();
      Preconditions.checkState(
          toolchainAttribute.isPresent(),
          "No toolchains attribute found while loading resolved toolchains");

      ImmutableMap.Builder<Label, ToolchainInfo> toolchains = new ImmutableMap.Builder<>();
      for (ConfiguredTarget target : prerequisiteMap.get(toolchainAttribute.get())) {
        Label discoveredLabel = target.getLabel();
        Label toolchainType = resolvedToolchainLabels.getType(discoveredLabel);
        if (toolchainType != null) {
          ToolchainInfo toolchainInfo = PlatformProviderUtils.toolchain(target);
          toolchains.put(toolchainType, toolchainInfo);
        }
      }
      return new ResolvedToolchainProviders(toolchains.build());
    }

    private final ImmutableMap<Label, ToolchainInfo> toolchains;

    private ResolvedToolchainProviders(ImmutableMap<Label, ToolchainInfo> toolchains) {
      this.toolchains = toolchains;
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<toolchain_context.resolved_labels: ");
      printer.append(toolchains.keySet().stream()
          .map(key -> key.toString())
          .collect(joining(", ")));
      printer.append(">");
    }

    private Label transformKey(Object key, Location loc) throws EvalException {
      if (key instanceof Label) {
        Label toolchainType = (Label) key;
        return toolchainType;
      } else if (key instanceof String) {
        Label toolchainType = null;
        String rawLabel = (String) key;
        try {
          toolchainType = Label.parseAbsolute(rawLabel);
        } catch (LabelSyntaxException e) {
          throw new EvalException(
              loc, String.format("Unable to parse toolchain %s: %s", rawLabel, e.getMessage()), e);
        }
        return toolchainType;
      } else {
        throw new EvalException(
            loc,
            String.format(
                "Toolchains only supports indexing by toolchain type, got %s instead",
                EvalUtils.getDataTypeName(key)));
      }
    }

    @Override
    public ToolchainInfo getIndex(Object key, Location loc) throws EvalException {
      Label toolchainType = transformKey(key, loc);
      return toolchains.get(toolchainType);
    }

    @Override
    public boolean containsKey(Object key, Location loc) throws EvalException {
      Label toolchainType = transformKey(key, loc);
      return toolchains.containsKey(toolchainType);
    }
  }
}
