import { expect, it } from "vitest";
import { sum } from "../src/math.js";

it("intentional failing scenario", () => {
  expect(sum(1, 1)).toBe(3);
});
