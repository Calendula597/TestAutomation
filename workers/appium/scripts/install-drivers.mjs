import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const [nodeMajor, nodeMinor] = process.versions.node.split(".").map(Number);
if (nodeMajor !== 24 || nodeMinor < 15) {
  throw new Error(`Appium drivers require Node 24.15+, current runtime is ${process.version}`);
}
console.log(`Appium driver runtime: ${process.version}`);

const appium = fileURLToPath(new URL("../node_modules/appium/index.js", import.meta.url));
const home = fileURLToPath(new URL("../.appium", import.meta.url));
const manifest = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));

function run(args) {
  const result = spawnSync(process.execPath, [appium, ...args], {
    env: { ...process.env, APPIUM_HOME: home },
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

for (const [name, automationName] of [
  ["appium-wincore-driver", "wincore"],
  ["appium-uiautomator2-driver", "uiautomator2"],
]) {
  const installed = new URL(`../.appium/node_modules/${name}/package.json`, import.meta.url);
  if (existsSync(installed)) {
    const version = JSON.parse(readFileSync(installed, "utf8")).version;
    if (version === manifest.dependencies[name]) continue;
    run(["driver", "uninstall", automationName]);
  }
  run(["driver", "install", "--source=npm", `${name}@${manifest.dependencies[name]}`]);
}
