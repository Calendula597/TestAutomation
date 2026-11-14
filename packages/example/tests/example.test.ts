import { describe, expect, it } from "vitest";

import { greet } from "../index.js";

describe("greet", () => {
  it("returns a greeting through the package entry point", () => {
    expect(greet("TestAutomation")).toBe("Hello, TestAutomation!");
  });
});
