import { describe, expect, it } from "vitest";
import { clamp, divide, sum } from "../src/math.js";

describe("math", () => {
  it("adds numbers", () => {
    expect(sum(1, 2)).toBe(3);
  });

  it("divides numbers and rejects zero", () => {
    expect(divide(8, 2)).toBe(4);
    expect(() => divide(1, 0)).toThrow("除数不能为零");
  });

  it("clamps to both boundaries and the original value", () => {
    expect(clamp(0, 1, 5)).toBe(1);
    expect(clamp(9, 1, 5)).toBe(5);
    expect(clamp(3, 1, 5)).toBe(3);
  });
});
