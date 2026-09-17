import { expect, test } from "@playwright/test";
import { loginAs } from "@tests/e2e/utils/auth";
import { IndexSettingsPage } from "./IndexSettingsPage";

const granite = "ibm-granite/granite-embedding-311m-multilingual-r2";

test.describe("Kotlin index settings @exclusive", () => {
  test.beforeEach(async ({ page }) => {
    await page.context().clearCookies();
    await loginAs(page, "admin");
    await page.route("**/api/admin/embedding/models", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          {
            model_name: granite,
            display_name: "Granite",
            dimension: 768,
            available: true,
            status: "READY",
            compatible_past_settings_id: null,
          },
          {
            model_name: "microsoft/harrier-oss-v1-0.6b",
            display_name: "Harrier",
            dimension: 1024,
            available: false,
            status: "UNAVAILABLE",
            compatible_past_settings_id: null,
          },
        ]),
      })
    );
    await page.route(
      "**/api/search-settings/get-current-search-settings",
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            id: 1,
            model_name: granite,
            status: "PRESENT",
          }),
        })
    );
    await page.route(
      "**/api/search-settings/get-secondary-search-settings",
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: "null",
        })
    );
  });

  test("shows only local models and approved actions", async ({ page }) => {
    await new IndexSettingsPage(page).goto();
    await expect(page.getByText("Granite")).toBeVisible();
    await expect(page.getByText("Harrier")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Full Reindex" }).first()
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Sync & Switch" })
    ).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "Full Reindex" }).nth(1)
    ).toBeDisabled();
  });

  test("full reindex calls only the full endpoint", async ({ page }) => {
    await new IndexSettingsPage(page).goto();
    const request = page.waitForRequest(
      (value) =>
        value.url().endsWith("/api/search-settings/reindex/full") &&
        value.method() === "POST"
    );
    await page.getByRole("button", { name: "Full Reindex" }).first().click();
    await request;
  });
});
