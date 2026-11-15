import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createDemoServer } from "../src/server.js";

let server;
let baseUrl;

beforeAll(async () => {
  server = createDemoServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

afterAll(async () => {
  await new Promise((resolve) => server.close(resolve));
});

describe("HTTP integration", () => {
  it("returns the real sum over HTTP", async () => {
    const response = await fetch(`${baseUrl}/sum?a=2&b=5`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ result: 7 });
  });
});
