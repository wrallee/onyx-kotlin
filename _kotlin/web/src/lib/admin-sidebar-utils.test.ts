import {
  buildKotlinAdminItems,
  type FeatureFlags,
} from "@/lib/admin-sidebar-utils";
import {
  ApplicationStatus,
  QueryHistoryType,
  Tier,
  type Settings,
} from "@/lib/settings/types";

const settings: Settings = {
  anonymous_user_enabled: false,
  invite_only_enabled: false,
  notifications: [],
  needs_reindexing: false,
  gpu_enabled: false,
  application_status: ApplicationStatus.ACTIVE,
  auto_scroll: true,
  temperature_override_enabled: false,
  query_history_type: QueryHistoryType.DISABLED,
  vector_db_enabled: true,
  onyx_craft_available: false,
  hooks_enabled: false,
  tier: Tier.COMMUNITY,
};

const flags: FeatureFlags = {
  vectorDbEnabled: true,
  enableCloud: false,
  tier: Tier.COMMUNITY,
  customAnalyticsEnabled: false,
  hasSubscription: false,
  hooksEnabled: false,
  opensearchEnabled: true,
  queryHistoryEnabled: false,
  craftAvailable: false,
};

describe("buildKotlinAdminItems", () => {
  it("keeps the original default visibility while disabling unsupported pages", () => {
    const items = buildKotlinAdminItems(flags, settings);

    expect(
      items.filter((item) => !item.disabled).map((item) => item.nameId)
    ).toEqual(["existingConnectors", "addConnector", "documentSets"]);
    expect(items.map((item) => item.nameId)).not.toContain("craftAccess");
    expect(items.map((item) => item.nameId)).not.toContain("hookExtensions");
    expect(items.map((item) => item.nameId)).not.toContain("queryHistory");
    expect(items.find((item) => item.nameId === "agents")).toMatchObject({
      disabled: true,
      kotlinUnsupported: true,
      tierDisabled: false,
    });
    expect(
      items.find((item) => item.nameId === "serviceAccounts")
    ).toMatchObject({
      disabled: true,
      tierDisabled: true,
    });
  });
});
