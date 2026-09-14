"use client";
import { useTranslations } from "next-intl";
import { SettingsLayouts } from "@opal/layouts";
import { SourceCategory, SourceMetadata } from "@/lib/search/interfaces";
import { listSourceMetadata } from "@/lib/sources";
import { Button } from "@opal/components";
import {
  useCallback,
  useDeferredValue,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import SourceTile from "@/components/SourceTile";
import { InputTypeIn } from "@opal/components";
import Text from "@/refresh-components/texts/Text";
import { ADMIN_ROUTES } from "@/lib/admin-routes";
import { isKotlinAdminSupportedSource } from "@/lib/kotlin-admin";

const route = ADMIN_ROUTES.ADD_CONNECTOR;

// The category headings come from the `SourceCategory` enum, whose values are
// identifiers shared across the app. Map each one to a message key (inside the
// `admin.addConnector` namespace) so the component can resolve it with `t`.
const CATEGORY_LABEL_KEYS = {
  [SourceCategory.Wiki]: "categories.wiki.label",
  [SourceCategory.Storage]: "categories.storage.label",
  [SourceCategory.TicketingAndTaskManagement]:
    "categories.ticketingAndTaskManagement.label",
  [SourceCategory.Messaging]: "categories.messaging.label",
  [SourceCategory.Sales]: "categories.sales.label",
  [SourceCategory.CodeRepository]: "categories.codeRepository.label",
  [SourceCategory.Other]: "categories.other.label",
} as const satisfies Record<SourceCategory, string>;

export default function Page() {
  const t = useTranslations("admin.addConnector");
  const sources = useMemo(() => listSourceMetadata(), []);
  const availableSources = useMemo(
    () =>
      sources.filter((source) =>
        isKotlinAdminSupportedSource(source.internalName)
      ),
    [sources]
  );
  const unsupportedSources = useMemo(
    () =>
      sources.filter(
        (source) => !isKotlinAdminSupportedSource(source.internalName)
      ),
    [sources]
  );

  const [rawSearchTerm, setSearchTerm] = useState("");
  const searchTerm = useDeferredValue(rawSearchTerm);

  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (searchInputRef.current) {
      searchInputRef.current.focus();
    }
  }, []);

  const filterSources = useCallback(
    (sources: SourceMetadata[]) => {
      if (!searchTerm) return sources;
      const lowerSearchTerm = searchTerm.toLowerCase();
      return sources.filter(
        (source) =>
          source.displayName.toLowerCase().includes(lowerSearchTerm) ||
          source.category.toLowerCase().includes(lowerSearchTerm)
      );
    },
    [searchTerm]
  );

  const popularSources = useMemo(() => {
    const filtered = filterSources(unsupportedSources);
    return unsupportedSources.filter(
      (source) =>
        source.isPopular &&
        (filtered.includes(source) ||
          source.displayName.toLowerCase().includes(searchTerm.toLowerCase()))
    );
  }, [unsupportedSources, filterSources, searchTerm]);

  const categorizedSources = useMemo(() => {
    const filtered = filterSources(unsupportedSources);
    const categories = Object.values(SourceCategory).reduce(
      (acc, category) => {
        acc[category] = unsupportedSources.filter(
          (source) =>
            source.category === category &&
            (filtered.includes(source) ||
              category.toLowerCase().includes(searchTerm.toLowerCase()))
        );
        return acc;
      },
      {} as Record<SourceCategory, SourceMetadata[]>
    );
    return categories;
  }, [unsupportedSources, filterSources, searchTerm]);

  const filteredAvailableSources = useMemo(
    () => filterSources(availableSources),
    [availableSources, filterSources]
  );

  // When searching, dedupe Popular against whatever is already in results
  const resultIds = useMemo(() => {
    if (!searchTerm) return new Set<string>();
    return new Set(
      Object.values(categorizedSources)
        .flat()
        .map((s) => s.internalName)
    );
  }, [categorizedSources, searchTerm]);

  const dedupedPopular = useMemo(() => {
    if (!searchTerm) return popularSources;
    return popularSources.filter((s) => !resultIds.has(s.internalName));
  }, [popularSources, resultIds, searchTerm]);

  const handleKeyPress = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== "Enter") return;
    const firstSource = filteredAvailableSources.at(0);
    if (firstSource) window.open(firstSource.adminUrl, "_self");
  };

  return (
    <SettingsLayouts.Root width="full">
      <SettingsLayouts.Header
        icon={route.icon}
        title={route.title}
        rightChildren={
          <Button href="/admin/indexing/status">
            {t("seeConnectorsButton.label")}
          </Button>
        }
        divider
      />
      <SettingsLayouts.Body>
        <InputTypeIn
          type="text"
          placeholder={t("search.placeholder")}
          ref={searchInputRef}
          value={rawSearchTerm} // keep the input bound to immediate state
          onChange={(event) => setSearchTerm(event.target.value)}
          onKeyDown={handleKeyPress}
        />

        {filteredAvailableSources.length > 0 && (
          <div className="pt-8">
            <Text as="p" headingH3>
              {t("available.title")}
            </Text>
            <div className="flex flex-wrap gap-4 p-4">
              {filteredAvailableSources.map((source, sourceInd) => (
                <SourceTile
                  preSelect={(searchTerm?.length ?? 0) > 0 && sourceInd === 0}
                  key={source.internalName}
                  sourceMetadata={source}
                  navigationUrl={source.adminUrl}
                />
              ))}
            </div>
          </div>
        )}

        {dedupedPopular.length > 0 && (
          <div className="pt-8">
            <Text as="p" headingH3>
              {t("popular.title")}
            </Text>
            <div className="flex flex-wrap gap-4 p-4">
              {dedupedPopular.map((source) => (
                <SourceTile
                  preSelect={false}
                  key={source.internalName}
                  sourceMetadata={source}
                  navigationUrl={source.adminUrl}
                />
              ))}
            </div>
          </div>
        )}

        {Object.entries(categorizedSources)
          .filter(([_, sources]) => sources.length > 0)
          .map(([category, sources], categoryInd) => (
            <div key={category} className="pt-8">
              <Text as="p" headingH3>
                {t(CATEGORY_LABEL_KEYS[category as SourceCategory])}
              </Text>
              <div className="flex flex-wrap gap-4 p-4">
                {sources.map((source, sourceInd) => (
                  <SourceTile
                    preSelect={
                      (searchTerm?.length ?? 0) > 0 &&
                      categoryInd == 0 &&
                      sourceInd == 0
                    }
                    key={source.internalName}
                    sourceMetadata={source}
                    navigationUrl={source.adminUrl}
                  />
                ))}
              </div>
            </div>
          ))}
      </SettingsLayouts.Body>
    </SettingsLayouts.Root>
  );
}
