import { expect, type Page } from "@playwright/test";
import { ADMIN_ROUTES } from "@/lib/admin-routes";

export class IndexSettingsPage {
  constructor(readonly page: Page) {}

  async goto() {
    await this.page.goto(ADMIN_ROUTES.INDEX_SETTINGS.path);
    await expect(this.page.getByLabel("admin-page-title")).toHaveText(
      /index settings/i
    );
  }
}
