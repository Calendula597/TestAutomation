import http from "node:http";
import { sum } from "./math.js";

export function createDemoServer() {
  return http.createServer((request, response) => {
    const url = new URL(request.url, "http://localhost");
    const a = Number(url.searchParams.get("a"));
    const b = Number(url.searchParams.get("b"));
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ result: sum(a, b) }));
  });
}
