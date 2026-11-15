import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const appium = fileURLToPath(new URL("../node_modules/appium/index.js", import.meta.url));
const home = fileURLToPath(new URL("../.appium", import.meta.url));
const expected = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")).dependencies;

function run(args) {
  const result = spawnSync(process.execPath, [appium, ...args], {
    env: { ...process.env, APPIUM_HOME: home },
    encoding: "utf8",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    process.stderr.write(result.stderr);
    process.stderr.write(result.stdout);
    process.exit(result.status ?? 1);
  }
  return result;
}

const version = run(["--version"]).stdout.trim();
if (version !== expected.appium) throw new Error(`Appium ${expected.appium} required, found ${version}`);
const installed = JSON.parse(run(["driver", "list", "--installed", "--json"]).stdout);
for (const [name, automationName] of [
  ["appium-wincore-driver", "wincore"],
  ["appium-uiautomator2-driver", "uiautomator2"],
]) {
  const actual = installed[automationName]?.version;
  if (actual !== expected[name]) {
    throw new Error(`${name} ${expected[name]} required, found ${actual ?? "not installed"}`);
  }
}
console.log(`Appium ${version}; WinCore ${installed.wincore.version}; UiAutomator2 ${installed.uiautomator2.version}`);
