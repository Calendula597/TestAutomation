import { expect, it } from "vitest";

it("slow scenario", async () => {
  await new Promise((resolve) => setTimeout(resolve, 30_000));
  expect(true).toBe(true);
});
