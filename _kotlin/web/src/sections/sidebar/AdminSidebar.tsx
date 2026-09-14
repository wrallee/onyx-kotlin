"use client";

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { SidebarLayouts, useSidebarState } from "@opal/layouts";
import { Divider, InputTypeIn, SidebarTab } from "@opal/components";
import { SvgSearch, SvgX } from "@opal/icons";
import useFilter from "@/hooks/useFilter";
import {
  buildKotlinAdminItems,
  groupBySection,
  type AdminNavItemId,
  type AdminNavSectionId,
  type FeatureFlags,
  type SidebarItemEntry,
} from "@/lib/admin-sidebar-utils";
import { SvgOnyxLogo, SvgOnyxLogoTyped } from "@opal/logos";
import { useSettings } from "@/lib/settings/hooks";
import { useCustomAnalyticsEnabled } from "@/lib/hooks/useCustomAnalyticsEnabled";
import { NEXT_PUBLIC_CLOUD_ENABLED } from "@/lib/constants";
import { Tier } from "@/lib/settings/types";
import { markdown } from "@opal/utils";

function renderSidebarLogo(folded: boolean) {
  return folded ? SvgOnyxLogo : SvgOnyxLogoTyped;
}

export default function AdminSidebar() {
  const t = useTranslations("sidebar");
  const { folded, setFolded } = useSidebarState();
  const searchRef = useRef<HTMLInputElement>(null);
  const [focusSearch, setFocusSearch] = useState(false);
  const pathname = usePathname();
  const settings = useSettings();
  const { customAnalyticsEnabled } = useCustomAnalyticsEnabled();
  const flags: FeatureFlags = {
    vectorDbEnabled: settings.vectorDbEnabled,
    enableCloud: NEXT_PUBLIC_CLOUD_ENABLED,
    tier: settings.tier,
    customAnalyticsEnabled,
    hasSubscription: false,
    hooksEnabled: settings.hooks_enabled ?? false,
    opensearchEnabled: settings.opensearch_indexing_enabled ?? false,
    queryHistoryEnabled:
      settings.query_history_type !== "disabled" &&
      !settings.hide_query_history_from_admin_panel,
    craftAvailable: settings.onyx_craft_available ?? false,
  };
  const allItems = buildKotlinAdminItems(flags, settings);

  useEffect(() => {
    if (focusSearch && !folded && searchRef.current) {
      searchRef.current.focus();
      setFocusSearch(false);
    }
  }, [focusSearch, folded]);

  const navLabels = useMemo<Record<AdminNavItemId, string>>(
    () => ({
      languageModels: t("adminNav.items.languageModels.label"),
      webSearch: t("adminNav.items.webSearch.label"),
      imageGeneration: t("adminNav.items.imageGeneration.label"),
      voice: t("adminNav.items.voice.label"),
      codeInterpreter: t("adminNav.items.codeInterpreter.label"),
      chatPreferences: t("adminNav.items.chatPreferences.label"),
      craftAccess: t("adminNav.items.craftAccess.label"),
      craftApps: t("adminNav.items.craftApps.label"),
      craftInstructions: t("adminNav.items.craftInstructions.label"),
      customAnalytics: t("adminNav.items.customAnalytics.label"),
      agents: t("adminNav.items.agents.label"),
      mcpActions: t("adminNav.items.mcpActions.label"),
      openapiActions: t("adminNav.items.openapiActions.label"),
      existingConnectors: t("adminNav.items.existingConnectors.label"),
      addConnector: t("adminNav.items.addConnector.label"),
      documentSets: t("adminNav.items.documentSets.label"),
      indexSettings: t("adminNav.items.indexSettings.label"),
      serviceAccounts: t("adminNav.items.serviceAccounts.label"),
      slackIntegration: t("adminNav.items.slackIntegration.label"),
      discordIntegration: t("adminNav.items.discordIntegration.label"),
      hookExtensions: t("adminNav.items.hookExtensions.label"),
      users: t("adminNav.items.users.label"),
      groups: t("adminNav.items.groups.label"),
      scim: t("adminNav.items.scim.label"),
      plansAndBilling: t("adminNav.items.plansAndBilling.label"),
      appearanceAndTheming: t("adminNav.items.appearanceAndTheming.label"),
      securityAndHardening: t("adminNav.items.securityAndHardening.label"),
      ssoProviders: t("adminNav.items.ssoProviders.label"),
      usage: t("adminNav.items.usage.label"),
      analytics: t("adminNav.items.analytics.label"),
      queryHistory: t("adminNav.items.queryHistory.label"),
      tracing: t("adminNav.items.tracing.label"),
      exportLogs: t("adminNav.items.exportLogs.label"),
      upgradePlan: t("adminNav.items.upgradePlan.label"),
    }),
    [t]
  );
  const sectionLabels = useMemo<Record<AdminNavSectionId, string>>(
    () => ({
      craft: t("adminNav.sections.craft.label"),
      agentsAndActions: t("adminNav.sections.agentsAndActions.label"),
      documentsAndKnowledge: t("adminNav.sections.documentsAndKnowledge.label"),
      integrations: t("adminNav.sections.integrations.label"),
      permissions: t("adminNav.sections.permissions.label"),
      organization: t("adminNav.sections.organization.label"),
      usage: t("adminNav.sections.usage.label"),
    }),
    [t]
  );
  const itemExtractor = useCallback(
    (item: SidebarItemEntry) => navLabels[item.nameId],
    [navLabels]
  );
  const { query, setQuery, filtered } = useFilter(allItems, itemExtractor);
  const enabledGroups = groupBySection(
    filtered.filter((item) => !item.disabled)
  );
  const disabledGroups = groupBySection(
    filtered.filter((item) => item.disabled)
  );

  return (
    <SidebarLayouts.Root>
      <SidebarLayouts.Header
        renderAppLogo={renderSidebarLogo}
        logoHref="/"
        showLogoWhenFolded
      >
        {folded ? (
          <SidebarTab
            icon={SvgSearch}
            onClick={() => {
              setFolded(false);
              setFocusSearch(true);
            }}
          >
            {t("adminSidebar.search.label")}
          </SidebarTab>
        ) : (
          <InputTypeIn
            ref={searchRef}
            variant="internal"
            searchIcon
            placeholder={t("adminSidebar.searchInput.placeholder")}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            clearButton
          />
        )}
      </SidebarLayouts.Header>
      <SidebarLayouts.Body scrollKey="admin-sidebar">
        {enabledGroups.map((group, index) => (
          <React.Fragment key={index}>
            <SidebarLayouts.Section
              title={
                group.sectionId ? sectionLabels[group.sectionId] : undefined
              }
            >
              {group.items.map(({ icon, link, nameId }) => (
                <SidebarTab
                  key={link}
                  icon={icon}
                  href={link}
                  selected={pathname.startsWith(link)}
                >
                  {navLabels[nameId]}
                </SidebarTab>
              ))}
            </SidebarLayouts.Section>
          </React.Fragment>
        ))}
        {disabledGroups.length > 0 && (
          <>
            <Divider paddingPerpendicular={0} />
            <div />
          </>
        )}
        {disabledGroups.map((group, index) => (
          <React.Fragment key={`disabled-${index}`}>
            <SidebarLayouts.Section
              title={
                group.sectionId ? sectionLabels[group.sectionId] : undefined
              }
              disabled
            >
              {group.items.map(
                ({ icon, link, nameId, requiredTier, tierDisabled }) => (
                  <SidebarTab
                    key={link}
                    disabled
                    icon={icon}
                    tooltip={
                      tierDisabled
                        ? markdown(
                            requiredTier === Tier.ENTERPRISE
                              ? t("adminSidebar.enterpriseOnly.tooltip")
                              : t(
                                  "adminSidebar.businessOrEnterpriseOnly.tooltip"
                                )
                          )
                        : "Not supported in this Kotlin port."
                    }
                  >
                    {navLabels[nameId]}
                  </SidebarTab>
                )
              )}
            </SidebarLayouts.Section>
          </React.Fragment>
        ))}
      </SidebarLayouts.Body>
      <SidebarLayouts.Footer>
        <SidebarTab icon={SvgX} href="/" variant="sidebar-light">
          {t("adminSidebar.exitAdminPanel.label")}
        </SidebarTab>
      </SidebarLayouts.Footer>
    </SidebarLayouts.Root>
  );
}
